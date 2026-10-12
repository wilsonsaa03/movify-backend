package com.movify.backend.Servicio;

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

import com.movify.backend.Modelo.Servicio.EstadoServicio;

import jakarta.annotation.PreDestroy;

/**
 * Simulación para demos y sustentación. SOLO se crea con app.demo.simulacion=true.
 *
 * Si en 10 s ningún conductor acepta, asigna un conductor de demostración aprobado
 * (placa que empiece por "DEMO") y recorre el viaje en línea recta hasta finalizarlo.
 * Nunca toca conductores reales ni crea usuarios.
 */
@Component
@ConditionalOnProperty(name = "app.demo.simulacion", havingValue = "true")
public class SimulacionDemo {

    private static final Logger log = LoggerFactory.getLogger(SimulacionDemo.class);
    private static final int PASOS = 10;

    private final JdbcTemplate db;
    private final ScheduledExecutorService planificador = Executors.newScheduledThreadPool(2);

    public SimulacionDemo(JdbcTemplate db) {
        this.db = db;
        log.warn("SIMULACIÓN DEMO ACTIVA: no usar en producción");
    }

    public void programar(Long servicioId) {
        planificador.schedule(() -> asignarSiNadieAcepto(servicioId), 10, TimeUnit.SECONDS);
    }

    private void asignarSiNadieAcepto(Long servicioId) {
        try {
            List<Long> demo = db.queryForList(
                    "SELECT id FROM conductores WHERE estado = 'aprobado' AND placa LIKE 'DEMO%' ORDER BY id LIMIT 1",
                    Long.class);
            if (demo.isEmpty()) {
                log.info("Simulación: no hay conductor DEMO aprobado; el servicio {} sigue pendiente", servicioId);
                return;
            }
            Long conductorId = demo.get(0);
            int filas = db.update(
                    "UPDATE servicios SET estado = ?, conductor_id = ?, fecha_inicio = NOW() WHERE id = ? AND estado = ?",
                    EstadoServicio.ACEPTADO.name(), conductorId, servicioId, EstadoServicio.PENDIENTE.name());
            if (filas == 0) return; // un conductor real ya lo aceptó o el cliente canceló

            Map<String, Object> s = db.queryForMap(
                    "SELECT origen_lat, origen_lng, destino_lat, destino_lng FROM servicios WHERE id = ?", servicioId);
            double oLat = num(s.get("origen_lat")), oLng = num(s.get("origen_lng"));
            double dLat = num(s.get("destino_lat")), dLng = num(s.get("destino_lng"));
            db.update("UPDATE conductores SET latitud = ?, longitud = ? WHERE id = ?", oLat + 0.004, oLng + 0.004,
                    conductorId);

            recorrer(servicioId, conductorId, oLat + 0.004, oLng + 0.004, oLat, oLng,
                    EstadoServicio.ACEPTADO, EstadoServicio.EN_CAMINO_AL_USUARIO, 0,
                    () -> recorrer(servicioId, conductorId, oLat, oLng, dLat, dLng,
                            EstadoServicio.EN_CAMINO_AL_USUARIO, EstadoServicio.EN_VIAJE, 0,
                            () -> finalizar(servicioId, conductorId)));
        } catch (Exception e) {
            log.error("Error en la simulación del servicio {}", servicioId, e);
        }
    }

    /** Mueve el conductor un paso cada 3 s; si alguien cambió el estado por fuera, se detiene. */
    private void recorrer(Long servicioId, Long conductorId, double aLat, double aLng, double bLat, double bLng,
            EstadoServicio desde, EstadoServicio hacia, int paso, Runnable alTerminar) {
        planificador.schedule(() -> {
            try {
                if (paso == 0) {
                    int filas = db.update("UPDATE servicios SET estado = ? WHERE id = ? AND estado = ?",
                            hacia.name(), servicioId, desde.name());
                    if (filas == 0) return;
                }
                double t = (paso + 1) / (double) PASOS;
                db.update("UPDATE conductores SET latitud = ?, longitud = ? WHERE id = ?",
                        aLat + (bLat - aLat) * t, aLng + (bLng - aLng) * t, conductorId);
                if (paso + 1 < PASOS) {
                    recorrer(servicioId, conductorId, aLat, aLng, bLat, bLng, hacia, hacia, paso + 1, alTerminar);
                } else {
                    alTerminar.run();
                }
            } catch (Exception e) {
                log.error("Error moviendo la simulación del servicio {}", servicioId, e);
            }
        }, 3, TimeUnit.SECONDS);
    }

    private void finalizar(Long servicioId, Long conductorId) {
        int filas = db.update("UPDATE servicios SET estado = ?, fecha_fin = NOW() WHERE id = ? AND estado = ?",
                EstadoServicio.FINALIZADO.name(), servicioId, EstadoServicio.EN_VIAJE.name());
        if (filas == 1) {
            db.update("""
                    UPDATE conductores SET viajes_hoy = COALESCE(viajes_hoy, 0) + 1,
                        viajes_total = COALESCE(viajes_total, 0) + 1,
                        ganancias_hoy = COALESCE(ganancias_hoy, 0) + COALESCE((SELECT tarifa FROM servicios WHERE id = ?), 0)
                    WHERE id = ?
                    """, servicioId, conductorId);
            log.info("Simulación: servicio {} finalizado", servicioId);
        }
    }

    private static double num(Object o) {
        return ((Number) o).doubleValue();
    }

    @PreDestroy
    void apagar() {
        planificador.shutdownNow();
    }
}
