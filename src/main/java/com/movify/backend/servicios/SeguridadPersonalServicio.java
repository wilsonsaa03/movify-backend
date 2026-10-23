package com.movify.backend.servicios;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.movify.backend.comun.AccesoDenegadoException;
import com.movify.backend.comun.ActorBd;
import com.movify.backend.comun.Datos;
import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.comun.Roles;
import com.movify.backend.seguridad.SesionActual;

/** Botón SOS y PQRS (peticiones, quejas, reclamos y sugerencias) del cliente y del conductor. */
@Service
public class SeguridadPersonalServicio {

    private final JdbcTemplate db;
    private final SesionActual sesion;
    private final ActorBd actor;

    public SeguridadPersonalServicio(JdbcTemplate db, SesionActual sesion, ActorBd actor) {
        this.db = db;
        this.sesion = sesion;
        this.actor = actor;
    }

    // ───────────── SOS ─────────────

    @Transactional
    public Long activarSos(Long servicioId, Double lat, Double lng) {
        Long yo = sesion.usuarioId();
        if (servicioId != null) {
            Map<String, Object> s = db.queryForList("SELECT cliente_id, conductor_id FROM servicios WHERE id = ?", servicioId)
                    .stream().findFirst().orElseThrow(() -> new RecursoNoEncontradoException("Servicio no encontrado"));
            if (!Objects.equals(Datos.aLong(s.get("cliente_id")), yo) && !Objects.equals(Datos.aLong(s.get("conductor_id")), yo)) {
                throw new AccesoDenegadoException("Ese servicio no es tuyo");
            }
        }
        declararActor(yo);
        // TODO notificaciones: avisar por SMS a contactos_emergencia y al personal de soporte
        return db.queryForObject("""
                INSERT INTO alertas_sos (usuario_id, servicio_id, lat, lng) VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, yo, servicioId, lat, lng);
    }

    @Transactional
    public void falsaAlarma(Long id) {
        Long yo = sesion.usuarioId();
        declararActor(yo);
        int n = db.update("UPDATE alertas_sos SET estado = 'falsa_alarma', atendida_en = now() WHERE id = ? AND usuario_id = ? AND estado = 'abierta'",
                id, yo);
        if (n == 0) throw new RecursoNoEncontradoException("No hay una alerta abierta con ese número");
    }

    // ───────────── PQRS ─────────────

    /** @return id y fecha_limite_respuesta del ticket creado. */
    public Map<String, Object> crearPqrs(String tipo, String asunto, String descripcion, Long servicioId) {
        Long yo = sesion.usuarioId();
        if (servicioId != null) {
            Boolean mio = db.queryForObject("SELECT EXISTS (SELECT 1 FROM servicios WHERE id = ? AND ? IN (cliente_id, conductor_id))",
                    Boolean.class, servicioId, yo);
            if (!Boolean.TRUE.equals(mio)) throw new AccesoDenegadoException("Ese servicio no es tuyo");
        }
        String prioridad = "incidente_seguridad".equals(tipo) ? "urgente" : "media";
        return db.queryForMap("""
                INSERT INTO tickets_soporte (usuario_id, servicio_id, tipo, asunto, descripcion, prioridad)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id, fecha_limite_respuesta
                """, yo, servicioId, tipo, asunto.trim(), descripcion.trim(), prioridad);
    }

    public List<Map<String, Object>> misPqrs() {
        return db.queryForList("""
                SELECT t.id, t.tipo, t.asunto, t.descripcion, t.estado, e.nombre AS estado_nombre, e.color AS estado_color,
                       t.respuesta, t.servicio_id, t.fecha_limite_respuesta, t.creado_en, t.resuelto_en
                FROM tickets_soporte t JOIN estados e ON e.ambito = 'ticket' AND e.codigo = t.estado
                WHERE t.usuario_id = ? ORDER BY t.creado_en DESC
                """, sesion.usuarioId());
    }

    /**
     * Un reporte propio con el viaje al que se refiere y la otra persona del viaje
     * (si quien reporta es el conductor, es el pasajero; si es el cliente, el conductor).
     */
    public Map<String, Object> miPqrs(Long id) {
        return db.queryForList("""
                SELECT t.id, t.tipo, t.asunto, t.estado, e.nombre AS estado_nombre,
                       t.servicio_id, t.fecha_limite_respuesta, t.creado_en,
                       COALESCE(s.valor_final, s.valor_estimado) AS valor,
                       COALESCE(s.finalizado_en, s.solicitado_en) AS fecha_viaje,
                       o.nombres AS otro_nombres, o.apellidos AS otro_apellidos
                FROM tickets_soporte t
                JOIN estados e ON e.ambito = 'ticket' AND e.codigo = t.estado
                LEFT JOIN servicios s ON s.id = t.servicio_id
                LEFT JOIN usuarios o ON o.id = CASE WHEN s.cliente_id = t.usuario_id THEN s.conductor_id ELSE s.cliente_id END
                WHERE t.id = ? AND t.usuario_id = ?
                """, id, sesion.usuarioId())
                .stream().findFirst().orElseThrow(() -> new RecursoNoEncontradoException("Reporte no encontrado"));
    }

    /** Si la respuesta le sirvió la cierra; si no, la reabre (resuelto → en_proceso). */
    @Transactional
    public void responderPqrs(Long id, boolean cerrar) {
        Long yo = sesion.usuarioId();
        declararActor(yo);
        int n = db.update("UPDATE tickets_soporte SET estado = ? WHERE id = ? AND usuario_id = ? AND estado = 'resuelto'",
                cerrar ? "cerrado" : "en_proceso", id, yo);
        if (n == 0) throw new RecursoNoEncontradoException("Esa PQRS no tiene una respuesta pendiente de tu parte");
    }

    private void declararActor(Long yo) {
        actor.declarar(yo, sesion.usuario().tieneRol(Roles.CONDUCTOR) ? ActorBd.CONDUCTOR : ActorBd.CLIENTE);
    }
}
