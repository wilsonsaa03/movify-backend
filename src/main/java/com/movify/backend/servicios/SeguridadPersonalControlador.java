package com.movify.backend.servicios;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.movify.backend.comun.AccesoDenegadoException;
import com.movify.backend.comun.ActorBd;
import com.movify.backend.comun.Datos;
import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.comun.Roles;
import com.movify.backend.seguridad.SesionActual;
import com.movify.backend.servicios.ServicioDtos.SosRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Botón SOS y PQRS (peticiones, quejas, reclamos y sugerencias) del cliente y del conductor. */
@RestController
@RequestMapping("/api")
public class SeguridadPersonalControlador {

    private final JdbcTemplate db;
    private final SesionActual sesion;
    private final ActorBd actor;

    public SeguridadPersonalControlador(JdbcTemplate db, SesionActual sesion, ActorBd actor) {
        this.db = db;
        this.sesion = sesion;
        this.actor = actor;
    }

    // ───────────── SOS ─────────────

    @PostMapping("/sos")
    @PreAuthorize("hasAuthority('seguridad_personal.activar_sos')")
    @Transactional
    public Map<String, Object> activarSos(@Valid @RequestBody SosRequest req) {
        Long yo = sesion.usuarioId();
        if (req.servicioId() != null) {
            Map<String, Object> s = db.queryForList("SELECT cliente_id, conductor_id FROM servicios WHERE id = ?", req.servicioId())
                    .stream().findFirst().orElseThrow(() -> new RecursoNoEncontradoException("Servicio no encontrado"));
            if (!Objects.equals(Datos.aLong(s.get("cliente_id")), yo) && !Objects.equals(Datos.aLong(s.get("conductor_id")), yo)) {
                throw new AccesoDenegadoException("Ese servicio no es tuyo");
            }
        }
        actor.declarar(yo, sesion.usuario().tieneRol(Roles.CONDUCTOR) ? ActorBd.CONDUCTOR : ActorBd.CLIENTE);
        Long id = db.queryForObject("""
                INSERT INTO alertas_sos (usuario_id, servicio_id, lat, lng) VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, yo, req.servicioId(), req.lat(), req.lng());
        // TODO notificaciones: avisar por SMS a contactos_emergencia y al personal de soporte
        return Map.of("id", id, "message", "Alerta enviada. El equipo de soporte ya fue avisado. Si estás en peligro llama al 123.");
    }

    @PostMapping("/sos/{id}/falsa-alarma")
    @Transactional
    public Map<String, Object> falsaAlarma(@PathVariable Long id) {
        Long yo = sesion.usuarioId();
        actor.declarar(yo, sesion.usuario().tieneRol(Roles.CONDUCTOR) ? ActorBd.CONDUCTOR : ActorBd.CLIENTE);
        int n = db.update("UPDATE alertas_sos SET estado = 'falsa_alarma', atendida_en = now() WHERE id = ? AND usuario_id = ? AND estado = 'abierta'",
                id, yo);
        if (n == 0) throw new RecursoNoEncontradoException("No hay una alerta abierta con ese número");
        return Map.of("message", "Marcamos la alerta como falsa alarma");
    }

    // ───────────── PQRS ─────────────

    public record PqrsRequest(
            @NotBlank @Pattern(regexp = "peticion|queja|reclamo|sugerencia|objeto_olvidado|cobro_indebido|incidente_seguridad") String tipo,
            @NotBlank @Size(max = 120) String asunto,
            @NotBlank @Size(max = 2000) String descripcion,
            Long servicioId) {
    }

    @PostMapping("/pqrs")
    @PreAuthorize("hasAuthority('pqrs.crear')")
    public Map<String, Object> crearPqrs(@Valid @RequestBody PqrsRequest req) {
        Long yo = sesion.usuarioId();
        if (req.servicioId() != null) {
            Boolean mio = db.queryForObject("SELECT EXISTS (SELECT 1 FROM servicios WHERE id = ? AND ? IN (cliente_id, conductor_id))",
                    Boolean.class, req.servicioId(), yo);
            if (!Boolean.TRUE.equals(mio)) throw new AccesoDenegadoException("Ese servicio no es tuyo");
        }
        String prioridad = "incidente_seguridad".equals(req.tipo()) ? "urgente" : "media";
        Map<String, Object> r = db.queryForMap("""
                INSERT INTO tickets_soporte (usuario_id, servicio_id, tipo, asunto, descripcion, prioridad)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id, fecha_limite_respuesta
                """, yo, req.servicioId(), req.tipo(), req.asunto().trim(), req.descripcion().trim(), prioridad);
        return Map.of("id", r.get("id"), "fecha_limite_respuesta", r.get("fecha_limite_respuesta"),
                "message", "Recibimos tu solicitud. Te responderemos a más tardar el " + r.get("fecha_limite_respuesta") + ".");
    }

    @GetMapping("/pqrs")
    @PreAuthorize("hasAuthority('pqrs.ver_propias')")
    public List<Map<String, Object>> misPqrs() {
        return db.queryForList("""
                SELECT t.id, t.tipo, t.asunto, t.descripcion, t.estado, e.nombre AS estado_nombre, e.color AS estado_color,
                       t.respuesta, t.servicio_id, t.fecha_limite_respuesta, t.creado_en, t.resuelto_en
                FROM tickets_soporte t JOIN estados e ON e.ambito = 'ticket' AND e.codigo = t.estado
                WHERE t.usuario_id = ? ORDER BY t.creado_en DESC
                """, sesion.usuarioId());
    }

    /** Si la respuesta le sirvió la cierra; si no, la reabre (resuelto → en_proceso). */
    @PostMapping("/pqrs/{id}/{accion:cerrar|reabrir}")
    @Transactional
    public Map<String, Object> responderPqrs(@PathVariable Long id, @PathVariable String accion) {
        Long yo = sesion.usuarioId();
        actor.declarar(yo, sesion.usuario().tieneRol(Roles.CONDUCTOR) ? ActorBd.CONDUCTOR : ActorBd.CLIENTE);
        int n = db.update("UPDATE tickets_soporte SET estado = ? WHERE id = ? AND usuario_id = ? AND estado = 'resuelto'",
                "cerrar".equals(accion) ? "cerrado" : "en_proceso", id, yo);
        if (n == 0) throw new RecursoNoEncontradoException("Esa PQRS no tiene una respuesta pendiente de tu parte");
        return Map.of("message", "cerrar".equals(accion) ? "PQRS cerrada" : "Reabrimos tu PQRS");
    }
}
