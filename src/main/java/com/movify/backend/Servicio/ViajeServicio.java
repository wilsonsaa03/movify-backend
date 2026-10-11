package com.movify.backend.Servicio;

import static com.movify.backend.Modelo.Servicio.EstadoServicio.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.movify.backend.Comun.AccesoDenegadoException;
import com.movify.backend.Comun.RecursoNoEncontradoException;
import com.movify.backend.Comun.ReglaNegocioException;
import com.movify.backend.Comun.Roles;
import com.movify.backend.Dto.ServicioDtos.CalificacionRequest;
import com.movify.backend.Dto.ServicioDtos.SolicitudRequest;
import com.movify.backend.Modelo.NotificacionConductor.EstadoNotificacion;
import com.movify.backend.Modelo.Servicio.EstadoServicio;
import com.movify.backend.Seguridad.SesionActual;
import com.movify.backend.Seguridad.UsuarioAutenticado;
import com.movify.backend.Servicio.MaquinaEstados.Actor;

/**
 * Ciclo de vida de un servicio (transporte, domicilio o encomienda):
 * solicitud → notificación a conductores → aceptación → estados → cierre → calificación.
 */
@Service
public class ViajeServicio {

    private static final Logger log = LoggerFactory.getLogger(ViajeServicio.class);
    private static final double RADIO_BUSQUEDA_KM = 5.0;

    private final JdbcTemplate db;
    private final SesionActual sesion;
    private final TarifaServicio tarifas;
    private final ObjectProvider<SimulacionDemo> simulacion;

    public ViajeServicio(JdbcTemplate db, SesionActual sesion, TarifaServicio tarifas,
            ObjectProvider<SimulacionDemo> simulacion) {
        this.db = db;
        this.sesion = sesion;
        this.tarifas = tarifas;
        this.simulacion = simulacion;
    }

    // ───────────────────────── Solicitud ─────────────────────────

    @Transactional
    public Map<String, Object> crearSolicitud(SolicitudRequest s) {
        Long usuarioId = sesion.usuarioId();
        boolean rapida = Boolean.TRUE.equals(s.entregaRapida());
        TarifaServicio.Cotizacion cot = tarifas.cotizar(s.origenLat(), s.origenLng(), s.destinoLat(),
                s.destinoLng(), rapida);

        String tipo = s.tipo() == null || s.tipo().isBlank() ? "TRANSPORTE" : s.tipo().trim().toUpperCase();

        Long servicioId = db.queryForObject("""
                INSERT INTO servicios (
                    usuario_id, origen_lat, origen_lng, destino_lat, destino_lng, origen, destino,
                    distancia_km, tarifa, estado, tipo, descripcion, fecha_solicitud,
                    nombre_remitente, telefono_remitente, nombre_destinatario, telefono_destinatario,
                    tipo_paquete, peso_kg, metodo_pago
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class,
                usuarioId, s.origenLat(), s.origenLng(), s.destinoLat(), s.destinoLng(),
                vacioANull(s.origen()), vacioANull(s.destino()),
                cot.distanciaKm(), cot.tarifa(), PENDIENTE.name(), tipo, nvl(s.descripcion()),
                nvl(s.nombreRemitente()), nvl(s.telefonoRemitente()),
                nvl(s.nombreDestinatario()), nvl(s.telefonoDestinatario()),
                nvl(s.tipoPaquete()), nvl(s.pesoKg()),
                s.metodoPago() == null || s.metodoPago().isBlank() ? "efectivo" : s.metodoPago());

        List<Long> conductores = conductoresCercanos(s.origenLat(), s.origenLng());
        for (Long conductorId : conductores) {
            db.update("""
                    INSERT INTO notificaciones_conductor (servicio_id, conductor_id, estado_notificacion, fecha_creacion)
                    VALUES (?, ?, ?, NOW())
                    """, servicioId, conductorId, EstadoNotificacion.ENVIADA.name());
        }
        log.info("Servicio {} creado; notificados {} conductores", servicioId, conductores.size());

        // Solo existe si app.demo.simulacion=true (nunca en producción)
        simulacion.ifAvailable(sim -> sim.programar(servicioId));

        Map<String, Object> resp = new HashMap<>();
        resp.put("id", servicioId);
        resp.put("tarifa", cot.tarifa());
        resp.put("distancia_km", cot.distanciaKm());
        resp.put("conductores_notificados", conductores.size());
        resp.put("message", "Solicitud creada con éxito.");
        return resp;
    }

    /**
     * Conductores aprobados y en línea a menos de 5 km (fórmula esférica; el VPS no tiene PostGIS).
     * Si no hay ninguno cerca, se avisa a todos los aprobados en línea de la ciudad.
     */
    private List<Long> conductoresCercanos(double lat, double lng) {
        List<Long> cerca = db.queryForList("""
                SELECT id FROM conductores
                WHERE en_linea = true AND estado = 'aprobado'
                  AND latitud IS NOT NULL AND longitud IS NOT NULL
                  AND latitud BETWEEN ? - 0.05 AND ? + 0.05
                  AND longitud BETWEEN ? - 0.05 AND ? + 0.05
                  AND 6371 * acos(GREATEST(-1.0, LEAST(1.0,
                        cos(radians(?)) * cos(radians(latitud)) * cos(radians(longitud) - radians(?))
                      + sin(radians(?)) * sin(radians(latitud))))) <= ?
                """, Long.class, lat, lat, lng, lng, lat, lng, lat, RADIO_BUSQUEDA_KM);
        if (!cerca.isEmpty()) return cerca;
        return db.queryForList("SELECT id FROM conductores WHERE en_linea = true AND estado = 'aprobado'",
                Long.class);
    }

    // ───────────────────────── Consulta ─────────────────────────

    public Map<String, Object> obtener(Long servicioId) {
        Map<String, Object> servicio = new HashMap<>(fila(servicioId, """
                SELECT s.id, s.estado, s.conductor_id, s.usuario_id, s.tipo, s.descripcion,
                       u.nombre AS usuario_nombre,
                       s.origen_lat, s.origen_lng, s.destino_lat, s.destino_lng,
                       s.origen, s.destino, s.distancia_km, s.tarifa
                FROM servicios s JOIN usuarios u ON s.usuario_id = u.id
                WHERE s.id = ?
                """));
        exigirPuedeVer(servicioId, servicio);

        servicio.put("conductor_lat", null);
        servicio.put("conductor_lng", null);
        servicio.put("conductor_nombre", null);
        servicio.put("conductor_foto", null);
        servicio.put("conductor_telefono", null);
        Object conductorId = servicio.get("conductor_id");
        if (conductorId != null) {
            db.queryForList("""
                    SELECT c.latitud AS conductor_lat, c.longitud AS conductor_lng,
                           uc.nombre AS conductor_nombre, uc.foto AS conductor_foto,
                           uc.telefono AS conductor_telefono, c.placa AS conductor_placa,
                           c.modelo AS conductor_modelo
                    FROM conductores c JOIN usuarios uc ON c.usuario_id = uc.id
                    WHERE c.id = ?
                    """, ((Number) conductorId).longValue()).stream().findFirst().ifPresent(servicio::putAll);
        }
        servicio.remove("usuario_id");
        return servicio;
    }

    private void exigirPuedeVer(Long servicioId, Map<String, Object> servicio) {
        UsuarioAutenticado yo = sesion.usuario();
        if (sesion.esPersonal()) return;
        if (Objects.equals(toLong(servicio.get("usuario_id")), yo.id())) return;
        if (Roles.CONDUCTOR.equals(yo.rol())) {
            Long miConductor = sesion.conductorId();
            if (Objects.equals(toLong(servicio.get("conductor_id")), miConductor)) return;
            Integer notificado = db.queryForObject(
                    "SELECT COUNT(*) FROM notificaciones_conductor WHERE servicio_id = ? AND conductor_id = ?",
                    Integer.class, servicioId, miConductor);
            if (notificado != null && notificado > 0) return;
        }
        throw new AccesoDenegadoException("No puedes ver este servicio");
    }

    // ───────────────────────── Cambios de estado ─────────────────────────

    @Transactional
    public Map<String, Object> cambiarEstado(Long servicioId, String estadoTexto) {
        EstadoServicio nuevo;
        try {
            nuevo = EstadoServicio.valueOf(estadoTexto.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ReglaNegocioException(400, "Estado de servicio no válido: " + estadoTexto);
        }

        Map<String, Object> s = fila(servicioId,
                "SELECT estado, usuario_id, conductor_id FROM servicios WHERE id = ? FOR UPDATE");
        EstadoServicio actual = EstadoServicio.valueOf(String.valueOf(s.get("estado")));
        Long usuarioDueno = toLong(s.get("usuario_id"));
        Long conductorAsignado = toLong(s.get("conductor_id"));
        UsuarioAutenticado yo = sesion.usuario();

        // RECHAZADO no cambia el servicio: el conductor solo descarta su notificación
        if (nuevo == RECHAZADO) {
            Long miConductor = sesion.conductorId();
            db.update("""
                    UPDATE notificaciones_conductor SET estado_notificacion = ?, fecha_respuesta = NOW()
                    WHERE servicio_id = ? AND conductor_id = ? AND estado_notificacion = ?
                    """, EstadoNotificacion.RECHAZADA.name(), servicioId, miConductor,
                    EstadoNotificacion.ENVIADA.name());
            return Map.of("message", "Servicio rechazado.");
        }

        Actor actor;
        Long miConductor = null;
        if (Roles.CONDUCTOR.equals(yo.rol())) {
            miConductor = sesion.conductorId();
            boolean aceptaPendiente = actual == PENDIENTE && nuevo == ACEPTADO;
            if (!aceptaPendiente && !Objects.equals(conductorAsignado, miConductor)) {
                throw new AccesoDenegadoException("Este servicio no está asignado a ti");
            }
            if (aceptaPendiente) exigirConductorHabilitado(miConductor);
            actor = Actor.CONDUCTOR;
        } else if (Objects.equals(usuarioDueno, yo.id())) {
            actor = Actor.CLIENTE;
        } else if (sesion.esPersonal()) {
            actor = Actor.PERSONAL;
        } else {
            throw new AccesoDenegadoException("No puedes modificar este servicio");
        }

        if (!MaquinaEstados.permitido(actor, actual, nuevo)) {
            throw new ReglaNegocioException("No se puede pasar de " + actual + " a " + nuevo);
        }

        // UPDATE condicionado al estado leído: si otro llegó primero, no se pisa
        int filas = switch (nuevo) {
            case ACEPTADO -> db.update(
                    "UPDATE servicios SET estado = ?, conductor_id = ?, fecha_inicio = NOW() WHERE id = ? AND estado = ?",
                    ACEPTADO.name(), miConductor, servicioId, actual.name());
            case FINALIZADO -> db.update(
                    "UPDATE servicios SET estado = ?, fecha_fin = NOW() WHERE id = ? AND estado = ?",
                    FINALIZADO.name(), servicioId, actual.name());
            case CANCELADO -> db.update(
                    "UPDATE servicios SET estado = ?, fecha_fin = NOW(), cancelado_por = ? WHERE id = ? AND estado = ?",
                    CANCELADO.name(), actor.name().toLowerCase(), servicioId, actual.name());
            default -> db.update("UPDATE servicios SET estado = ? WHERE id = ? AND estado = ?",
                    nuevo.name(), servicioId, actual.name());
        };
        if (filas == 0) {
            throw new ReglaNegocioException("El servicio ya no está disponible o cambió de estado");
        }

        switch (nuevo) {
            case ACEPTADO -> {
                db.update("""
                        UPDATE notificaciones_conductor SET estado_notificacion = ?, fecha_respuesta = NOW()
                        WHERE servicio_id = ? AND conductor_id <> ? AND estado_notificacion = ?
                        """, EstadoNotificacion.RECHAZADA_POR_OTRO.name(), servicioId, miConductor,
                        EstadoNotificacion.ENVIADA.name());
                db.update("""
                        UPDATE notificaciones_conductor SET estado_notificacion = ?, fecha_respuesta = NOW()
                        WHERE servicio_id = ? AND conductor_id = ?
                        """, EstadoNotificacion.ACEPTADA.name(), servicioId, miConductor);
            }
            case FINALIZADO -> sumarEstadisticas(servicioId, conductorAsignado);
            case CANCELADO -> db.update("""
                    UPDATE notificaciones_conductor SET estado_notificacion = ?, fecha_respuesta = NOW()
                    WHERE servicio_id = ? AND estado_notificacion = ?
                    """, EstadoNotificacion.RECHAZADA_POR_OTRO.name(), servicioId,
                    EstadoNotificacion.ENVIADA.name());
            default -> {
            }
        }
        return Map.of("message", "Estado actualizado a " + nuevo, "servicioId", servicioId, "estado", nuevo.name());
    }

    /** Se llama una sola vez por servicio porque el UPDATE de FINALIZADO es condicional. */
    void sumarEstadisticas(Long servicioId, Long conductorId) {
        if (conductorId == null) return;
        db.update("""
                UPDATE conductores
                SET viajes_hoy = COALESCE(viajes_hoy, 0) + 1,
                    viajes_total = COALESCE(viajes_total, 0) + 1,
                    ganancias_hoy = COALESCE(ganancias_hoy, 0) + COALESCE((SELECT tarifa FROM servicios WHERE id = ?), 0)
                WHERE id = ?
                """, servicioId, conductorId);
    }

    private void exigirConductorHabilitado(Long conductorId) {
        String estado = db.queryForObject("SELECT estado FROM conductores WHERE id = ?", String.class, conductorId);
        if (!"aprobado".equalsIgnoreCase(estado)) {
            throw new AccesoDenegadoException("Tu cuenta de conductor aún no está aprobada");
        }
    }

    // ───────────────────────── Calificación ─────────────────────────

    @Transactional
    public void calificar(CalificacionRequest req) {
        Long yo = sesion.usuarioId();
        Map<String, Object> s = fila(req.servicioId(), "SELECT usuario_id, estado FROM servicios WHERE id = ?");
        if (!Objects.equals(toLong(s.get("usuario_id")), yo)) {
            throw new AccesoDenegadoException("Solo puedes calificar tus propios servicios");
        }
        if (!FINALIZADO.name().equals(s.get("estado"))) {
            throw new ReglaNegocioException("Solo se califican servicios finalizados");
        }
        Integer ya = db.queryForObject(
                "SELECT COUNT(*) FROM calificaciones WHERE servicio_id = ? AND usuario_id = ?",
                Integer.class, req.servicioId(), yo);
        if (ya != null && ya > 0) {
            throw new ReglaNegocioException("Ya calificaste este servicio");
        }
        db.update("""
                INSERT INTO calificaciones (servicio_id, usuario_id, calificacion, comentario, fecha)
                VALUES (?, ?, ?, ?, NOW())
                """, req.servicioId(), yo, req.puntos(), nvl(req.comentario()));
    }

    // ───────────────────────── Utilidades ─────────────────────────

    private Map<String, Object> fila(Long id, String sql) {
        List<Map<String, Object>> filas = db.queryForList(sql, id);
        if (filas.isEmpty()) throw new RecursoNoEncontradoException("Servicio no encontrado");
        return filas.get(0);
    }

    static Long toLong(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    private static String nvl(String s) {
        return s == null ? "" : s.trim();
    }

    private static String vacioANull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
