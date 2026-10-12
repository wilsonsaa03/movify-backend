package com.movify.backend.servicios;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.movify.backend.comun.ActorBd;
import com.movify.backend.comun.Datos;

import jakarta.annotation.PreDestroy;

/**
 * Simulación para demos y sustentación. SOLO se crea con app.demo.simulacion=true.
 *
 * Si en 10 s ningún conductor acepta, un conductor de demostración aprobado (cuyo correo
 * empieza por "demo" y termina en @movify.co, ver scripts/datos_demo.sql) acepta el servicio
 * y recorre el viaje en línea recta hasta finalizarlo. Pasa por los mismos triggers que un
 * conductor real: nunca toca conductores reales ni crea usuarios.
 */
@Component
@ConditionalOnProperty(name = "app.demo.simulacion", havingValue = "true")
public class SimulacionDemo {

    private static final Logger log = LoggerFactory.getLogger(SimulacionDemo.class);
    private static final int PASOS = 10;

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ActorBd actor;
    private final ScheduledExecutorService planificador = Executors.newScheduledThreadPool(2);

    @Value("${app.demo.segundos-por-paso:3}")
    private int segundosPorPaso;

    public SimulacionDemo(JdbcTemplate db, TransactionTemplate tx, ActorBd actor) {
        this.db = db;
        this.tx = tx;
        this.actor = actor;
        log.warn("SIMULACIÓN DEMO ACTIVA: no usar en producción");
    }

    public void programar(Long servicioId) {
        planificador.schedule(() -> asignarSiNadieAcepto(servicioId), 10, TimeUnit.SECONDS);
    }

    private void asignarSiNadieAcepto(Long servicioId) {
        try {
            Map<String, Object> s = db.queryForList("""
                    SELECT tipo_vehiculo, estado, origen_lat, origen_lng, destino_lat, destino_lng
                    FROM servicios WHERE id = ?
                    """, servicioId).stream().findFirst().orElse(null);
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
            double dLat = Datos.num(s.get("destino_lat")), dLng = Datos.num(s.get("destino_lng"));

            Boolean aceptado = tx.execute(st -> {
                actor.declarar(conductorId, ActorBd.CONDUCTOR);
                moverConductor(conductorId, oLat + 0.004, oLng + 0.004);
                return db.update("""
                        UPDATE servicios SET estado = 'asignado', conductor_id = ?, vehiculo_id = ?
                        WHERE id = ? AND estado = 'buscando'
                        """, conductorId, demo.get(0).get("vehiculo_id"), servicioId) == 1;
            });
            if (!Boolean.TRUE.equals(aceptado)) return; // un conductor real ya lo aceptó o el cliente canceló

            recorrer(servicioId, conductorId, oLat + 0.004, oLng + 0.004, oLat, oLng, 0,
                    () -> cambiar(servicioId, conductorId, "asignado", "en_origen")
                            && cambiar(servicioId, conductorId, "en_origen", "en_curso"),
                    () -> recorrer(servicioId, conductorId, oLat, oLng, dLat, dLng, 0, () -> true,
                            () -> finalizar(servicioId, conductorId)));
        } catch (Exception e) {
            log.error("Error en la simulación del servicio {}", servicioId, e);
        }
    }

    /** Mueve el conductor un paso cada N segundos; si alguien cambió el estado por fuera, se detiene. */
    private void recorrer(Long servicioId, Long conductorId, double aLat, double aLng, double bLat, double bLng,
            int paso, java.util.function.BooleanSupplier alLlegar, Runnable despues) {
        planificador.schedule(() -> {
            try {
                String estado = db.queryForObject("SELECT estado FROM servicios WHERE id = ?", String.class, servicioId);
                if (!"asignado".equals(estado) && !"en_curso".equals(estado)) return;
                double t = (paso + 1) / (double) PASOS;
                tx.executeWithoutResult(st -> moverConductor(conductorId, aLat + (bLat - aLat) * t, aLng + (bLng - aLng) * t));
                if (paso + 1 < PASOS) {
                    recorrer(servicioId, conductorId, aLat, aLng, bLat, bLng, paso + 1, alLlegar, despues);
                } else if (alLlegar.getAsBoolean()) {
                    despues.run();
                }
            } catch (Exception e) {
                log.error("Error moviendo la simulación del servicio {}", servicioId, e);
            }
        }, segundosPorPaso, TimeUnit.SECONDS);
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
