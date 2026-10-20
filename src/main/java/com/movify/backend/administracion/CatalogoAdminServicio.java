package com.movify.backend.administracion;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.movify.backend.administracion.CatalogoAdminControlador.MotivoCancelacionRequest;
import com.movify.backend.administracion.CatalogoAdminControlador.TipoDocumentoRequest;
import com.movify.backend.comun.Datos;
import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.comun.ReglaNegocioException;

/**
 * CRUD de los datos básicos (tarea 1.5): motivos de cancelación y tipos de documento.
 * Lo que el código o la app usan por su id/código se puede editar pero no eliminar;
 * lo que ya está en uso lo protege la llave foránea (se responde 409 con un mensaje claro).
 */
@Service
public class CatalogoAdminServicio {

    /** Motivos por defecto al cancelar (ViajeServicio.MOTIVO_POR_DEFECTO) y el del sistema. */
    private static final Set<Integer> MOTIVOS_DEL_SISTEMA = Set.of(1, 11, 20, 30);

    /** Documentos que piden los pasos del registro del conductor en la app (V3 y V5). */
    private static final Set<String> DOCUMENTOS_DEL_SISTEMA = Set.of(
            "cedula", "cedula_reverso", "selfie", "licencia_conduccion", "licencia_reverso", "tarjeta_control",
            "antecedentes", "soat", "tecnomecanica", "tarjeta_propiedad", "tarjeta_operacion", "foto_frente",
            "foto_atras", "foto_lado", "foto_placa", "foto_interior", "casco_certificado");

    private static final String COLUMNAS_DOCUMENTO = """
            codigo, nombre, aplica_a, requiere_vencimiento, obligatorio_moto, obligatorio_taxi,
            paso, orden, solo_imagen, exigir_desde_anios, ayuda""";

    private final JdbcTemplate db;
    private final AdminServicio admin;

    public CatalogoAdminServicio(JdbcTemplate db, AdminServicio admin) {
        this.db = db;
        this.admin = admin;
    }

    // ───────────── Motivos de cancelación ─────────────

    public List<Map<String, Object>> motivos() {
        return db.queryForList("SELECT id, actor, descripcion, activo FROM motivos_cancelacion ORDER BY id");
    }

    public Map<String, Object> motivo(int id) {
        List<Map<String, Object>> f = db.queryForList(
                "SELECT id, actor, descripcion, activo FROM motivos_cancelacion WHERE id = ?", id);
        if (f.isEmpty()) throw new RecursoNoEncontradoException("No existe el motivo de cancelación " + id);
        return f.get(0);
    }

    @Transactional
    public Map<String, Object> crearMotivo(MotivoCancelacionRequest req) {
        Integer id = db.queryForObject("""
                INSERT INTO motivos_cancelacion (id, actor, descripcion, activo)
                SELECT COALESCE(max(id), 0) + 1, ?, ?, ? FROM motivos_cancelacion
                RETURNING id
                """, Integer.class, req.actor(), req.descripcion().trim(), !Boolean.FALSE.equals(req.activo()));
        admin.auditar("catalogo_crear", "motivos_cancelacion", id.longValue(), Map.of("descripcion", req.descripcion()));
        return motivo(id);
    }

    @Transactional
    public Map<String, Object> editarMotivo(int id, MotivoCancelacionRequest req) {
        int n = db.update("""
                UPDATE motivos_cancelacion SET actor = ?, descripcion = ?, activo = COALESCE(?, activo) WHERE id = ?
                """, req.actor(), req.descripcion().trim(), req.activo(), id);
        if (n == 0) throw new RecursoNoEncontradoException("No existe el motivo de cancelación " + id);
        admin.auditar("catalogo_editar", "motivos_cancelacion", (long) id, Map.of("descripcion", req.descripcion()));
        return motivo(id);
    }

    @Transactional
    public Map<String, Object> eliminarMotivo(int id) {
        motivo(id);
        if (MOTIVOS_DEL_SISTEMA.contains(id)) {
            throw new ReglaNegocioException("Este motivo lo usa el sistema al cancelar: puedes editarlo, pero no eliminarlo.");
        }
        try {
            db.update("DELETE FROM motivos_cancelacion WHERE id = ?", id);
        } catch (DataIntegrityViolationException e) {
            throw new ReglaNegocioException(
                    "Hay servicios cancelados con este motivo: no se puede eliminar. Desactívalo (activo: false).");
        }
        admin.auditar("catalogo_eliminar", "motivos_cancelacion", (long) id, Map.of());
        return Map.of("message", "Motivo eliminado");
    }

    // ───────────── Tipos de documento ─────────────

    public List<Map<String, Object>> tiposDocumento() {
        return db.queryForList("SELECT " + COLUMNAS_DOCUMENTO + " FROM tipos_documento ORDER BY aplica_a, paso, orden, nombre");
    }

    public Map<String, Object> tipoDocumento(String codigo) {
        List<Map<String, Object>> f = db.queryForList(
                "SELECT " + COLUMNAS_DOCUMENTO + " FROM tipos_documento WHERE codigo = ?", codigo);
        if (f.isEmpty()) throw new RecursoNoEncontradoException("No existe el tipo de documento " + codigo);
        return f.get(0);
    }

    @Transactional
    public Map<String, Object> crearTipoDocumento(TipoDocumentoRequest req) {
        String codigo = Datos.vacioANull(req.codigo());
        if (codigo == null) throw new ReglaNegocioException(400, "Escribe el código del tipo de documento");
        db.update("INSERT INTO tipos_documento (" + COLUMNAS_DOCUMENTO + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                codigo, req.nombre().trim(), req.aplicaA(), si(req.requiereVencimiento()), si(req.obligatorioMoto()),
                si(req.obligatorioTaxi()), paso(req), orden(req), si(req.soloImagen()), req.exigirDesdeAnios(),
                Datos.vacioANull(req.ayuda()));
        admin.auditar("catalogo_crear", "tipos_documento", 0L, detalle(codigo, req));
        return tipoDocumento(codigo);
    }

    /** El código no se cambia (es la llave que usan los documentos cargados y la app). */
    @Transactional
    public Map<String, Object> editarTipoDocumento(String codigo, TipoDocumentoRequest req) {
        int n = db.update("""
                UPDATE tipos_documento SET nombre = ?, aplica_a = ?, requiere_vencimiento = ?, obligatorio_moto = ?,
                       obligatorio_taxi = ?, paso = ?, orden = ?, solo_imagen = ?, exigir_desde_anios = ?, ayuda = ?
                 WHERE codigo = ?
                """, req.nombre().trim(), req.aplicaA(), si(req.requiereVencimiento()), si(req.obligatorioMoto()),
                si(req.obligatorioTaxi()), paso(req), orden(req), si(req.soloImagen()), req.exigirDesdeAnios(),
                Datos.vacioANull(req.ayuda()), codigo);
        if (n == 0) throw new RecursoNoEncontradoException("No existe el tipo de documento " + codigo);
        admin.auditar("catalogo_editar", "tipos_documento", 0L, detalle(codigo, req));
        return tipoDocumento(codigo);
    }

    @Transactional
    public Map<String, Object> eliminarTipoDocumento(String codigo) {
        tipoDocumento(codigo);
        if (DOCUMENTOS_DEL_SISTEMA.contains(codigo)) {
            throw new ReglaNegocioException("La app pide este documento en el registro del conductor: puedes editarlo, "
                    + "pero no eliminarlo. Si ya no se exige, quítale lo obligatorio.");
        }
        try {
            db.update("DELETE FROM tipos_documento WHERE codigo = ?", codigo);
        } catch (DataIntegrityViolationException e) {
            throw new ReglaNegocioException("Ya hay documentos cargados de este tipo: no se puede eliminar. "
                    + "Si ya no se exige, quítale lo obligatorio.");
        }
        admin.auditar("catalogo_eliminar", "tipos_documento", 0L, Map.of("codigo", codigo));
        return Map.of("message", "Tipo de documento eliminado");
    }

    private static boolean si(Boolean b) {
        return Boolean.TRUE.equals(b);
    }

    private static String paso(TipoDocumentoRequest req) {
        return req.paso() == null || req.paso().isBlank() ? "documentos" : req.paso();
    }

    private static short orden(TipoDocumentoRequest req) {
        return req.orden() == null ? 50 : req.orden();
    }

    private static Map<String, Object> detalle(String codigo, TipoDocumentoRequest req) {
        return Map.of("codigo", codigo, "nombre", req.nombre());
    }
}
