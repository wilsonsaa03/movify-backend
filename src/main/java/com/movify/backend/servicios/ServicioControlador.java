package com.movify.backend.servicios;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.movify.backend.comun.AccesoDenegadoException;
import com.movify.backend.comun.ArchivoServicio;
import com.movify.backend.comun.Datos;
import com.movify.backend.seguridad.SesionActual;
import com.movify.backend.servicios.ServicioDtos.CambioEstadoRequest;
import com.movify.backend.servicios.ServicioDtos.MensajeRequest;
import com.movify.backend.servicios.ServicioDtos.SolicitudRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * API de servicios para la app móvil (y los historiales que ya usaba la web).
 *
 *   POST /api/servicios                 pedir transporte, domicilio o encomienda
 *   GET  /api/servicios/activo          el servicio en curso (cliente o conductor)
 *   GET  /api/servicios/disponibles     solicitudes cercanas para el conductor
 *   GET  /api/servicios/{id}            detalle (estado, conductor, ubicación)
 *   POST /api/servicios/{id}/estado     aceptar, llegué, iniciar, finalizar, cancelar
 *   POST /api/servicios/{id}/calificar  1 a 5 estrellas
 *   GET  /api/servicios/{id}/historial  línea de tiempo del servicio
 *   GET/POST /api/servicios/{id}/nota-voz  nota de voz del punto de referencia
 *   GET/POST /api/servicios/{id}/mensajes  chat cliente ↔ conductor
 */
@RestController
@RequestMapping("/api/servicios")
public class ServicioControlador {

    private final JdbcTemplate db;
    private final SesionActual sesion;
    private final ViajeServicio viajes;
    private final EstadisticasServicio estadisticas;
    private final ArchivoServicio archivos;

    public ServicioControlador(JdbcTemplate db, SesionActual sesion, ViajeServicio viajes, EstadisticasServicio estadisticas,
            ArchivoServicio archivos) {
        this.db = db;
        this.sesion = sesion;
        this.viajes = viajes;
        this.estadisticas = estadisticas;
        this.archivos = archivos;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('servicios.solicitar')")
    public Map<String, Object> solicitar(@Valid @RequestBody SolicitudRequest req) {
        return viajes.crearSolicitud(req);
    }

    @GetMapping("/activo")
    public ResponseEntity<Map<String, Object>> activo() {
        Map<String, Object> s = viajes.activo();
        return s == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(s);
    }

    @GetMapping("/disponibles")
    @PreAuthorize("hasAuthority('servicios.ver_disponibles')")
    public List<Map<String, Object>> disponibles() {
        return viajes.solicitudesPara(sesion.conductorId());
    }

    @GetMapping("/mios")
    public List<Map<String, Object>> mios() {
        return estadisticas.historialCliente(sesion.usuarioId(), false);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detalle(@PathVariable Long id) {
        return viajes.obtener(id);
    }

    @PostMapping("/{id}/estado")
    public Map<String, Object> estado(@PathVariable Long id, @Valid @RequestBody CambioEstadoRequest req) {
        return viajes.cambiarEstado(id, req);
    }

    public record CalificarRequest(@Min(1) @Max(5) int puntuacion, String comentario) {
    }

    @PostMapping("/{id}/calificar")
    @PreAuthorize("hasAuthority('calificaciones.crear')")
    public Map<String, Object> calificar(@PathVariable Long id, @Valid @RequestBody CalificarRequest req) {
        viajes.calificar(id, req.puntuacion(), req.comentario());
        return Map.of("message", "¡Gracias por calificar!");
    }

    @GetMapping("/{id}/historial")
    public List<Map<String, Object>> historial(@PathVariable Long id) {
        viajes.obtener(id); // valida que pueda verlo
        return db.queryForList("""
                SELECT creado_en, estado_anterior, estado_nuevo, estado_nombre, color, actor_rol, motivo
                FROM v_historial_servicios WHERE servicio_id = ? ORDER BY creado_en
                """, id);
    }

    // ───────────── Nota de voz del punto de referencia ─────────────

    /** multipart: audio (webm/ogg/m4a, máx. 2 MB) y segundos (1 a 60). */
    @PostMapping("/{id}/nota-voz")
    public Map<String, Object> subirNotaVoz(@PathVariable Long id, @RequestParam("audio") MultipartFile audio,
            @RequestParam(defaultValue = "1") int segundos) {
        return viajes.guardarNotaVoz(id, archivos, audio, segundos);
    }

    @GetMapping("/{id}/nota-voz")
    public ResponseEntity<Resource> notaVoz(@PathVariable Long id) {
        ArchivoServicio.Archivo a = viajes.notaVoz(id, archivos);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(a.contentType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(a.recurso());
    }

    // ───────────── Chat ─────────────

    @GetMapping("/{id}/mensajes")
    @Transactional
    public List<Map<String, Object>> mensajes(@PathVariable Long id) {
        exigirParticipante(id);
        Long yo = sesion.usuarioId();
        db.update("UPDATE mensajes_servicio SET leido_en = now() WHERE servicio_id = ? AND remitente_id <> ? AND leido_en IS NULL",
                id, yo);
        return db.queryForList("""
                SELECT id, remitente_id, (remitente_id = ?) AS mio, contenido, leido_en, creado_en
                FROM mensajes_servicio WHERE servicio_id = ? ORDER BY creado_en
                """, yo, id);
    }

    @PostMapping("/{id}/mensajes")
    @PreAuthorize("hasAuthority('chat.usar')")
    public Map<String, Object> enviarMensaje(@PathVariable Long id, @Valid @RequestBody MensajeRequest req) {
        exigirParticipante(id);
        // El trigger fn_mensajes_validar exige que el servicio esté activo
        Long msg = db.queryForObject(
                "INSERT INTO mensajes_servicio (servicio_id, remitente_id, contenido) VALUES (?, ?, ?) RETURNING id",
                Long.class, id, sesion.usuarioId(), req.contenido().trim());
        return Map.of("id", msg);
    }

    private void exigirParticipante(Long servicioId) {
        Map<String, Object> s = db.queryForList("SELECT cliente_id, conductor_id FROM servicios WHERE id = ?", servicioId)
                .stream().findFirst().orElseThrow(() -> new AccesoDenegadoException("No puedes ver este chat"));
        Long yo = sesion.usuarioId();
        if (!Objects.equals(Datos.aLong(s.get("cliente_id")), yo) && !Objects.equals(Datos.aLong(s.get("conductor_id")), yo)
                && !sesion.esPersonal()) {
            throw new AccesoDenegadoException("No puedes ver este chat");
        }
    }

    // ───────────── Historiales que ya usaba la web ─────────────

    @GetMapping("/usuario/{usuarioId}")
    public List<Map<String, Object>> serviciosUsuario(@PathVariable Long usuarioId) {
        sesion.exigirUsuario(usuarioId);
        return estadisticas.historialCliente(usuarioId, false);
    }

    @GetMapping("/usuario/{usuarioId}/mes-actual")
    public List<Map<String, Object>> serviciosUsuarioMes(@PathVariable Long usuarioId) {
        sesion.exigirUsuario(usuarioId);
        return estadisticas.historialCliente(usuarioId, true);
    }

    @GetMapping("/conductor/{conductorId}")
    public List<Map<String, Object>> serviciosConductor(@PathVariable Long conductorId,
            @RequestParam(defaultValue = "50") int limite) {
        sesion.exigirConductor(conductorId);
        return db.queryForList("SELECT * FROM v_servicios_api WHERE conductor_id = ? ORDER BY fecha_solicitud DESC LIMIT ?",
                conductorId, Math.min(Math.max(limite, 1), 200));
    }
}
