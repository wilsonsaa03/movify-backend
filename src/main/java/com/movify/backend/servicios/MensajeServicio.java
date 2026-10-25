package com.movify.backend.servicios;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.movify.backend.comun.AccesoDenegadoException;
import com.movify.backend.comun.Datos;
import com.movify.backend.notificaciones.NotificacionServicio;
import com.movify.backend.seguridad.SesionActual;

/** Chat cliente ↔ conductor de un servicio. Solo los dos participantes (y el personal) lo ven. */
@Service
public class MensajeServicio {

    private final JdbcTemplate db;
    private final SesionActual sesion;
    private final NotificacionServicio avisos;

    public MensajeServicio(JdbcTemplate db, SesionActual sesion, NotificacionServicio avisos) {
        this.db = db;
        this.sesion = sesion;
        this.avisos = avisos;
    }

    /** Devuelve los mensajes y marca como leídos los que le llegaron. */
    @Transactional
    public List<Map<String, Object>> mensajes(Long servicioId) {
        exigirParticipante(servicioId);
        Long yo = sesion.usuarioId();
        db.update("UPDATE mensajes_servicio SET leido_en = now() WHERE servicio_id = ? AND remitente_id <> ? AND leido_en IS NULL",
                servicioId, yo);
        return db.queryForList("""
                SELECT id, remitente_id, (remitente_id = ?) AS mio, contenido, leido_en, creado_en
                FROM mensajes_servicio WHERE servicio_id = ? ORDER BY creado_en
                """, yo, servicioId);
    }

    /** El trigger fn_mensajes_validar exige que el servicio esté activo. Al otro le llega un push con el texto. */
    @Transactional
    public Long enviar(Long servicioId, String contenido) {
        Map<String, Object> s = exigirParticipante(servicioId);
        Long yo = sesion.usuarioId();
        String texto = contenido.trim();
        Long id = db.queryForObject(
                "INSERT INTO mensajes_servicio (servicio_id, remitente_id, contenido) VALUES (?, ?, ?) RETURNING id",
                Long.class, servicioId, yo, texto);
        boolean soyCliente = Objects.equals(Datos.aLong(s.get("cliente_id")), yo);
        Long otro = Datos.aLong(s.get(soyCliente ? "conductor_id" : "cliente_id"));
        String nombre = db.queryForObject("SELECT split_part(trim(nombres), ' ', 1) FROM usuarios WHERE id = ?", String.class, yo);
        avisos.avisar(otro, servicioId, NotificacionServicio.MENSAJE, "mensaje",
                (soyCliente ? "Mensaje de " : "Tu conductor ") + nombre, Datos.recortar(texto, 120));
        return id;
    }

    private Map<String, Object> exigirParticipante(Long servicioId) {
        Map<String, Object> s = db.queryForList("SELECT cliente_id, conductor_id FROM servicios WHERE id = ?", servicioId)
                .stream().findFirst().orElseThrow(() -> new AccesoDenegadoException("No puedes ver este chat"));
        Long yo = sesion.usuarioId();
        if (!Objects.equals(Datos.aLong(s.get("cliente_id")), yo) && !Objects.equals(Datos.aLong(s.get("conductor_id")), yo)
                && !sesion.esPersonal()) {
            throw new AccesoDenegadoException("No puedes ver este chat");
        }
        return s;
    }
}
