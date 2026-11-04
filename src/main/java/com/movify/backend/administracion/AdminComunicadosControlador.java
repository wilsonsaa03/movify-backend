package com.movify.backend.administracion;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.movify.backend.comun.ReglaNegocioException;
import com.movify.backend.notificaciones.NotificacionServicio;
import com.movify.backend.seguridad.SesionActual;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Comunicados masivos (V19): "Hoy hay paro en la vía Alterna", "Nuevas tarifas desde el lunes".
 * Llegan a la bandeja de notificaciones de la app y como push. Solo el admin (comunicados.enviar);
 * cada envío queda en la tabla comunicados y en auditoria_admin.
 */
@RestController
@RequestMapping("/api/admin/comunicados")
public class AdminComunicadosControlador {

    private static final Set<String> AUDIENCIAS = Set.of("todos", "clientes", "conductores");

    public record ComunicadoRequest(
            @NotBlank(message = "Escribe el título") @Size(min = 3, max = 100, message = "El título va de 3 a 100 letras") String titulo,
            @NotBlank(message = "Escribe el mensaje") @Size(min = 5, max = 300, message = "El mensaje va de 5 a 300 letras") String mensaje,
            @NotBlank @Pattern(regexp = "todos|clientes|conductores", message = "Elige a quién va") String audiencia) {
    }

    private final JdbcTemplate db;
    private final NotificacionServicio notificaciones;
    private final AdminServicio admin;
    private final SesionActual sesion;

    public AdminComunicadosControlador(JdbcTemplate db, NotificacionServicio notificaciones, AdminServicio admin,
            SesionActual sesion) {
        this.db = db;
        this.notificaciones = notificaciones;
        this.admin = admin;
        this.sesion = sesion;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('comunicados.enviar')")
    public List<Map<String, Object>> enviados() {
        return db.queryForList("""
                SELECT c.id, c.titulo, c.mensaje, c.audiencia, c.destinatarios, c.creado_en,
                       trim(u.nombres || ' ' || COALESCE(u.apellidos, '')) AS enviado_por
                FROM comunicados c LEFT JOIN usuarios u ON u.id = c.creado_por
                ORDER BY c.creado_en DESC LIMIT 50
                """);
    }

    /** A cuántas personas llegaría (se muestra antes de confirmar). */
    @GetMapping("/alcance")
    @PreAuthorize("hasAuthority('comunicados.enviar')")
    public Map<String, Object> alcance(@RequestParam(defaultValue = "todos") String audiencia) {
        if (!AUDIENCIAS.contains(audiencia)) throw new ReglaNegocioException(400, "Audiencia no válida");
        return Map.of("audiencia", audiencia, "personas", notificaciones.alcance(audiencia));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('comunicados.enviar')")
    @Transactional
    public Map<String, Object> enviar(@Valid @RequestBody ComunicadoRequest req) {
        String titulo = req.titulo().trim();
        String mensaje = req.mensaje().trim();
        // Un mismo comunicado dos veces en 2 minutos casi siempre es un doble clic
        Integer repetido = db.queryForObject("""
                SELECT count(*) FROM comunicados
                WHERE titulo = ? AND mensaje = ? AND audiencia = ? AND creado_en > now() - INTERVAL '2 minutes'
                """, Integer.class, titulo, mensaje, req.audiencia());
        if (repetido != null && repetido > 0) {
            throw new ReglaNegocioException(409, "Ese comunicado ya se envió hace un momento.");
        }
        Long id = db.queryForObject("""
                INSERT INTO comunicados (titulo, mensaje, audiencia, creado_por) VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, titulo, mensaje, req.audiencia(), sesion.usuarioId());
        int personas = notificaciones.difundir(req.audiencia(), titulo, mensaje, id);
        db.update("UPDATE comunicados SET destinatarios = ? WHERE id = ?", personas, id);
        admin.auditar("comunicado_enviado", "comunicados", id, Map.of("audiencia", req.audiencia(), "personas", personas));
        return Map.of("id", id, "personas", personas,
                "message", personas == 1 ? "Comunicado enviado a 1 persona." : "Comunicado enviado a " + personas + " personas.");
    }
}
