package com.movify.backend.servicios;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import com.movify.backend.comun.AccesoDenegadoException;
import com.movify.backend.comun.ActorBd;
import com.movify.backend.comun.ArchivoServicio;
import com.movify.backend.comun.Datos;
import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.comun.ReglaNegocioException;
import com.movify.backend.seguridad.SesionActual;
import com.movify.backend.tiemporeal.TiempoReal;

/**
 * Fotos del servicio (tabla evidencias_servicio del DER): la de la entrega del domicilio o la encomienda,
 * y opcionalmente la de la recogida o la factura de la compra.
 *
 * La foto de entrega es obligatoria para finalizar un domicilio o una encomienda desde la app del conductor
 * (la app manda X-Plataforma). El panel web del conductor no tiene cámara: ahí sigue siendo opcional.
 */
@Service
public class EvidenciaServicio {

    public static final String ENTREGA = "entrega";
    private static final Set<String> TIPOS = Set.of("recogida", ENTREGA, "factura_compra");
    private static final int MAX_POR_TIPO = 3;

    private final JdbcTemplate db;
    private final SesionActual sesion;
    private final ArchivoServicio archivos;
    private final TiempoReal tiempoReal;

    public EvidenciaServicio(JdbcTemplate db, SesionActual sesion, ArchivoServicio archivos, TiempoReal tiempoReal) {
        this.db = db;
        this.sesion = sesion;
        this.archivos = archivos;
        this.tiempoReal = tiempoReal;
    }

    /** Solo el conductor asignado, con el servicio en la recogida o en camino. */
    @Transactional
    public Map<String, Object> guardar(Long servicioId, String tipo, MultipartFile foto) {
        String t = tipo == null ? ENTREGA : tipo.trim().toLowerCase();
        if (!TIPOS.contains(t)) throw new ReglaNegocioException(400, "Tipo de foto no válido");
        Map<String, Object> s = db.queryForList(
                "SELECT conductor_id, estado, tipo_servicio FROM servicios WHERE id = ? FOR UPDATE", servicioId)
                .stream().findFirst().orElseThrow(() -> new RecursoNoEncontradoException("Servicio no encontrado"));
        Long yo = sesion.usuarioId();
        if (!Objects.equals(Datos.aLong(s.get("conductor_id")), yo)) {
            throw new AccesoDenegadoException("Solo el conductor del servicio puede subir fotos");
        }
        if (!List.of("en_origen", "en_curso").contains(String.valueOf(s.get("estado")))) {
            throw new ReglaNegocioException(400, "Solo puedes subir fotos durante el servicio");
        }
        Integer ya = db.queryForObject("SELECT count(*) FROM evidencias_servicio WHERE servicio_id = ? AND tipo = ?",
                Integer.class, servicioId, t);
        if (ya != null && ya >= MAX_POR_TIPO) throw new ReglaNegocioException(400, "Ya subiste suficientes fotos de este tipo");
        ArchivoServicio.Guardado g = archivos.guardar(foto, "evidencia", false);
        Map<String, Object> fila = db.queryForMap("""
                INSERT INTO evidencias_servicio (servicio_id, tipo, ruta_archivo, mime_type, subido_por)
                VALUES (?, ?, ?, ?, ?) RETURNING id, tipo, creado_en
                """, servicioId, t, g.nombre(), g.mimeType(), yo);
        tiempoReal.publicar(servicioId, "evidencia", Map.of("evidencia", t, "id", fila.get("id")));
        return Map.of("id", fila.get("id"), "tipo", t, "creado_en", String.valueOf(fila.get("creado_en")),
                "message", ENTREGA.equals(t) ? "Foto de la entrega guardada" : "Foto guardada");
    }

    /** Las ven el cliente, el conductor asignado y el personal. */
    public List<Map<String, Object>> listar(Long servicioId) {
        exigirParticipante(servicioId);
        return db.queryForList("""
                SELECT id, tipo, creado_en FROM evidencias_servicio WHERE servicio_id = ? ORDER BY creado_en
                """, servicioId);
    }

    public ArchivoServicio.Archivo archivo(Long servicioId, Long evidenciaId) {
        exigirParticipante(servicioId);
        String nombre = db.queryForList("SELECT ruta_archivo FROM evidencias_servicio WHERE id = ? AND servicio_id = ?",
                String.class, evidenciaId, servicioId).stream().findFirst()
                .orElseThrow(() -> new RecursoNoEncontradoException("Foto no encontrada"));
        return archivos.obtener(nombre);
    }

    /** id de la última foto de entrega (o null): el cliente la ve en el seguimiento y en el resumen. */
    Long ultimaEntrega(Long servicioId) {
        return db.queryForList("""
                SELECT id FROM evidencias_servicio WHERE servicio_id = ? AND tipo = 'entrega' ORDER BY creado_en DESC LIMIT 1
                """, Long.class, servicioId).stream().findFirst().orElse(null);
    }

    /** Antes de finalizar un domicilio o una encomienda desde la app: tiene que haber foto de la entrega. */
    void exigirFotoEntrega(Long servicioId, String tipoServicio, String quien) {
        if (!"domicilio".equals(tipoServicio) && !"encomienda".equals(tipoServicio)) return;
        if (!ActorBd.CONDUCTOR.equals(quien) || !desdeLaApp()) return;
        if (ultimaEntrega(servicioId) == null) {
            throw new ReglaNegocioException(400, "Toma la foto de la entrega antes de finalizar.");
        }
    }

    /** La app (Android, iPhone o la app abierta en el navegador) siempre manda X-Plataforma; la web no. */
    private static boolean desdeLaApp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a)) return false;
        String p = a.getRequest().getHeader("X-Plataforma");
        return p != null && !p.isBlank();
    }

    private void exigirParticipante(Long servicioId) {
        Map<String, Object> s = db.queryForList("SELECT cliente_id, conductor_id FROM servicios WHERE id = ?", servicioId)
                .stream().findFirst().orElseThrow(() -> new RecursoNoEncontradoException("Servicio no encontrado"));
        Long yo = sesion.usuarioId();
        if (!Objects.equals(Datos.aLong(s.get("cliente_id")), yo) && !Objects.equals(Datos.aLong(s.get("conductor_id")), yo)
                && !sesion.esPersonal()) {
            throw new AccesoDenegadoException("No puedes ver las fotos de este servicio");
        }
    }
}
