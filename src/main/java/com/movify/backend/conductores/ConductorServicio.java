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
import com.movify.backend.conductores.ConductorDtos.IdentidadRequest;
import com.movify.backend.conductores.ConductorDtos.LicenciaRequest;
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
     * Crea el perfil (estado "pendiente", todavía sin rol conductor: la BD se lo asigna cuando el admin
     * lo aprueba) o lo corrige si estaba rechazado.
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
            return Map.of("message", "Perfil de conductor creado. Ahora registra tu vehículo.", "requiereNuevoToken", false);
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

    /**
     * Paso 1 "Confirma quién eres": crea el perfil (sin licencia todavía) o corrige los datos.
     * El rol conductor NO se asigna aquí: llega cuando el admin aprueba (V6).
     */
    @Transactional
    public Map<String, Object> guardarIdentidad(Long usuarioId, IdentidadRequest req) {
        String numero = req.numeroIdentificacion().replaceAll("[.\\- ]", "").toUpperCase(Locale.ROOT);
        actor.declarar(usuarioId, ActorBd.CONDUCTOR);
        if (!tienePerfil(usuarioId)) {
            db.update("""
                    INSERT INTO conductores (usuario_id, tipo_identificacion, numero_identificacion, fecha_nacimiento)
                    VALUES (?, ?, ?, ?)
                    """, usuarioId, req.tipoIdentificacion(), numero, req.fechaNacimiento());
            return Map.of("message", "Listo. Ahora las fotos de tu documento.", "requiereNuevoToken", false);
        }
        exigirNoAprobado(usuarioId);
        db.update("""
                UPDATE conductores SET tipo_identificacion = ?, numero_identificacion = ?, fecha_nacimiento = ?
                WHERE usuario_id = ?
                """, req.tipoIdentificacion(), numero, req.fechaNacimiento(), usuarioId);
        return Map.of("message", "Datos actualizados", "requiereNuevoToken", false);
    }

    /** Paso 2 "Tu licencia": categoría y número (el vencimiento va en el documento de la licencia). */
    @Transactional
    public Map<String, Object> guardarLicencia(Long usuarioId, LicenciaRequest req) {
        exigirPerfil(usuarioId);
        exigirNoAprobado(usuarioId);
        actor.declarar(usuarioId, ActorBd.CONDUCTOR);
        String arreglo = "{" + String.join(",", req.categoriasLicencia().stream().distinct().toList()) + "}";
        String numero = req.numeroLicencia().replaceAll("[.\\- ]", "").toUpperCase(Locale.ROOT);
        db.update("UPDATE conductores SET categorias_licencia = ?::varchar(2)[], numero_licencia = ? WHERE usuario_id = ?",
                arreglo, numero, usuarioId);
        return Map.of("message", "Licencia guardada");
    }

    /**
     * Paso 5 "Revisa y envía": todo completo + acepta las condiciones → queda en la fila del admin.
     * Si lo habían rechazado y ya corrigió, vuelve a "pendiente".
     */
    @Transactional
    public Map<String, Object> enviarSolicitud(Long usuarioId, boolean aceptaCondiciones, String ip, String plataforma) {
        exigirPerfil(usuarioId);
        if (!aceptaCondiciones) {
            throw new ReglaNegocioException(400, "Debes confirmar que la información es real y aceptar las condiciones");
        }
        Map<String, Object> p = progresoRegistro(usuarioId, null);
        String siguiente = String.valueOf(p.get("siguiente"));
        if ("aprobado".equals(siguiente)) throw new ReglaNegocioException("Tu cuenta de conductor ya está aprobada");
        exigirPuedeReenviar(p);
        List<?> faltan = (List<?>) p.get("faltantes");
        if (!faltan.isEmpty() || List.of("identidad", "licencia", "vehiculo", "documentos").contains(siguiente)) {
            String nombres = faltan.stream().map(f -> String.valueOf(((Map<?, ?>) f).get("nombre"))).limit(3)
                    .reduce((a, b) -> a + ", " + b).orElse("tus datos");
            throw new ReglaNegocioException("Todavía falta: " + nombres);
        }
        actor.declarar(usuarioId, ActorBd.CONDUCTOR);
        db.update("""
                INSERT INTO aceptaciones_legales (usuario_id, documento_id, ip_origen, plataforma)
                SELECT ?, id, ?, ? FROM documentos_legales WHERE tipo = 'contrato_conductor' AND activo
                ON CONFLICT (usuario_id, documento_id) DO NOTHING
                """, usuarioId, ip, plataforma);
        db.update("""
                UPDATE conductores
                   SET solicitud_enviada_en = now(),
                       estado = CASE WHEN estado IN ('rechazado', 'suspendido') THEN 'pendiente' ELSE estado END,
                       motivo_estado = CASE WHEN estado IN ('rechazado', 'suspendido') THEN NULL ELSE motivo_estado END
                 WHERE usuario_id = ?
                """, usuarioId);
        return Map.of("message", "¡Solicitud enviada! Revisaremos tus documentos.", "estado", "pendiente");
    }

    private void exigirNoAprobado(Long usuarioId) {
        if ("aprobado".equals(estado(usuarioId))) {
            throw new ReglaNegocioException("Tu perfil ya fue aprobado. Para cambiar estos datos escribe a soporte.");
        }
    }

    /** Después de corregir lo que el admin rechazó, el conductor vuelve a pedir revisión. */
    @Transactional
    public Map<String, Object> reenviarRevision(Long usuarioId) {
        String estado = estado(usuarioId);
        if (!"rechazado".equals(estado) && !"suspendido".equals(estado)) {
            throw new ReglaNegocioException("Tu perfil ya está " + nombreEstado(estado).toLowerCase(Locale.ROOT) + ".");
        }
        exigirPuedeReenviar(progresoRegistro(usuarioId, null));
        actor.declarar(usuarioId, ActorBd.CONDUCTOR);
        db.update("UPDATE conductores SET estado = 'pendiente', motivo_estado = NULL WHERE usuario_id = ?", usuarioId);
        return Map.of("message", "Enviamos tu perfil a revisión de nuevo", "estado", "pendiente");
    }

    /**
     * Suspendido: solo soporte lo reactiva (antes el conductor podía pasarse solo a "pendiente").
     * Rechazado: tiene que corregir algo antes de pedir revisión otra vez.
     */
    private void exigirPuedeReenviar(Map<String, Object> progreso) {
        String estado = String.valueOf(progreso.get("estado"));
        if ("suspendido".equals(estado)) {
            throw new ReglaNegocioException("Tu cuenta está suspendida. Escríbenos a soporte para revisar tu caso.");
        }
        if ("rechazado".equals(estado) && !Boolean.TRUE.equals(progreso.get("corregido"))) {
            Object motivo = progreso.get("motivo_estado");
            throw new ReglaNegocioException("Primero corrige lo que te indicamos"
                    + (motivo == null ? "" : ": " + motivo) + ". Luego vuelve a enviar tu solicitud.");
        }
    }

    /** Todo lo que necesita la pantalla del conductor (perfil, vehículo, documentos y qué falta). */
    public Map<String, Object> miPerfil(Long usuarioId) {
        List<Map<String, Object>> filas = db.queryForList("""
                SELECT c.usuario_id AS conductor_id, c.tipo_identificacion, c.numero_identificacion, c.fecha_nacimiento,
                       array_to_string(c.categorias_licencia, ',') AS categorias_licencia, c.estado, e.nombre AS estado_nombre,
                       e.color AS estado_color, c.motivo_estado, c.disponible, c.calificacion_promedio,
                       c.total_calificaciones, c.total_servicios, c.total_cancelaciones, c.verificado_en, c.creado_en,
                       c.sobre_mi
                FROM conductores c JOIN estados e ON e.ambito = 'conductor' AND e.codigo = c.estado
                WHERE c.usuario_id = ?
                """, usuarioId);
        if (filas.isEmpty()) throw new RecursoNoEncontradoException("Todavía no tienes perfil de conductor");
        Map<String, Object> r = new HashMap<>(filas.get(0));
        r.put("categorias_licencia", List.of(String.valueOf(r.get("categorias_licencia")).split(",")));
        Map<String, Object> vehiculo = vehiculoActivo(usuarioId);
        r.put("vehiculo", vehiculo);
        // Lo que puede recibir según su vehículo (tabla servicio_vehiculo_permitido, la misma que usa la asignación)
        r.put("servicios_autorizados", vehiculo == null ? List.of() : db.queryForList("""
                SELECT ts.codigo, ts.nombre, ts.descripcion
                FROM servicio_vehiculo_permitido p JOIN tipos_servicio ts ON ts.codigo = p.tipo_servicio
                WHERE p.tipo_vehiculo = ?
                ORDER BY CASE ts.codigo WHEN 'transporte' THEN 1 WHEN 'domicilio' THEN 2 ELSE 3 END
                """, vehiculo.get("tipo_vehiculo")));
        r.put("documentos", documentos(usuarioId));
        r.put("documentos_faltantes", faltantes(usuarioId));
        return r;
    }

    /**
     * "Tu desempeño" del perfil del conductor (como Uber/DiDi): estrellas por puntaje, los últimos comentarios
     * (sin el nombre del pasajero), la tasa de cancelación contra el límite de la sanción (más de 20 % con al menos
     * 5 cierres, ver ViajeServicio.tasaSancion) y cuántos pasajeros lo tienen de favorito.
     */
    public Map<String, Object> miDesempeno(Long usuarioId) {
        List<Map<String, Object>> filas = db.queryForList("""
                SELECT c.calificacion_promedio, c.total_calificaciones, c.total_servicios, c.total_cancelaciones,
                       COALESCE(c.verificado_en, c.creado_en) AS conductor_desde,
                       (SELECT count(*) FROM conductores_favoritos f WHERE f.conductor_id = c.usuario_id) AS favoritos
                FROM conductores c WHERE c.usuario_id = ?
                """, usuarioId);
        if (filas.isEmpty()) throw new RecursoNoEncontradoException("Todavía no tienes perfil de conductor");
        Map<String, Object> c = filas.get(0);

        Map<String, Object> estrellas = new HashMap<>();
        for (int i = 1; i <= 5; i++) estrellas.put(String.valueOf(i), 0L);
        db.queryForList("SELECT puntuacion, count(*) AS n FROM calificaciones WHERE calificado_id = ? GROUP BY puntuacion", usuarioId)
                .forEach(f -> estrellas.put(String.valueOf(f.get("puntuacion")), ((Number) f.get("n")).longValue()));

        List<Map<String, Object>> comentarios = db.queryForList("""
                SELECT ca.puntuacion, ca.comentario, ca.creado_en, s.tipo_servicio
                FROM calificaciones ca JOIN servicios s ON s.id = ca.servicio_id
                WHERE ca.calificado_id = ? AND NULLIF(btrim(ca.comentario), '') IS NOT NULL
                ORDER BY ca.creado_en DESC
                LIMIT 10
                """, usuarioId);

        long finalizados = ((Number) c.get("total_servicios")).longValue();
        long cancelados = ((Number) c.get("total_cancelaciones")).longValue();
        long cerrados = finalizados + cancelados;
        double tasa = cerrados == 0 ? 0 : Math.round(cancelados * 1000.0 / cerrados) / 10.0;

        Map<String, Object> r = new HashMap<>();
        r.put("calificacion_promedio", c.get("calificacion_promedio"));
        r.put("total_calificaciones", c.get("total_calificaciones"));
        r.put("estrellas", estrellas);
        r.put("comentarios", comentarios);
        r.put("total_servicios", finalizados);
        r.put("total_cancelaciones", cancelados);
        r.put("tasa_cancelacion", tasa);
        r.put("limite_cancelacion", 20.0);
        r.put("minimo_cierres", 5);
        r.put("sancionado", cerrados >= 5 && tasa > 20.0);
        r.put("favoritos", ((Number) c.get("favoritos")).longValue());
        // Elogios de los pasajeros ("Muy amable" ×12), de más a menos
        r.put("elogios", db.queryForList("""
                SELECT e AS elogio, count(*) AS veces
                FROM calificaciones ca, unnest(ca.elogios) AS e
                WHERE ca.calificado_id = ?
                GROUP BY e ORDER BY veces DESC, e
                """, usuarioId));
        r.put("conductor_desde", c.get("conductor_desde"));
        return r;
    }

    /**
     * "Sobre mí" (máx. 160 letras). No se permiten teléfonos, correos ni enlaces: el contacto con el pasajero va por
     * el chat del viaje. Vacío = lo borra.
     */
    public Map<String, Object> guardarSobreMi(Long usuarioId, String texto) {
        String t = texto == null ? "" : texto.strip().replaceAll("\\s+", " ");
        if (t.length() > 160) throw new ReglaNegocioException(400, "Escribe máximo 160 letras.");
        String sinEspacios = t.replaceAll("[\\s.\\-()]", "");
        if (sinEspacios.matches(".*\\d{7,}.*") || t.contains("@") || t.toLowerCase().matches(".*(https?://|www\\.|\\.com|\\.co\\b).*")) {
            throw new ReglaNegocioException(400, "No pongas teléfonos, correos ni enlaces. Tus pasajeros te escriben por el chat del viaje.");
        }
        actor.declarar(usuarioId, ActorBd.CONDUCTOR);
        db.update("UPDATE conductores SET sobre_mi = ? WHERE usuario_id = ?", t.isEmpty() ? null : t, usuarioId);
        return Map.of("sobre_mi", t, "message", t.isEmpty() ? "Quitamos tu presentación." : "¡Listo! Tus pasajeros verán tu presentación.");
    }

    /**
     * En qué paso va el registro del conductor (la app lo usa para saber qué pantalla abrir).
     * Funciona aunque todavía no tenga perfil.
     *
     * siguiente: identidad → licencia → vehiculo → documentos → enviar → revision · corregir · suspendido · aprobado
     * corregido: si lo rechazaron, ya cambió algo desde entonces (puede volver a enviar).
     */
    public Map<String, Object> progresoRegistro(Long usuarioId, String tipoElegido) {
        Map<String, Object> r = new HashMap<>();
        boolean perfil = tienePerfil(usuarioId);
        Map<String, Object> conductor = perfil ? db.queryForMap("""
                SELECT tipo_identificacion, numero_identificacion, fecha_nacimiento,
                       array_to_string(categorias_licencia, ',') AS categorias, numero_licencia,
                       estado, motivo_estado, solicitud_enviada_en
                FROM conductores WHERE usuario_id = ?
                """, usuarioId) : null;
        Map<String, Object> vehiculo = perfil ? vehiculoActivo(usuarioId) : null;
        String tipo = vehiculo != null ? String.valueOf(vehiculo.get("tipo_vehiculo"))
                : ("moto".equals(tipoElegido) || "taxi".equals(tipoElegido) ? tipoElegido : null);
        Integer anio = vehiculo == null ? null : ((Number) vehiculo.get("modelo_anio")).intValue();

        List<Map<String, Object>> documentos = perfil ? documentos(usuarioId) : List.of();
        List<Map<String, Object>> requeridos = requeridos(tipo, anio);
        List<String> listos = documentos.stream()
                .filter(d -> "pendiente".equals(d.get("estado")) || "aprobado".equals(d.get("estado")))
                .map(d -> String.valueOf(d.get("tipo_documento"))).toList();
        boolean rechazados = documentos.stream().anyMatch(d -> "rechazado".equals(d.get("estado")));
        String estado = conductor == null ? null : String.valueOf(conductor.get("estado"));
        String categorias = conductor == null ? "" : String.valueOf(conductor.get("categorias"));

        String siguiente;
        if (!perfil || !pasoCompleto(requeridos, listos, "identidad")) siguiente = "identidad";
        else if ("aprobado".equals(estado)) siguiente = "aprobado";
        // Suspendido por el admin: no se corrige desde la app, se habla con soporte (antes caía en "revision")
        else if ("suspendido".equals(estado)) siguiente = "suspendido";
        else if (conductor.get("solicitud_enviada_en") != null && ("rechazado".equals(estado) || rechazados)) siguiente = "corregir";
        else if (categorias.isBlank() || !pasoCompleto(requeridos, listos, "licencia")) siguiente = "licencia";
        else if (vehiculo == null || !pasoCompleto(requeridos, listos, "vehiculo")) siguiente = "vehiculo";
        else if (!pasoCompleto(requeridos, listos, "documentos")) siguiente = "documentos";
        else if (conductor.get("solicitud_enviada_en") == null) siguiente = "enviar";
        else siguiente = "revision";

        r.put("siguiente", siguiente);
        r.put("tiene_perfil", perfil);
        if (conductor != null) {
            Map<String, Object> datos = new HashMap<>(conductor);
            datos.put("categorias", categorias.isBlank() ? List.of() : List.of(categorias.split(",")));
            r.put("conductor", datos);
        } else {
            r.put("conductor", null);
        }
        r.put("estado", estado);
        r.put("motivo_estado", conductor == null ? null : conductor.get("motivo_estado"));
        // Pantalla "Solicitud rechazada": cuándo se rechazó y si ya corrigió algo desde entonces
        Map<String, Object> rechazo = perfil ? rechazo(usuarioId) : Map.of();
        r.put("rechazado_en", rechazo.get("rechazado_en"));
        r.put("corregido", perfil && corregido(usuarioId, rechazo, rechazados));
        r.put("tipo_vehiculo", tipo);
        r.put("vehiculo", vehiculo);
        r.put("documentos", documentos);
        r.put("requeridos", requeridos);
        r.put("faltantes", requeridos.stream().filter(t -> !listos.contains(String.valueOf(t.get("codigo")))).toList());
        return r;
    }

    /** Último rechazo o suspensión del conductor (historial_estados), o vacío si nunca lo rechazaron. */
    private Map<String, Object> rechazo(Long usuarioId) {
        return db.queryForList("""
                SELECT creado_en AS rechazado_en, estado_nuevo FROM historial_estados
                WHERE ambito = 'conductor' AND registro_id = ? AND estado_nuevo IN ('rechazado', 'suspendido')
                ORDER BY creado_en DESC LIMIT 1
                """, usuarioId).stream().findFirst().orElse(Map.of());
    }

    /**
     * ¿Ya corrigió? No le quedan documentos rechazados y, si el admin rechazó la solicitud, cambió algo después:
     * subió un documento o editó sus datos o los del vehículo. Así "reenviar" no borra el rechazo sin corregir nada.
     */
    private boolean corregido(Long usuarioId, Map<String, Object> rechazo, boolean quedanRechazados) {
        if (quedanRechazados) return false;
        Object cuando = rechazo.get("rechazado_en");
        if (cuando == null) return true;
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM documentos_conductor WHERE conductor_id = ? AND creado_en > ?)
                    OR EXISTS (SELECT 1 FROM vehiculos WHERE conductor_id = ? AND actualizado_en > ?)
                    OR EXISTS (SELECT 1 FROM conductores WHERE usuario_id = ? AND actualizado_en > ?)
                """, Boolean.class, usuarioId, cuando, usuarioId, cuando, usuarioId, cuando));
    }

    private static boolean pasoCompleto(List<Map<String, Object>> requeridos, List<String> listos, String paso) {
        return requeridos.stream().filter(t -> paso.equals(t.get("paso")))
                .allMatch(t -> listos.contains(String.valueOf(t.get("codigo"))));
    }

    /** Documentos y fotos obligatorios para moto o taxi (y según la edad del vehículo), en el orden de la app. */
    public List<Map<String, Object>> requeridos(String tipo, Integer modeloAnio) {
        return db.queryForList("""
                SELECT codigo, nombre, aplica_a, requiere_vencimiento, paso, orden, solo_imagen, ayuda
                FROM tipos_documento
                WHERE fn_documento_exigido(codigo, CAST(? AS varchar), CAST(? AS int))
                ORDER BY CASE paso WHEN 'identidad' THEN 1 WHEN 'licencia' THEN 2 WHEN 'vehiculo' THEN 3 ELSE 4 END, orden
                """, tipo, modeloAnio);
    }

    /** Nombre del archivo de un documento vigente del propio conductor (para verlo desde la app). */
    public String archivoPropio(Long usuarioId, Long documentoId) {
        return db.queryForList("""
                SELECT ruta_archivo FROM documentos_conductor WHERE id = ? AND conductor_id = ?
                """, String.class, documentoId, usuarioId)
                .stream().findFirst().orElseThrow(() -> new RecursoNoEncontradoException("Documento no encontrado"));
    }

    // ───────────── Vehículo ─────────────

    public Map<String, Object> vehiculoActivo(Long usuarioId) {
        return db.queryForList("""
                SELECT id, tipo_vehiculo, placa, marca, linea, modelo_anio, color, empresa_afiliadora, numero_interno,
                       cilindraje_cc, casco_pasajero
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
        Integer cilindraje = "moto".equals(v.tipoVehiculo()) ? v.cilindrajeCc() : null;
        Boolean casco = "moto".equals(v.tipoVehiculo()) ? v.cascoPasajero() : null;
        Map<String, Object> actual = vehiculoActivo(usuarioId);

        // Mientras no esté aprobado, "Editar" corrige el mismo vehículo (sus fotos y documentos se conservan)
        if (actual != null && !"aprobado".equals(estado(usuarioId))) {
            Long id = Datos.aLong(actual.get("id"));
            db.update("""
                    UPDATE vehiculos SET tipo_vehiculo = ?, placa = ?, marca = ?, linea = ?, modelo_anio = ?, color = ?,
                                         empresa_afiliadora = ?, numero_interno = ?, cilindraje_cc = ?, casco_pasajero = ?
                    WHERE id = ?
                    """, v.tipoVehiculo(), placa, v.marca().trim(), Datos.vacioANull(v.linea()), v.modeloAnio(),
                    v.color().trim(), Datos.vacioANull(v.empresaAfiliadora()), Datos.vacioANull(v.numeroInterno()),
                    cilindraje, casco, id);
            return Map.of("id", id, "message", "Vehículo actualizado");
        }

        db.update("UPDATE vehiculos SET activo = false WHERE conductor_id = ? AND activo", usuarioId);
        Long id = db.queryForObject("""
                INSERT INTO vehiculos (conductor_id, tipo_vehiculo, placa, marca, linea, modelo_anio, color,
                                       empresa_afiliadora, numero_interno, cilindraje_cc, casco_pasajero)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, usuarioId, v.tipoVehiculo(), placa, v.marca().trim(), Datos.vacioANull(v.linea()),
                v.modeloAnio(), v.color().trim(), Datos.vacioANull(v.empresaAfiliadora()),
                Datos.vacioANull(v.numeroInterno()), cilindraje, casco);
        return Map.of("id", id, "message", "Vehículo registrado. Ahora sube sus documentos.");
    }

    // ───────────── Documentos ─────────────

    @Transactional
    public Map<String, Object> subirDocumento(Long usuarioId, String tipo, MultipartFile archivo, LocalDate vence) {
        exigirPerfil(usuarioId);
        Map<String, Object> td = db.queryForList(
                "SELECT codigo, nombre, aplica_a, requiere_vencimiento, solo_imagen FROM tipos_documento WHERE codigo = ?", tipo)
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
        boolean permitirPdf = !Boolean.TRUE.equals(td.get("solo_imagen")); // fotos y selfie: solo imagen
        ArchivoServicio.Guardado g = archivos.guardar(archivo, tipo.replaceAll("[^a-z_]", ""), permitirPdf);
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
                  AND (d.vehiculo_id IS NULL
                       OR d.vehiculo_id = (SELECT id FROM vehiculos WHERE conductor_id = d.conductor_id AND activo))
                ORDER BY td.aplica_a, td.nombre
                """, usuarioId);
    }

    /** Documentos obligatorios para su vehículo que todavía no ha subido (o le rechazaron). */
    public List<Map<String, Object>> faltantes(Long usuarioId) {
        Map<String, Object> v = vehiculoActivo(usuarioId);
        String tipo = v == null ? null : String.valueOf(v.get("tipo_vehiculo"));
        Integer anio = v == null ? null : ((Number) v.get("modelo_anio")).intValue();
        return db.queryForList("""
                SELECT td.codigo, td.nombre, td.aplica_a, td.requiere_vencimiento, td.paso, td.solo_imagen, td.ayuda
                FROM tipos_documento td
                WHERE fn_documento_exigido(td.codigo, CAST(? AS varchar), CAST(? AS int))
                  AND NOT EXISTS (SELECT 1 FROM documentos_conductor d
                                   WHERE d.conductor_id = ? AND d.tipo_documento = td.codigo AND d.vigente
                                     AND d.estado IN ('pendiente', 'aprobado')
                                     AND (td.aplica_a = 'conductor' OR d.vehiculo_id = CAST(? AS bigint)))
                ORDER BY CASE td.paso WHEN 'identidad' THEN 1 WHEN 'licencia' THEN 2 WHEN 'vehiculo' THEN 3 ELSE 4 END, td.orden
                """, tipo, anio, usuarioId, v == null ? null : v.get("id"));
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

    // ───────────── Mapa y perfil de la web ─────────────

    /**
     * "Zonas con más pedidos" del mapa del conductor: origen de los servicios de los últimos 7 días
     * agrupado en celdas de ~550 m (0,005°). Devuelve el centro de las 6 celdas con más pedidos.
     */
    public List<Map<String, Object>> zonasDemanda() {
        return db.queryForList("""
                SELECT round(avg(origen_lat)::numeric, 5) AS lat, round(avg(origen_lng)::numeric, 5) AS lng,
                       count(*) AS pedidos
                FROM servicios
                WHERE solicitado_en >= now() - INTERVAL '7 days'
                GROUP BY floor(origen_lat / 0.005), floor(origen_lng / 0.005)
                ORDER BY pedidos DESC
                LIMIT 6
                """);
    }

    /** Edita nombre y foto del conductor (la ciudad es siempre Buenaventura; el celular solo con código SMS). */
    @Transactional
    public Map<String, Object> actualizarPerfilWeb(Long id, Map<String, Object> datos) {
        String nombre = Datos.recortar(Datos.texto(datos.get("nombre")), 160);
        String foto = Datos.recortar(Datos.texto(datos.get("foto")), 300);
        if (nombre != null && !nombre.isBlank()) {
            String[] p = nombre.trim().split("\\s+", 2);
            db.update("UPDATE usuarios SET nombres = ?, apellidos = ? WHERE id = ?", p[0], p.length > 1 ? p[1] : null, id);
        }
        if (foto != null) db.update("UPDATE usuarios SET foto_url = ? WHERE id = ?", foto.isBlank() ? null : foto, id);
        if (datos.get("telefono") != null) {
            throw new ReglaNegocioException(400, "El celular se cambia desde la app con un código SMS");
        }
        return Map.of("mensaje", "Perfil actualizado correctamente");
    }

    /** Perfil con estadísticas buscado por correo: solo el propio conductor o el personal. */
    public Map<String, Object> perfilPorCorreo(String correo, Long yo, boolean personal) {
        List<Map<String, Object>> u = db.queryForList("SELECT id FROM usuarios WHERE lower(email) = lower(?)", correo.trim());
        if (u.isEmpty()) throw new RecursoNoEncontradoException("Usuario no encontrado");
        Long id = Datos.aLong(u.get(0).get("id"));
        if (!id.equals(yo) && !personal) {
            throw new AccesoDenegadoException("No puedes ver el perfil de otro conductor");
        }
        return perfilConEstadisticas(id);
    }

    public Map<String, Object> perfilConEstadisticas(Long id) {
        List<Map<String, Object>> filas = db.queryForList("""
                SELECT c.usuario_id AS conductor_id, trim(u.nombres || ' ' || COALESCE(u.apellidos, '')) AS nombre,
                       u.email AS correo, u.telefono, u.foto_url AS foto, c.estado, c.motivo_estado AS motivo_rechazo,
                       c.disponible AS en_linea, c.calificacion_promedio AS calificacion,
                       v.placa, trim(v.marca || ' ' || COALESCE(v.linea, '') || ' ' || v.modelo_anio) AS modelo,
                       v.tipo_vehiculo, 'Buenaventura' AS ciudad
                FROM conductores c
                JOIN usuarios u ON u.id = c.usuario_id
                LEFT JOIN vehiculos v ON v.conductor_id = c.usuario_id AND v.activo
                WHERE c.usuario_id = ?
                """, id);
        if (filas.isEmpty()) throw new RecursoNoEncontradoException("Perfil de conductor no encontrado para este usuario");
        Map<String, Object> r = new HashMap<>(filas.get(0));
        Map<String, Object> s = db.queryForMap("""
                SELECT COUNT(*) FILTER (WHERE estado = 'finalizado') AS total,
                       COUNT(*) FILTER (WHERE estado = 'finalizado'
                                          AND (finalizado_en AT TIME ZONE 'America/Bogota')::date = (now() AT TIME ZONE 'America/Bogota')::date) AS hoy,
                       COALESCE(SUM(valor_final) FILTER (WHERE estado = 'finalizado'
                                          AND (finalizado_en AT TIME ZONE 'America/Bogota')::date = (now() AT TIME ZONE 'America/Bogota')::date), 0) AS g_hoy,
                       COALESCE(SUM(valor_final) FILTER (WHERE estado = 'finalizado'
                                          AND finalizado_en >= date_trunc('week', now() AT TIME ZONE 'America/Bogota') AT TIME ZONE 'America/Bogota'), 0) AS g_semana
                FROM servicios WHERE conductor_id = ?
                """, id);
        r.put("gananciasHoy", s.get("g_hoy"));
        r.put("gananciasSemana", s.get("g_semana"));
        r.put("viajesHoy", s.get("hoy"));
        r.put("viajesTotal", s.get("total"));
        r.put("historial", List.of());
        r.put("solicitudes", List.of());
        return r;
    }

    /** Documento de algún conductor (para el panel): 404 si el nombre de archivo no está registrado. */
    public String documentoRegistrado(String nombreArchivo) {
        Boolean existe = db.queryForObject("SELECT EXISTS (SELECT 1 FROM documentos_conductor WHERE ruta_archivo = ?)",
                Boolean.class, nombreArchivo);
        if (!Boolean.TRUE.equals(existe)) throw new RecursoNoEncontradoException("Documento no encontrado");
        return nombreArchivo;
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
