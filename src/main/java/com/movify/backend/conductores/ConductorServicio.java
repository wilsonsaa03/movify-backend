package com.movify.backend.conductores;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.movify.backend.comun.AccesoDenegadoException;
import com.movify.backend.comun.ActorBd;
import com.movify.backend.comun.ArchivoServicio;
import com.movify.backend.comun.Datos;
import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.comun.ReglaNegocioException;
import com.movify.backend.conductores.ConductorDtos.PerfilConductorRequest;
import com.movify.backend.conductores.ConductorDtos.UbicacionRequest;
import com.movify.backend.conductores.ConductorDtos.VehiculoRequest;

/**
 * Perfil del conductor, su vehículo, sus documentos y su disponibilidad.
 *
 * Las reglas duras las hace cumplir la base (triggers del DER v2.3):
 *  - mayor de edad, licencia acorde (moto A1/A2, taxi C1–C3), placa con formato,
 *  - un solo vehículo activo, documentos con vencimiento, reemplazo del documento vigente,
 *  - solo se conecta si está aprobado, tiene vehículo activo y mandó su ubicación.
 */
@Service
public class ConductorServicio {

    private final JdbcTemplate db;
    private final ActorBd actor;
    private final ArchivoServicio archivos;

    public ConductorServicio(JdbcTemplate db, ActorBd actor, ArchivoServicio archivos) {
        this.db = db;
        this.actor = actor;
        this.archivos = archivos;
    }

    // ───────────── Perfil ─────────────

    public boolean tienePerfil(Long usuarioId) {
        return Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM conductores WHERE usuario_id = ?)", Boolean.class, usuarioId));
    }

    /**
     * Crea el perfil (estado "pendiente"; la BD le asigna el rol conductor) o lo corrige si estaba
     * rechazado. Como el rol nuevo no está en el JWT actual, la app debe pedir /api/auth/refresh.
     */
    @Transactional
    public Map<String, Object> guardarPerfil(Long usuarioId, PerfilConductorRequest req) {
        actor.declarar(usuarioId, ActorBd.CONDUCTOR);
        String numero = req.numeroIdentificacion().replaceAll("[.\\- ]", "").toUpperCase(Locale.ROOT);
        // Arreglo de PostgreSQL en texto: {A1,A2} (los valores ya vienen validados: solo A1..C3)
        String arreglo = "{" + String.join(",", req.categoriasLicencia().stream().distinct().toList()) + "}";

        if (!tienePerfil(usuarioId)) {
            db.update("""
                    INSERT INTO conductores (usuario_id, tipo_identificacion, numero_identificacion, fecha_nacimiento,
                                             categorias_licencia)
                    VALUES (?, ?, ?, ?, ?::varchar(2)[])
                    """, usuarioId, req.tipoIdentificacion(), numero, req.fechaNacimiento(), arreglo);
            return Map.of("message", "Perfil de conductor creado. Ahora registra tu vehículo.", "requiereNuevoToken", true);
        }

        String estado = estado(usuarioId);
        if ("aprobado".equals(estado)) {
            throw new ReglaNegocioException("Tu perfil ya fue aprobado. Para cambiar estos datos escribe a soporte.");
        }
        db.update("""
                UPDATE conductores SET tipo_identificacion = ?, numero_identificacion = ?, fecha_nacimiento = ?,
                                       categorias_licencia = ?::varchar(2)[]
                WHERE usuario_id = ?
                """, req.tipoIdentificacion(), numero, req.fechaNacimiento(), arreglo, usuarioId);
        return Map.of("message", "Datos actualizados", "requiereNuevoToken", false);
    }

    /** Después de corregir lo que el admin rechazó, el conductor vuelve a pedir revisión. */
    @Transactional
    public Map<String, Object> reenviarRevision(Long usuarioId) {
        String estado = estado(usuarioId);
        if (!"rechazado".equals(estado) && !"suspendido".equals(estado)) {
            throw new ReglaNegocioException("Tu perfil ya está " + nombreEstado(estado).toLowerCase(Locale.ROOT) + ".");
        }
        actor.declarar(usuarioId, ActorBd.CONDUCTOR);
        db.update("UPDATE conductores SET estado = 'pendiente', motivo_estado = NULL WHERE usuario_id = ?", usuarioId);
        return Map.of("message", "Enviamos tu perfil a revisión de nuevo", "estado", "pendiente");
    }

    /** Todo lo que necesita la pantalla del conductor (perfil, vehículo, documentos y qué falta). */
    public Map<String, Object> miPerfil(Long usuarioId) {
        List<Map<String, Object>> filas = db.queryForList("""
                SELECT c.usuario_id AS conductor_id, c.tipo_identificacion, c.numero_identificacion, c.fecha_nacimiento,
                       array_to_string(c.categorias_licencia, ',') AS categorias_licencia, c.estado, e.nombre AS estado_nombre,
                       e.color AS estado_color, c.motivo_estado, c.disponible, c.calificacion_promedio,
                       c.total_calificaciones, c.total_servicios, c.total_cancelaciones, c.verificado_en, c.creado_en
                FROM conductores c JOIN estados e ON e.ambito = 'conductor' AND e.codigo = c.estado
                WHERE c.usuario_id = ?
                """, usuarioId);
        if (filas.isEmpty()) throw new RecursoNoEncontradoException("Todavía no tienes perfil de conductor");
        Map<String, Object> r = new HashMap<>(filas.get(0));
        r.put("categorias_licencia", List.of(String.valueOf(r.get("categorias_licencia")).split(",")));
        r.put("vehiculo", vehiculoActivo(usuarioId));
        r.put("documentos", documentos(usuarioId));
        r.put("documentos_faltantes", faltantes(usuarioId));
        return r;
    }

    // ───────────── Vehículo ─────────────

    public Map<String, Object> vehiculoActivo(Long usuarioId) {
        return db.queryForList("""
                SELECT id, tipo_vehiculo, placa, marca, linea, modelo_anio, color, empresa_afiliadora, numero_interno
                FROM vehiculos WHERE conductor_id = ? AND activo
                """, usuarioId).stream().findFirst().orElse(null);
    }

    /** Registra el vehículo nuevo y desactiva el anterior (solo puede haber uno activo). */
    @Transactional
    public Map<String, Object> registrarVehiculo(Long usuarioId, VehiculoRequest v) {
        exigirPerfil(usuarioId);
        actor.declarar(usuarioId, ActorBd.CONDUCTOR);
        String placa = v.placa().replaceAll("[\\s\\-]", "").toUpperCase(Locale.ROOT);
        if ("taxi".equals(v.tipoVehiculo()) && (v.empresaAfiliadora() == null || v.empresaAfiliadora().isBlank())) {
            throw new ReglaNegocioException(400, "Para un taxi debes indicar la empresa afiliadora");
        }
        db.update("UPDATE vehiculos SET activo = false WHERE conductor_id = ? AND activo", usuarioId);
        Long id = db.queryForObject("""
                INSERT INTO vehiculos (conductor_id, tipo_vehiculo, placa, marca, linea, modelo_anio, color,
                                       empresa_afiliadora, numero_interno)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, usuarioId, v.tipoVehiculo(), placa, v.marca().trim(), Datos.vacioANull(v.linea()),
                v.modeloAnio(), v.color().trim(), Datos.vacioANull(v.empresaAfiliadora()),
                Datos.vacioANull(v.numeroInterno()));
        return Map.of("id", id, "message", "Vehículo registrado. Ahora sube sus documentos.");
    }

    // ───────────── Documentos ─────────────

    @Transactional
    public Map<String, Object> subirDocumento(Long usuarioId, String tipo, MultipartFile archivo, LocalDate vence) {
        exigirPerfil(usuarioId);
        Map<String, Object> td = db.queryForList(
                "SELECT codigo, nombre, aplica_a, requiere_vencimiento FROM tipos_documento WHERE codigo = ?", tipo)
                .stream().findFirst()
                .orElseThrow(() -> new ReglaNegocioException(400, "Tipo de documento no válido: " + tipo));
        Long vehiculoId = null;
        if ("vehiculo".equals(td.get("aplica_a"))) {
            Map<String, Object> v = vehiculoActivo(usuarioId);
            if (v == null) throw new ReglaNegocioException("Primero registra tu vehículo");
            vehiculoId = Datos.aLong(v.get("id"));
        }
        if (Boolean.TRUE.equals(td.get("requiere_vencimiento")) && vence == null) {
            throw new ReglaNegocioException(400, "Indica la fecha de vencimiento de " + td.get("nombre"));
        }
        ArchivoServicio.Guardado g = archivos.guardar(archivo, tipo.replaceAll("[^a-z_]", ""), true);
        actor.declarar(usuarioId, ActorBd.CONDUCTOR);
        try {
            Long id = db.queryForObject("""
                    INSERT INTO documentos_conductor (conductor_id, vehiculo_id, tipo_documento, ruta_archivo,
                                                      nombre_original, mime_type, tamano_bytes, fecha_vencimiento)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """, Long.class, usuarioId, vehiculoId, tipo, g.nombre(), g.nombreOriginal(), g.mimeType(),
                    g.tamanoBytes(), vence);
            return Map.of("id", id, "message", td.get("nombre") + " cargado. Queda por revisar.");
        } catch (RuntimeException e) {
            archivos.borrar(g.nombre()); // si la BD lo rechazó (p. ej. vencido), no queda basura en disco
            throw e;
        }
    }

    public List<Map<String, Object>> documentos(Long usuarioId) {
        return db.queryForList("""
                SELECT d.id, d.tipo_documento, td.nombre, td.aplica_a, d.vehiculo_id, d.estado,
                       e.nombre AS estado_nombre, e.color AS estado_color, d.observacion, d.fecha_vencimiento,
                       d.mime_type, d.ruta_archivo AS archivo, d.creado_en, d.revisado_en
                FROM documentos_conductor d
                JOIN tipos_documento td ON td.codigo = d.tipo_documento
                JOIN estados e ON e.ambito = 'documento' AND e.codigo = d.estado
                WHERE d.conductor_id = ? AND d.vigente
                ORDER BY td.aplica_a, td.nombre
                """, usuarioId);
    }

    /** Documentos obligatorios para su tipo de vehículo que todavía no ha subido (o le rechazaron). */
    public List<Map<String, Object>> faltantes(Long usuarioId) {
        Map<String, Object> v = vehiculoActivo(usuarioId);
        String tipo = v == null ? null : String.valueOf(v.get("tipo_vehiculo"));
        return db.queryForList("""
                SELECT td.codigo, td.nombre, td.aplica_a, td.requiere_vencimiento
                FROM tipos_documento td
                WHERE (CASE WHEN ? = 'moto' THEN td.obligatorio_moto
                            WHEN ? = 'taxi' THEN td.obligatorio_taxi
                            ELSE td.obligatorio_moto OR td.obligatorio_taxi END)
                  AND NOT EXISTS (SELECT 1 FROM documentos_conductor d
                                   WHERE d.conductor_id = ? AND d.tipo_documento = td.codigo AND d.vigente
                                     AND d.estado IN ('pendiente', 'aprobado')
                                     AND (td.aplica_a = 'conductor' OR d.vehiculo_id = ?))
                ORDER BY td.aplica_a, td.nombre
                """, tipo, tipo, usuarioId, v == null ? null : v.get("id"));
    }

    // ───────────── Disponibilidad y ubicación ─────────────

    /** Conectarse o desconectarse. Si llegan coordenadas se guardan primero (la BD exige ubicación reciente). */
    @Transactional
    public Map<String, Object> disponibilidad(Long usuarioId, boolean activo, Double lat, Double lng) {
        exigirPerfil(usuarioId);
        actor.declarar(usuarioId, ActorBd.CONDUCTOR);
        if (lat != null && lng != null) guardarUbicacion(usuarioId, lat, lng, null, null, null);
        if (activo && !"aprobado".equals(estado(usuarioId))) {
            throw new AccesoDenegadoException("Tu cuenta de conductor aún no está aprobada");
        }
        db.update("UPDATE conductores SET disponible = ? WHERE usuario_id = ?", activo, usuarioId);
        return Map.of("message", activo ? "Ahora estás en línea" : "Te desconectaste", "en_linea", activo,
                "disponible", activo);
    }

    /** Cada 5-10 s desde la app. Si tiene un servicio activo, también queda en el rastro del viaje. */
    @Transactional
    public void ubicacion(Long usuarioId, UbicacionRequest u) {
        exigirPerfil(usuarioId);
        guardarUbicacion(usuarioId, u.lat(), u.lng(), u.rumbo(), u.velocidad(), u.precision());
        db.update("""
                INSERT INTO seguimiento_ubicaciones (servicio_id, conductor_id, lat, lng, velocidad_kmh, rumbo_grados, precision_m)
                SELECT s.id, s.conductor_id, ?, ?, ?, ?, ?
                FROM servicios s
                WHERE s.conductor_id = ? AND s.estado IN ('asignado', 'en_origen', 'en_curso')
                """, u.lat(), u.lng(), u.velocidad(), u.rumbo(), u.precision(), usuarioId);
    }

    private void guardarUbicacion(Long id, double lat, double lng, Integer rumbo, Double vel, Double precision) {
        db.update("""
                INSERT INTO ubicacion_conductores (conductor_id, lat, lng, rumbo_grados, velocidad_kmh, precision_m, actualizado_en)
                VALUES (?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (conductor_id) DO UPDATE
                   SET lat = EXCLUDED.lat, lng = EXCLUDED.lng, rumbo_grados = EXCLUDED.rumbo_grados,
                       velocidad_kmh = EXCLUDED.velocidad_kmh, precision_m = EXCLUDED.precision_m, actualizado_en = now()
                """, id, lat, lng, rumbo, vel, precision);
    }

    // ───────────── Utilidades ─────────────

    public String estado(Long usuarioId) {
        return db.queryForList("SELECT estado FROM conductores WHERE usuario_id = ?", String.class, usuarioId)
                .stream().findFirst()
                .orElseThrow(() -> new AccesoDenegadoException("Tu cuenta no tiene perfil de conductor"));
    }

    private void exigirPerfil(Long usuarioId) {
        if (!tienePerfil(usuarioId)) {
            throw new ReglaNegocioException("Primero completa tus datos de conductor (identificación y licencia)");
        }
    }

    private String nombreEstado(String estado) {
        return db.queryForList("SELECT nombre FROM estados WHERE ambito = 'conductor' AND codigo = ?", String.class, estado)
                .stream().findFirst().orElse(estado);
    }
}
