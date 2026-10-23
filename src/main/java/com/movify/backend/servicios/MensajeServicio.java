package com.movify.backend.servicios;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.movify.backend.comun.AccesoDenegadoException;
import com.movify.backend.comun.Datos;
import com.movify.backend.seguridad.SesionActual;

/** Chat cliente ↔ conductor de un servicio. Solo los dos participantes (y el personal) lo ven. */
@Service
public class MensajeServicio {

    private final JdbcTemplate db;
    private final SesionActual sesion;

    public MensajeServicio(JdbcTemplate db, SesionActual sesion) {
        this.db = db;
        this.sesion = sesion;
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

    /** El trigger fn_mensajes_validar exige que el servicio esté activo. */
    public Long enviar(Long servicioId, String contenido) {
        exigirParticipante(servicioId);
        return db.queryForObject(
                "INSERT INTO mensajes_servicio (servicio_id, remitente_id, contenido) VALUES (?, ?, ?) RETURNING id",
                Long.class, servicioId, sesion.usuarioId(), contenido.trim());
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
}
