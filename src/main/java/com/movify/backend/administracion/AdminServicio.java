package com.movify.backend.administracion;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movify.backend.comun.ActorBd;
import com.movify.backend.comun.Celular;
import com.movify.backend.comun.Datos;
import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.comun.ReglaNegocioException;
import com.movify.backend.notificaciones.NotificacionServicio;
import com.movify.backend.seguridad.SesionActual;

/**
 * Acciones del panel administrativo. Cada cambio declara al actor (admin o soporte) para que
 * los triggers validen la transición, y queda en auditoria_admin con nombre propio.
 */
@Service
public class AdminServicio {

    private final JdbcTemplate db;
    private final SesionActual sesion;
    private final ActorBd actor;
    private final ObjectMapper json = new ObjectMapper();

    private final NotificacionServicio avisos;

    public AdminServicio(JdbcTemplate db, SesionActual sesion, ActorBd actor, NotificacionServicio avisos) {
        this.avisos = avisos;
        this.db = db;
        this.sesion = sesion;
        this.actor = actor;
    }

    // ───────────── Conductores ─────────────

    public List<Map<String, Object>> conductores(String estado) {
        return db.queryForList("""
                SELECT c.usuario_id AS conductor_id, trim(u.nombres || ' ' || COALESCE(u.apellidos, '')) AS nombre,
                       u.email AS correo, u.telefono, v.placa, v.tipo_vehiculo,
                       trim(v.marca || ' ' || COALESCE(v.linea, '') || ' ' || v.modelo_anio) AS modelo,
                       c.estado, e.nombre AS estado_nombre, e.color AS estado_color, c.disponible AS en_linea,
                       c.calificacion_promedio AS calificacion, c.total_servicios, c.creado_en,
                       (SELECT count(*) FROM documentos_conductor d WHERE d.conductor_id = c.usuario_id AND d.vigente
                                                                       AND d.estado = 'pendiente') AS documentos_por_revisar
                FROM conductores c
                JOIN usuarios u ON u.id = c.usuario_id
                JOIN estados e ON e.ambito = 'conductor' AND e.codigo = c.estado
                LEFT JOIN vehiculos v ON v.conductor_id = c.usuario_id AND v.activo
                WHERE CAST(? AS varchar) IS NULL OR c.estado = ?
                ORDER BY (c.estado = 'pendiente') DESC, c.creado_en DESC
                """, estado, estado);
    }

    public Map<String, Object> detalleConductor(Long id) {
        List<Map<String, Object>> f = db.queryForList("""
                SELECT c.usuario_id AS conductor_id, trim(u.nombres || ' ' || COALESCE(u.apellidos, '')) AS nombre,
                       u.email AS correo, u.telefono, u.telefono_verificado, u.foto_url AS foto,
                       c.tipo_identificacion, c.numero_identificacion, c.fecha_nacimiento,
                       array_to_string(c.categorias_licencia, ', ') AS categorias_licencia,
                       c.estado, c.motivo_estado AS motivo_rechazo, c.verificado_en AS fecha_verificacion,
                       v.id AS vehiculo_id, v.tipo_vehiculo, v.placa, v.marca, v.linea, v.modelo_anio, v.color,
                       v.empresa_afiliadora, v.numero_interno,
                       trim(v.marca || ' ' || COALESCE(v.linea, '') || ' ' || v.modelo_anio) AS modelo
                FROM conductores c
                JOIN usuarios u ON u.id = c.usuario_id
                LEFT JOIN vehiculos v ON v.conductor_id = c.usuario_id AND v.activo
                WHERE c.usuario_id = ?
                """, id);
        if (f.isEmpty()) throw new RecursoNoEncontradoException("Conductor no encontrado");
        Map<String, Object> r = new HashMap<>(f.get(0));
        List<Map<String, Object>> docs = db.queryForList("""
                SELECT d.id, d.tipo_documento, td.nombre, d.ruta_archivo AS archivo, d.mime_type, d.estado,
                       e.nombre AS estado_nombre, d.observacion, d.fecha_vencimiento, d.creado_en, d.revisado_en
                FROM documentos_conductor d
                JOIN tipos_documento td ON td.codigo = d.tipo_documento
                JOIN estados e ON e.ambito = 'documento' AND e.codigo = d.estado
                WHERE d.conductor_id = ? AND d.vigente
                ORDER BY td.aplica_a, td.nombre
                """, id);
        r.put("documentos", docs);
        // Campos que ya mostraba la web (nombre del archivo vigente de cada documento)
        for (Map<String, Object> d : docs) {
            switch (String.valueOf(d.get("tipo_documento"))) {
                case "licencia_conduccion" -> r.put("licencia", d.get("archivo"));
                case "soat" -> r.put("soat", d.get("archivo"));
                case "tarjeta_propiedad" -> r.put("tarjeta_propiedad", d.get("archivo"));
                case "cedula" -> r.put("cedula", d.get("archivo"));
                default -> {
                }
            }
        }
        return r;
    }

    /**
     * Aprobar exige (trigger) celular verificado, vehículo activo y todos los documentos obligatorios
     * aprobados y vigentes. Para conservar el "aprobar de un clic" de la web, al aprobar al conductor
     * se aprueban también sus documentos vigentes que siguen por revisar (el admin ya los vio en el visor).
     */
    @Transactional
    public Map<String, Object> cambiarEstadoConductor(Long id, String estado, String motivo) {
        String actual = db.queryForList("SELECT estado FROM conductores WHERE usuario_id = ? FOR UPDATE", String.class, id)
                .stream().findFirst().orElseThrow(() -> new RecursoNoEncontradoException("Conductor no encontrado"));
        String nuevo = estado.toLowerCase();
        if (nuevo.equals(actual)) return Map.of("message", "El conductor ya está " + nuevo);
        if (Set.of("rechazado", "suspendido").contains(nuevo) && (motivo == null || motivo.isBlank())) {
            throw new ReglaNegocioException(400, "Escribe el motivo para que el conductor sepa qué corregir");
        }
        actor.declarar(sesion.usuarioId(), sesion.actorPersonal());
        if ("aprobado".equals(nuevo)) {
            db.update("UPDATE documentos_conductor SET estado = 'aprobado' WHERE conductor_id = ? AND vigente AND estado = 'pendiente'", id);
        }
        db.update("UPDATE conductores SET estado = ?, motivo_estado = ? WHERE usuario_id = ?", nuevo,
                "aprobado".equals(nuevo) || "pendiente".equals(nuevo) ? null : motivo.trim(), id);
        auditar("conductor_" + nuevo, "conductores", id, Map.of("antes", actual, "motivo", Datos.nvl(motivo)));
        return Map.of("message", "Estado del conductor actualizado a " + nuevo);
    }

    @Transactional
    public Map<String, Object> revisarDocumento(Long documentoId, String estado, String observacion) {
        if (!Set.of("aprobado", "rechazado").contains(estado)) throw new ReglaNegocioException(400, "Estado no válido");
        if ("rechazado".equals(estado) && (observacion == null || observacion.isBlank())) {
            throw new ReglaNegocioException(400, "Escribe por qué se rechaza el documento");
        }
        actor.declarar(sesion.usuarioId(), ActorBd.ADMIN);
        int n = db.update("UPDATE documentos_conductor SET estado = ?, observacion = ? WHERE id = ? AND vigente",
                estado, "rechazado".equals(estado) ? observacion.trim() : Datos.vacioANull(observacion), documentoId);
        if (n == 0) throw new RecursoNoEncontradoException("Documento no encontrado");
        auditar("documento_" + estado, "documentos_conductor", documentoId, Map.of("observacion", Datos.nvl(observacion)));
        return Map.of("message", "Documento " + estado);
    }

    // ───────────── Usuarios ─────────────

    public List<Map<String, Object>> usuarios() {
        return db.queryForList("""
                SELECT u.id, trim(u.nombres || ' ' || COALESCE(u.apellidos, '')) AS nombre,
                       COALESCE(u.email, '') AS correo, COALESCE(u.telefono, '') AS telefono, u.telefono_verificado,
                       COALESCE((SELECT string_agg(ur.rol, ',' ORDER BY ur.rol) FROM usuario_roles ur WHERE ur.usuario_id = u.id), '') AS roles,
                       CASE WHEN EXISTS (SELECT 1 FROM usuario_roles WHERE usuario_id = u.id AND rol = 'admin') THEN 'admin'
                            WHEN EXISTS (SELECT 1 FROM usuario_roles WHERE usuario_id = u.id AND rol = 'soporte') THEN 'soporte'
                            WHEN EXISTS (SELECT 1 FROM usuario_roles WHERE usuario_id = u.id AND rol = 'conductor') THEN 'conductor'
                            -- Se registró como conductor y el admin todavía no lo aprueba (V6: sin rol)
                            WHEN u.registrado_como = 'conductor'
                              OR EXISTS (SELECT 1 FROM conductores WHERE usuario_id = u.id) THEN 'aspirante'
                            ELSE 'cliente' END AS rol,
                       u.estado, u.motivo_bloqueo, u.calificacion_promedio,
                       TO_CHAR(u.creado_en AT TIME ZONE 'America/Bogota', 'YYYY-MM-DD"T"HH24:MI:SS') AS "fechaRegistro",
                       u.ultimo_acceso
                FROM usuarios u
                WHERE u.estado <> 'eliminado'
                ORDER BY u.id DESC
                """);
    }

    /** activo | bloqueado ("inactivo" de la web vieja = bloqueado). */
    @Transactional
    public Map<String, Object> cambiarEstadoUsuario(Long id, String estado, String motivo) {
        String nuevo = "inactivo".equals(estado) ? "bloqueado" : estado;
        // Bloquear deja a la persona sin servicio: siempre con un motivo que quede escrito (y que ella pueda conocer)
        if ("bloqueado".equals(nuevo) && (motivo == null || motivo.trim().length() < 5)) {
            throw new ReglaNegocioException(400, "Escribe el motivo del bloqueo (al menos 5 letras)");
        }
        if (id.equals(sesion.usuarioId()) && "bloqueado".equals(nuevo)) {
            throw new ReglaNegocioException(400, "No puedes bloquear tu propia cuenta");
        }
        actor.declarar(sesion.usuarioId(), ActorBd.ADMIN);
        String m = "bloqueado".equals(nuevo) ? Datos.recortar(motivo.trim(), 200) : null;
        int n = db.update("UPDATE usuarios SET estado = ?, motivo_bloqueo = ? WHERE id = ? AND estado <> 'eliminado'", nuevo, m, id);
        if (n == 0) throw new RecursoNoEncontradoException("Usuario no encontrado");
        if ("bloqueado".equals(nuevo)) {
            db.update("UPDATE sesiones SET revocada_en = now() WHERE usuario_id = ? AND revocada_en IS NULL", id);
            db.update("UPDATE conductores SET disponible = false WHERE usuario_id = ? AND disponible", id);
        }
        auditar("usuario_" + nuevo, "usuarios", id, Map.of("motivo", Datos.nvl(m)));
        return Map.of("message", "Estado actualizado correctamente");
    }

    @Transactional
    public Map<String, Object> asignarRol(Long usuarioId, String rol, boolean quitar) {
        if (quitar && "admin".equals(rol)) {
            // Nunca quedarse sin administradores ni quitarse uno mismo el acceso por error
            if (usuarioId.equals(sesion.usuarioId())) {
                throw new ReglaNegocioException(400, "No puedes quitarte tu propio rol de administrador. Pídeselo a otro admin.");
            }
            Integer admins = db.queryForObject("SELECT count(*) FROM usuario_roles ur JOIN usuarios u ON u.id = ur.usuario_id "
                    + "WHERE ur.rol = 'admin' AND u.estado = 'activo'", Integer.class);
            if (admins != null && admins <= 1) throw new ReglaNegocioException(400, "Tiene que quedar al menos un administrador activo.");
        }
        actor.declarar(sesion.usuarioId(), ActorBd.ADMIN);
        if (quitar) {
            db.update("DELETE FROM usuario_roles WHERE usuario_id = ? AND rol = ?", usuarioId, rol);
        } else {
            db.update("INSERT INTO usuario_roles (usuario_id, rol) VALUES (?, ?) ON CONFLICT DO NOTHING", usuarioId, rol);
        }
        auditar(quitar ? "rol_quitado" : "rol_asignado", "usuarios", usuarioId, Map.of("rol", rol));
        return Map.of("message", quitar ? "Rol quitado" : "Rol asignado", "nota", "El cambio aplica cuando la persona vuelva a entrar");
    }

    // ───────────── Tarifas y configuración ─────────────

    /** Las tarifas no se editan: se crea la nueva versión y se desactiva la anterior (trigger de inmutabilidad). */
    @Transactional
    public Map<String, Object> nuevaTarifa(String tipoServicio, String tipoVehiculo, BigDecimal base, BigDecimal km,
            BigDecimal minuto, BigDecimal minima, BigDecimal recargoNocturno, BigDecimal recargoLluvia) {
        actor.declarar(sesion.usuarioId(), ActorBd.ADMIN);
        db.update("UPDATE tarifas SET activo = false WHERE tipo_servicio = ? AND tipo_vehiculo = ? AND activo", tipoServicio, tipoVehiculo);
        Long id = db.queryForObject("""
                INSERT INTO tarifas (tipo_servicio, tipo_vehiculo, tarifa_base, valor_km, valor_minuto, tarifa_minima,
                                     recargo_nocturno_pct, recargo_lluvia_pct, creado_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, tipoServicio, tipoVehiculo, base, km, minuto, minima, recargoNocturno,
                recargoLluvia == null ? BigDecimal.ZERO : recargoLluvia, sesion.usuarioId());
        auditar("tarifa_nueva", "tarifas", id, Map.of("servicio", tipoServicio, "vehiculo", tipoVehiculo));
        return Map.of("id", id, "message", "Tarifa actualizada. Los servicios ya creados conservan su tarifa.");
    }

    @Transactional
    public Map<String, Object> editarConfiguracion(String clave, String valor) {
        String actual = db.queryForList("SELECT valor FROM configuracion_sistema WHERE clave = ?", String.class, clave)
                .stream().findFirst().orElseThrow(() -> new RecursoNoEncontradoException("No existe el parámetro " + clave));
        String nuevo = validarParametro(clave, actual, valor.trim());
        actor.declarar(sesion.usuarioId(), ActorBd.ADMIN);
        db.update("UPDATE configuracion_sistema SET valor = ? WHERE clave = ?", nuevo, clave);
        auditar("configuracion", "configuracion_sistema", 0L, Map.of("clave", clave, "antes", actual, "valor", nuevo));
        return Map.of("message", "Parámetro actualizado", "valor", nuevo);
    }

    /**
     * La base lee los parámetros numéricos con fn_config_num (valor::numeric): un texto ahí rompería la búsqueda
     * de conductores, las tarifas y los tiempos. Por eso un parámetro que hoy es número solo acepta números.
     */
    static String validarParametro(String clave, String actual, String valor) {
        if ("telefono_central".equals(clave)) {
            if (valor.isEmpty()) return "";
            String tel = Celular.normalizarContacto(valor);
            if (tel == null) throw new ReglaNegocioException(400, "Escribe un celular (3XX XXX XXXX) o un fijo (60X XXX XXXX)");
            return tel;
        }
        if (!actual.matches("-?\\d+(\\.\\d+)?")) {
            if (valor.isEmpty()) throw new ReglaNegocioException(400, "El valor no puede quedar vacío");
            return valor;
        }
        BigDecimal n;
        try {
            n = new BigDecimal(valor.replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new ReglaNegocioException(400, "Este parámetro es un número. Escribe solo dígitos (por ejemplo 5 o 2.5)");
        }
        if (clave.startsWith("hora_") && (n.compareTo(BigDecimal.ZERO) < 0 || n.compareTo(new BigDecimal("23")) > 0
                || n.stripTrailingZeros().scale() > 0)) {
            throw new ReglaNegocioException(400, "La hora va de 0 a 23 (hora de Colombia)");
        }
        if (clave.startsWith("cobertura_l")) return n.toPlainString(); // latitud / longitud: pueden ser negativas
        if (clave.endsWith("_activa") && n.compareTo(BigDecimal.ZERO) != 0 && n.compareTo(BigDecimal.ONE) != 0) {
            throw new ReglaNegocioException(400, "Este interruptor solo acepta 1 (prendido) o 0 (apagado)");
        }
        if (n.signum() < 0) throw new ReglaNegocioException(400, "El valor no puede ser negativo");
        if (n.signum() == 0 && NO_CERO.contains(clave)) {
            throw new ReglaNegocioException(400, "Este parámetro no puede ser 0: dejaría de funcionar esa parte de MoviFY");
        }
        return n.stripTrailingZeros().toPlainString();
    }

    /** Parámetros que en 0 apagarían la operación (nadie recibiría pedidos, nadie podría entrar…). */
    private static final Set<String> NO_CERO = Set.of("radio_busqueda_km", "tiempo_busqueda_seg", "ubicacion_vigencia_seg",
            "cobertura_radio_km", "otp_vigencia_seg", "otp_max_intentos", "otp_max_envios_hora", "max_intentos_fallidos",
            "minutos_ventana_intentos", "horas_vida_access_token", "dias_vida_refresh_token", "tope_compra_domicilio",
            "peso_max_moto_kg", "horas_enlace_compartido", "max_dias_programar", "dias_para_calificar");

    // ───────────── SOS y PQRS ─────────────

    @Transactional
    public Map<String, Object> atenderSos(Long id, String estado, String notas) {
        actor.declarar(sesion.usuarioId(), sesion.actorPersonal());
        int n = db.update("""
                UPDATE alertas_sos SET estado = ?, notas = COALESCE(?, notas), atendida_por = ?,
                       atendida_en = CASE WHEN ? IN ('atendida', 'falsa_alarma') THEN now() ELSE atendida_en END
                WHERE id = ?
                """, estado, Datos.vacioANull(notas), sesion.usuarioId(), estado, id);
        if (n == 0) throw new RecursoNoEncontradoException("Alerta no encontrada");
        auditar("sos_" + estado, "alertas_sos", id, Map.of("notas", Datos.nvl(notas)));
        return Map.of("message", "Alerta actualizada");
    }

    @Transactional
    public Map<String, Object> responderPqrs(Long id, String estado, String respuesta, String prioridad) {
        actor.declarar(sesion.usuarioId(), sesion.actorPersonal());
        int n = db.update("""
                UPDATE tickets_soporte SET estado = COALESCE(?, estado), respuesta = COALESCE(?, respuesta),
                       prioridad = COALESCE(?, prioridad), asignado_a = COALESCE(asignado_a, ?)
                WHERE id = ?
                """, Datos.vacioANull(estado), Datos.vacioANull(respuesta), Datos.vacioANull(prioridad), sesion.usuarioId(), id);
        if (n == 0) throw new RecursoNoEncontradoException("PQRS no encontrada");
        auditar("pqrs_" + Datos.nvl(estado), "tickets_soporte", id, Map.of());
        // Con respuesta, a la persona le llega el aviso en el celular ("Ayuda" en la app muestra la respuesta)
        if (Datos.vacioANull(respuesta) != null) {
            Map<String, Object> t = db.queryForMap("SELECT usuario_id, servicio_id, asunto FROM tickets_soporte WHERE id = ?", id);
            avisos.avisar(Datos.aLong(t.get("usuario_id")), Datos.aLong(t.get("servicio_id")), NotificacionServicio.SOPORTE,
                    "soporte", "Soporte de MoviFY te respondió", "Sobre «" + Datos.recortar(String.valueOf(t.get("asunto")), 60)
                            + "». Ábrela en Ayuda.");
        }
        return Map.of("message", "PQRS actualizada");
    }

    // ───────────── Auditoría ─────────────

    void auditar(String accion, String entidad, Long entidadId, Map<String, Object> detalle) {
        String d;
        try {
            d = json.writeValueAsString(detalle);
        } catch (JsonProcessingException e) {
            d = "{}";
        }
        db.update("INSERT INTO auditoria_admin (admin_id, accion, entidad, entidad_id, detalle) VALUES (?, ?, ?, ?, ?::jsonb)",
                sesion.usuarioId(), accion.length() > 40 ? accion.substring(0, 40) : accion, entidad, entidadId, d);
    }
}
