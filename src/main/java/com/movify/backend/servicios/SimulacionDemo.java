package com.movify.backend.servicios;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.movify.backend.comun.ActorBd;
import com.movify.backend.comun.Datos;
import com.movify.backend.servicios.simulacion.SimulacionViajes;

import jakarta.annotation.PreDestroy;

/**
 * Conductor de demostración para demos y sustentación. SOLO se crea con app.demo.simulacion=true.
 *
 * Si en 10 s ningún conductor acepta, un conductor demo aprobado (correo "demo…@movify.co",
 * ver scripts/datos_demo.sql) acepta el servicio. El MOVIMIENTO lo hace SimulacionViajes (el mismo motor
 * de todos los viajes: ruta OSRM por las calles). Aquí solo se decide cuándo cambiar de estado:
 *   llegó al final de la recogida → en_origen → en_curso;   llegó al destino → finalizado.
 * Pasa por los mismos triggers que un conductor real: nunca toca conductores reales ni crea usuarios.
 */
@Component
@ConditionalOnProperty(name = "app.demo.simulacion", havingValue = "true")
public class SimulacionDemo {

    private static final Logger log = LoggerFactory.getLogger(SimulacionDemo.class);
    /** Cada cuánto se revisa si el vehículo ya llegó. */
    private static final int REVISAR_S = 2;
    /** Lo más que se espera un servicio (por si OSRM no responde nunca). */
    private static final int MAXIMO_S = 3 * 60 * 60;
    /** Lo que "tarda" en subir el pasajero o en recoger el pedido. */
    private static final int ESPERA_EN_ORIGEN_S = 4;

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ActorBd actor;
    private final SimulacionViajes simulacion;
    private final ScheduledExecutorService planificador = Executors.newScheduledThreadPool(2);

    public SimulacionDemo(JdbcTemplate db, TransactionTemplate tx, ActorBd actor, SimulacionViajes simulacion) {
        this.db = db;
        this.tx = tx;
        this.actor = actor;
        this.simulacion = simulacion;
        log.warn("SIMULACIÓN DEMO ACTIVA: no usar en producción");
    }

    public void programar(Long servicioId) {
        planificador.schedule(() -> asignarSiNadieAcepto(servicioId), 10, TimeUnit.SECONDS);
    }

    private void asignarSiNadieAcepto(Long servicioId) {
        try {
            Map<String, Object> s = db.queryForList("SELECT tipo_vehiculo, estado, origen_lat, origen_lng FROM servicios WHERE id = ?",
                    servicioId).stream().findFirst().orElse(null);
            if (s == null || !"buscando".equals(s.get("estado"))) return;

            List<Map<String, Object>> demo = db.queryForList("""
                    SELECT c.usuario_id, v.id AS vehiculo_id
                    FROM conductores c
                    JOIN usuarios u ON u.id = c.usuario_id AND u.estado = 'activo'
                    JOIN vehiculos v ON v.conductor_id = c.usuario_id AND v.activo AND v.tipo_vehiculo = ?
                    WHERE c.estado = 'aprobado' AND u.email LIKE 'demo%@movify.co'
                      AND NOT EXISTS (SELECT 1 FROM servicios x WHERE x.conductor_id = c.usuario_id
                                       AND x.estado IN ('asignado', 'en_origen', 'en_curso'))
                    ORDER BY c.usuario_id LIMIT 1
                    """, s.get("tipo_vehiculo"));
            if (demo.isEmpty()) {
                log.info("Simulación: no hay conductor demo libre para {}; el servicio {} sigue buscando",
                        s.get("tipo_vehiculo"), servicioId);
                return;
            }
            Long conductorId = Datos.aLong(demo.get(0).get("usuario_id"));
            double oLat = Datos.num(s.get("origen_lat")), oLng = Datos.num(s.get("origen_lng"));

            Boolean aceptado = tx.execute(st -> {
                actor.declarar(conductorId, ActorBd.CONDUCTOR);
                // Punto de partida a unos 700 m: SimulacionViajes lo pega a la calle y traza la recogida
                moverConductor(conductorId, oLat + 0.0045, oLng + 0.0035);
                return db.update("""
                        UPDATE servicios SET estado = 'asignado', conductor_id = ?, vehiculo_id = ?
                        WHERE id = ? AND estado = 'buscando'
                        """, conductorId, demo.get(0).get("vehiculo_id"), servicioId) == 1;
            });
            if (!Boolean.TRUE.equals(aceptado)) return; // un conductor real ya lo aceptó o el cliente canceló
            vigilar(servicioId, conductorId, 0, REVISAR_S);
        } catch (Exception e) {
            log.error("Error en la simulación del servicio {}", servicioId, e);
        }
    }

    /** Revisa cada 2 s en qué va el vehículo simulado y avanza el estado del servicio cuando llega. */
    private void vigilar(Long servicioId, Long conductorId, int segundos, int retraso) {
        if (segundos > MAXIMO_S) return;
        planificador.schedule(() -> {
            try {
                String estado = db.queryForObject("SELECT estado FROM servicios WHERE id = ?", String.class, servicioId);
                int espera = REVISAR_S;
                switch (estado == null ? "" : estado) {
                    case "asignado" -> {
                        if (simulacion.llego(servicioId, SimulacionViajes.RECOGIDA)) {
                            cambiar(servicioId, conductorId, "asignado", "en_origen");
                            espera = ESPERA_EN_ORIGEN_S;
                        }
                    }
                    case "en_origen" -> cambiar(servicioId, conductorId, "en_origen", "en_curso");
                    case "en_curso" -> {
                        if (simulacion.llego(servicioId, SimulacionViajes.DESTINO)) {
                            finalizar(servicioId, conductorId);
                            return;
                        }
                    }
                    default -> {
                        return; // cancelado, finalizado…
                    }
                }
                vigilar(servicioId, conductorId, segundos + retraso, espera);
            } catch (Exception e) {
                log.error("Error siguiendo la simulación del servicio {}", servicioId, e);
            }
        }, retraso, TimeUnit.SECONDS);
    }

    private boolean cambiar(Long servicioId, Long conductorId, String desde, String hacia) {
        Boolean ok = tx.execute(st -> {
            actor.declarar(conductorId, ActorBd.CONDUCTOR);
            return db.update("UPDATE servicios SET estado = ? WHERE id = ? AND estado = ?", hacia, servicioId, desde) == 1;
        });
        return Boolean.TRUE.equals(ok);
    }

    private void finalizar(Long servicioId, Long conductorId) {
        tx.executeWithoutResult(st -> {
            actor.declarar(conductorId, ActorBd.CONDUCTOR);
            // Encomienda: el conductor demo "recibe" el PIN; domicilio con compra: se cobra lo estimado
            db.update("UPDATE servicio_encomienda SET entregado_a = destinatario_nombre WHERE servicio_id = ?", servicioId);
            db.update("""
                    UPDATE servicio_domicilio SET valor_compra_real = COALESCE(valor_productos_aprox, 0)
                    WHERE servicio_id = ? AND requiere_compra AND valor_compra_real IS NULL
                    """, servicioId);
            if (db.update("UPDATE servicios SET estado = 'finalizado' WHERE id = ? AND estado = 'en_curso'", servicioId) == 1) {
                log.info("Simulación: servicio {} finalizado", servicioId);
            }
        });
    }

    private void moverConductor(Long conductorId, double lat, double lng) {
        db.update("""
                INSERT INTO ubicacion_conductores (conductor_id, lat, lng, actualizado_en) VALUES (?, ?, ?, now())
                ON CONFLICT (conductor_id) DO UPDATE SET lat = EXCLUDED.lat, lng = EXCLUDED.lng, actualizado_en = now()
                """, conductorId, lat, lng);
    }

    @PreDestroy
    void apagar() {
        planificador.shutdownNow();
    }
}
