package com.movify.backend.conductores;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.movify.backend.comun.ActorBd;
import com.movify.backend.comun.ArchivoServicio;
import com.movify.backend.comun.Datos;
import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.comun.ReglaNegocioException;
import com.movify.backend.notificaciones.NotificacionServicio;

/**
 * Selfie del conductor antes de conectarse (como el "Real-Time ID Check" de Uber): confirma que quien maneja
 * es el dueño de la cuenta. Vale unas horas (horas_vigencia_selfie); después hay que tomarse otra.
 *
 * La comparación con la foto de perfil la hace una persona de soporte en el panel (Seguridad → Selfies).
 * Si no coincide, la rechaza: el conductor queda desconectado y tiene que tomarse otra.
 * Comparación automática de rostros: NO está hecha (necesita un servicio externo de reconocimiento facial).
 */
@Service
public class SelfieServicio {

    /** Código HTTP con el que la app sabe que debe abrir la cámara (428 Precondition Required). */
    public static final int SELFIE_REQUERIDA = 428;

    private final JdbcTemplate db;
    private final ArchivoServicio archivos;
    private final ActorBd actor;
    private final NotificacionServicio avisos;

    public SelfieServicio(JdbcTemplate db, ArchivoServicio archivos, ActorBd actor, NotificacionServicio avisos) {
        this.db = db;
        this.archivos = archivos;
        this.actor = actor;
        this.avisos = avisos;
    }

    /** GET /api/conductor/selfie: ¿necesita tomarse una para conectarse? */
    public Map<String, Object> estado(Long conductorId) {
        Map<String, Object> r = new LinkedHashMap<>();
        Map<String, Object> ultima = db.queryForList("""
                SELECT id, estado, motivo_rechazo, creado_en,
                       creado_en + make_interval(hours => fn_config_num('horas_vigencia_selfie', 12)::int) AS vence_en,
                       creado_en + make_interval(hours => fn_config_num('horas_vigencia_selfie', 12)::int) > now() AS vigente
                FROM selfies_conductor WHERE conductor_id = ? ORDER BY creado_en DESC LIMIT 1
                """, conductorId).stream().findFirst().orElse(null);
        boolean valida = ultima != null && Boolean.TRUE.equals(ultima.get("vigente")) && !"rechazada".equals(ultima.get("estado"));
        r.put("requerida", !valida);
        r.put("horas_vigencia", db.queryForObject("SELECT fn_config_num('horas_vigencia_selfie', 12)::int", Integer.class));
        if (ultima != null) {
            r.put("estado", ultima.get("estado"));
            r.put("vence_en", String.valueOf(ultima.get("vence_en")));
            r.put("motivo_rechazo", ultima.get("motivo_rechazo"));
        }
        return r;
    }

    /** Para conectarse hace falta una selfie vigente y no rechazada. */
    public void exigirVigente(Long conductorId) {
        if (Boolean.TRUE.equals(estado(conductorId).get("requerida"))) {
            throw new ReglaNegocioException(SELFIE_REQUERIDA,
                    "Tómate una selfie para conectarte. Así confirmamos que eres tú quien maneja.");
        }
    }

    /** POST /api/conductor/selfie (multipart foto). */
    @Transactional
    public Map<String, Object> guardar(Long conductorId, MultipartFile foto) {
        Integer hoy = db.queryForObject("""
                SELECT count(*) FROM selfies_conductor WHERE conductor_id = ? AND creado_en > now() - INTERVAL '1 hour'
                """, Integer.class, conductorId);
        if (hoy != null && hoy >= 6) throw new ReglaNegocioException(429, "Ya enviaste varias selfies. Espera un rato.");
        ArchivoServicio.Guardado g = archivos.guardar(foto, "selfie", false);
        db.update("INSERT INTO selfies_conductor (conductor_id, ruta_archivo, mime_type) VALUES (?, ?, ?)",
                conductorId, g.nombre(), g.mimeType());
        Map<String, Object> r = new LinkedHashMap<>(estado(conductorId));
        r.put("message", "¡Listo! Ya puedes conectarte.");
        return r;
    }

    // ───────────── Panel (soporte) ─────────────

    /** Selfies por revisar (o todas las recientes), con la foto de perfil para comparar. */
    public List<Map<String, Object>> listar(String estado) {
        String e = estado == null || estado.isBlank() ? "pendiente" : estado.trim();
        if (!List.of("pendiente", "aprobada", "rechazada", "todas").contains(e)) {
            throw new ReglaNegocioException(400, "Estado no válido");
        }
        return db.queryForList("""
                SELECT sc.id, sc.conductor_id, sc.estado, sc.motivo_rechazo, sc.creado_en, sc.revisado_en,
                       trim(u.nombres || ' ' || COALESCE(u.apellidos, '')) AS conductor_nombre, u.foto_url,
                       c.estado AS conductor_estado, c.disponible
                FROM selfies_conductor sc
                JOIN conductores c ON c.usuario_id = sc.conductor_id
                JOIN usuarios u ON u.id = sc.conductor_id
                WHERE (? = 'todas' OR sc.estado = ?)
                ORDER BY sc.creado_en DESC LIMIT 100
                """, e, e);
    }

    public ArchivoServicio.Archivo archivo(Long selfieId) {
        String nombre = db.queryForList("SELECT ruta_archivo FROM selfies_conductor WHERE id = ?", String.class, selfieId)
                .stream().findFirst().orElseThrow(() -> new RecursoNoEncontradoException("Selfie no encontrada"));
        return archivos.obtener(nombre);
    }

    /** Aprobar o rechazar. Rechazada: el conductor queda desconectado (si no tiene un servicio en curso). */
    @Transactional
    public Map<String, Object> revisar(Long selfieId, boolean aprobar, String motivo, Long adminId, String actorRol) {
        Map<String, Object> s = db.queryForList("SELECT conductor_id, estado FROM selfies_conductor WHERE id = ? FOR UPDATE",
                selfieId).stream().findFirst().orElseThrow(() -> new RecursoNoEncontradoException("Selfie no encontrada"));
        String m = Datos.vacioANull(motivo);
        if (!aprobar && m == null) throw new ReglaNegocioException(400, "Escribe por qué la rechazas");
        Long conductor = Datos.aLong(s.get("conductor_id"));
        db.update("""
                UPDATE selfies_conductor SET estado = ?, motivo_rechazo = ?, revisado_por = ?, revisado_en = now() WHERE id = ?
                """, aprobar ? "aprobada" : "rechazada", aprobar ? null : Datos.recortar(m, 200), adminId, selfieId);
        db.update("INSERT INTO auditoria_admin (admin_id, accion, entidad, entidad_id, detalle) VALUES (?, ?, 'selfie', ?, ?::jsonb)",
                adminId, aprobar ? "selfie_aprobada" : "selfie_rechazada", selfieId,
                "{\"conductor_id\":" + conductor + (aprobar ? "" : ",\"motivo\":" + jsonTexto(m)) + "}");
        if (!aprobar) {
            actor.declarar(adminId, actorRol);
            db.update("""
                    UPDATE conductores SET disponible = false
                    WHERE usuario_id = ? AND NOT EXISTS (SELECT 1 FROM servicios WHERE conductor_id = ?
                                                           AND estado IN ('asignado', 'en_origen', 'en_curso'))
                    """, conductor, conductor);
            avisos.avisar(conductor, null, NotificacionServicio.SOPORTE, "selfie_rechazada", "Tómate otra selfie",
                    "Tu selfie no se pudo verificar: " + Datos.recortar(m, 100) + ". Tómate otra para conectarte.");
        }
        return Map.of("message", aprobar ? "Selfie aprobada" : "Selfie rechazada: el conductor debe tomarse otra");
    }

    private static String jsonTexto(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }
}
