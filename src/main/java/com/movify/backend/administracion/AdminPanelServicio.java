package com.movify.backend.administracion;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.movify.backend.comun.ActorBd;
import com.movify.backend.comun.Celular;
import com.movify.backend.comun.ContenidoProhibido;
import com.movify.backend.comun.Datos;
import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.comun.ReglaNegocioException;
import com.movify.backend.notificaciones.NotificacionServicio;
import com.movify.backend.seguridad.SesionActual;
import com.movify.backend.servicios.ViajeServicio;

/**
 * Lo que el panel necesita para operar sin traer miles de filas a Angular: resumen del día, búsquedas
 * paginadas y filtradas en la base, reasignar un servicio atascado, expediente del conductor con el checklist de
 * su tipo de vehículo, detalle de un cliente, reportes con exportación, calificaciones bajas, el equipo interno,
 * los permisos de soporte y las palabras prohibidas. Las listas muestran teléfonos, correos y documentos
 * parcialmente; el detalle los muestra completos (y queda en el registro de accesos del backend).
 */
@Service
public class AdminPanelServicio {

    /** Comienzo del día de hoy en Colombia (para "hoy" en el resumen). */
    private static final String HOY = "(date_trunc('day', now() AT TIME ZONE 'America/Bogota') AT TIME ZONE 'America/Bogota')";
    private static final int MAX_TAMANO = 100;
    private static final Set<String> ESTADOS = Set.of("buscando", "asignado", "en_origen", "en_curso", "finalizado", "cancelado", "expirado");
    /** Roles cuyos permisos se pueden ajustar desde el panel (admin queda fijo para no dejar la plataforma sin dueño). */
    private static final Set<String> ROLES_EDITABLES = Set.of("soporte");

    private final JdbcTemplate db;
    private final SesionActual sesion;
    private final ActorBd actor;
    private final AdminServicio admin;
    private final ViajeServicio viajes;
    private final NotificacionServicio avisos;
    private final ContenidoProhibidoCatalogo prohibidas;

    public AdminPanelServicio(JdbcTemplate db, SesionActual sesion, ActorBd actor, AdminServicio admin, ViajeServicio viajes,
            NotificacionServicio avisos, ContenidoProhibidoCatalogo prohibidas) {
        this.db = db;
        this.sesion = sesion;
        this.actor = actor;
        this.admin = admin;
        this.viajes = viajes;
        this.avisos = avisos;
        this.prohibidas = prohibidas;
    }

    /** Página pedida dentro de límites sanos. */
    record Pagina(int numero, int tamano) {
        static Pagina de(int pagina, int tamano) {
            return new Pagina(Math.max(0, pagina), Math.min(Math.max(tamano, 5), MAX_TAMANO));
        }

        int desplazamiento() {
            return numero * tamano;
        }
    }

    private static Map<String, Object> resultado(List<Map<String, Object>> items, long total, Pagina p) {
        return Map.of("items", items, "total", total, "pagina", p.numero(), "tamano", p.tamano());
    }

    // ═════════════════════════════ 1. Resumen del día ═════════════════════════════

    /** Lo que hay que mirar hoy: actividad, problemas (sin conductor, cancelaciones) y pendientes del equipo. */
    public Map<String, Object> resumenDelDia() {
        Map<String, Object> r = new HashMap<>(db.queryForMap("""
                WITH hoy AS (SELECT * FROM servicios WHERE solicitado_en >= %1$s)
                SELECT (SELECT count(*) FROM hoy) AS servicios_hoy,
                       (SELECT count(*) FROM hoy WHERE estado = 'finalizado') AS finalizados_hoy,
                       (SELECT count(*) FROM hoy WHERE estado = 'cancelado') AS cancelados_hoy,
                       (SELECT count(*) FROM hoy WHERE estado = 'cancelado' AND cancelado_por = cliente_id) AS cancelados_cliente_hoy,
                       (SELECT count(*) FROM hoy WHERE estado = 'cancelado' AND cancelado_por = conductor_id) AS cancelados_conductor_hoy,
                       (SELECT count(*) FROM hoy WHERE estado = 'expirado') AS sin_conductor_hoy,
                       (SELECT COALESCE(sum(valor_final), 0) FROM hoy WHERE estado = 'finalizado') AS efectivo_hoy,
                       (SELECT count(*) FROM servicios WHERE estado IN ('asignado', 'en_origen', 'en_curso')) AS en_curso,
                       (SELECT count(*) FROM servicios WHERE estado = 'buscando') AS buscando,
                       (SELECT count(*) FROM servicios WHERE estado = 'buscando' AND solicitado_en < now() - interval '2 minutes') AS buscando_demorados,
                       (SELECT count(*) FROM v_conductores_en_linea
                         WHERE actualizado_en > now() - make_interval(secs => fn_config_num('ubicacion_vigencia_seg', 300)::int)) AS conductores_conectados,
                       (SELECT count(*) FROM v_conductores_en_linea WHERE ocupado) AS conductores_ocupados,
                       (SELECT count(*) FROM conductores WHERE estado = 'pendiente') AS conductores_pendientes,
                       (SELECT count(*) FROM v_documentos_por_vencer) AS documentos_por_vencer,
                       (SELECT count(*) FROM alertas_sos WHERE estado IN ('abierta', 'en_atencion')) AS sos_abiertas,
                       (SELECT count(*) FROM alertas_viaje WHERE respuesta <> 'estoy_bien' AND revisada_en IS NULL) AS alertas_viaje_pendientes,
                       (SELECT count(*) FROM tickets_soporte WHERE estado IN ('abierto', 'en_proceso')) AS pqrs_abiertas,
                       (SELECT count(*) FROM calificaciones WHERE puntuacion <= 2 AND creado_en >= now() - interval '7 days') AS calificaciones_bajas_semana,
                       (SELECT count(*) FROM usuarios u WHERE u.estado = 'activo'
                         AND EXISTS (SELECT 1 FROM usuario_roles ur WHERE ur.usuario_id = u.id AND ur.rol = 'cliente')) AS clientes_activos,
                       (SELECT count(*) FROM conductores WHERE estado = 'aprobado') AS conductores_aprobados,
                       fn_config_num('lluvia_activa', 0) = 1 AS lluvia_activa
                """.formatted(HOY)));
        r.put("por_hora", db.queryForList("""
                SELECT EXTRACT(HOUR FROM solicitado_en AT TIME ZONE 'America/Bogota')::int AS hora, count(*) AS total
                FROM servicios WHERE solicitado_en >= %s GROUP BY 1 ORDER BY 1
                """.formatted(HOY)));
        r.put("ultimos_7_dias", db.queryForList("""
                SELECT (solicitado_en AT TIME ZONE 'America/Bogota')::date AS fecha, count(*) AS total,
                       count(*) FILTER (WHERE estado = 'finalizado') AS finalizados,
                       count(*) FILTER (WHERE estado IN ('cancelado', 'expirado')) AS perdidos
                FROM servicios WHERE solicitado_en >= %s - interval '6 days'
                GROUP BY 1 ORDER BY 1
                """.formatted(HOY)));
        return r;
    }

    // ═════════════════════════════ 4. Supervisión de solicitudes ═════════════════════════════

    /**
     * Solicitudes con filtros y paginación en la base. q busca por número (#123), cliente, conductor o placa.
     * desde/hasta: fechas yyyy-MM-dd en hora de Colombia (incluye el día "hasta").
     */
    public Map<String, Object> buscarServicios(String tipoServicio, String tipoVehiculo, String estado, String desde,
            String hasta, String q, int pagina, int tamano) {
        Pagina p = Pagina.de(pagina, tamano);
        StringBuilder w = new StringBuilder(" WHERE true");
        List<Object> args = new ArrayList<>();
        if (Datos.vacioANull(tipoServicio) != null) {
            w.append(" AND s.tipo_servicio = ?");
            args.add(tipoServicio.trim().toLowerCase());
        }
        if (Datos.vacioANull(tipoVehiculo) != null) {
            w.append(" AND s.tipo_vehiculo = ?");
            args.add(tipoVehiculo.trim().toLowerCase());
        }
        if (Datos.vacioANull(estado) != null) {
            String e = estado.trim().toLowerCase();
            if ("activos".equals(e)) {
                w.append(" AND s.estado IN ('buscando', 'asignado', 'en_origen', 'en_curso')");
            } else {
                if (!ESTADOS.contains(e)) throw new ReglaNegocioException(400, "Estado no válido");
                w.append(" AND s.estado = ?");
                args.add(e);
            }
        }
        LocalDate d = fecha(desde);
        LocalDate h = fecha(hasta);
        if (d != null) {
            w.append(" AND s.solicitado_en >= (?::date AT TIME ZONE 'America/Bogota')");
            args.add(d.toString());
        }
        if (h != null) {
            w.append(" AND s.solicitado_en < ((?::date + 1) AT TIME ZONE 'America/Bogota')");
            args.add(h.toString());
        }
        String texto = Datos.vacioANull(q);
        if (texto != null) {
            String id = texto.replace("#", "").trim();
            if (id.matches("\\d{1,12}")) {
                w.append(" AND s.id = ?");
                args.add(Long.parseLong(id));
            } else {
                w.append("""
                         AND (cli.nombres || ' ' || COALESCE(cli.apellidos, '') ILIKE ?
                              OR con.nombres || ' ' || COALESCE(con.apellidos, '') ILIKE ?
                              OR ve.placa ILIKE ? OR s.destino_direccion ILIKE ? OR s.origen_direccion ILIKE ?)
                        """);
                String like = "%" + texto.replace("%", "").replace("_", "") + "%";
                for (int i = 0; i < 5; i++) args.add(like);
            }
        }
        String desdeSql = """
                FROM servicios s
                JOIN usuarios cli ON cli.id = s.cliente_id
                JOIN estados e ON e.ambito = 'servicio' AND e.codigo = s.estado
                LEFT JOIN usuarios con ON con.id = s.conductor_id
                LEFT JOIN vehiculos ve ON ve.id = s.vehiculo_id
                LEFT JOIN motivos_cancelacion mc ON mc.id = s.motivo_cancelacion_id
                """ + w;
        long total = db.queryForObject("SELECT count(*) " + desdeSql, Long.class, args.toArray());
        List<Object> conPagina = new ArrayList<>(args);
        conPagina.add(p.tamano());
        conPagina.add(p.desplazamiento());
        List<Map<String, Object>> items = db.queryForList("""
                SELECT s.id, s.tipo_servicio, s.tipo_vehiculo, s.estado AS estado_codigo, e.nombre AS estado_nombre,
                       s.solicitado_en, COALESCE(s.valor_final, s.valor_estimado) AS tarifa,
                       trim(cli.nombres || ' ' || COALESCE(cli.apellidos, '')) AS cliente_nombre,
                       trim(con.nombres || ' ' || COALESCE(con.apellidos, '')) AS conductor_nombre, ve.placa,
                       s.origen_direccion AS origen, s.destino_direccion AS destino, mc.descripcion AS motivo_cancelacion,
                       (EXTRACT(EPOCH FROM now() - s.solicitado_en) / 60)::int AS minutos
                """ + desdeSql + " ORDER BY s.solicitado_en DESC LIMIT ? OFFSET ?", conPagina.toArray());
        return resultado(items, total, p);
    }

    /**
     * Reasignar: el conductor no avanza (no llega, no contesta). El servicio vuelve a "buscando" con el mismo
     * precio, se ofrece a otros conductores y no se le cuenta como cancelación al conductor (V16). Solo desde
     * asignado o en la recogida: un viaje en curso no se reasigna (el pasajero va adentro).
     */
    @Transactional
    public Map<String, Object> reasignar(Long servicioId, String motivo) {
        String m = exigirMotivo(motivo);
        Map<String, Object> s = db.queryForList(
                "SELECT estado, cliente_id, conductor_id FROM servicios WHERE id = ? FOR UPDATE", servicioId)
                .stream().findFirst().orElseThrow(() -> new RecursoNoEncontradoException("Servicio no encontrado"));
        String estado = String.valueOf(s.get("estado"));
        if (!"asignado".equals(estado) && !"en_origen".equals(estado)) {
            throw new ReglaNegocioException(400, "Solo se reasigna un servicio que tiene conductor y todavía no empezó el viaje.");
        }
        Long conductor = Datos.aLong(s.get("conductor_id"));
        actor.declarar(sesion.usuarioId(), sesion.actorPersonal());
        int n = db.update("UPDATE servicios SET estado = 'buscando' WHERE id = ? AND estado = ?", servicioId, estado);
        if (n == 0) throw new ReglaNegocioException("El servicio cambió de estado. Recarga e intenta de nuevo.");
        int notificados = viajes.ofertar(servicioId);
        avisos.avisar(Datos.aLong(s.get("cliente_id")), servicioId, NotificacionServicio.ESTADO, "buscando",
                "Te buscamos otro conductor", "Soporte de MoviFY reasignó tu servicio. El precio no cambia.");
        avisos.avisar(conductor, servicioId, NotificacionServicio.ESTADO, "cancelado",
                "Soporte reasignó el servicio", "Ya no tienes que ir. No cuenta como cancelación tuya.");
        admin.auditar("servicio_reasignado", "servicios", servicioId, Map.of("conductor_anterior", conductor, "motivo", m));
        return Map.of("message", "Servicio reasignado. Les avisamos a " + notificados + " conductores cercanos.",
                "conductores_notificados", notificados);
    }

    /** Cancelar desde el panel: siempre con motivo escrito y queda en la auditoría (además del historial de estados). */
    public void auditarCancelacion(Long servicioId, Short motivoId, String motivo) {
        admin.auditar("servicio_cancelado", "servicios", servicioId,
                Map.of("motivo_id", motivoId == null ? "" : motivoId, "motivo", Datos.nvl(motivo)));
    }

    // ═════════════════════════════ 3. Clientes y usuarios ═════════════════════════════

    /** Usuarios con filtros y paginación; teléfono y correo parciales (completos en el detalle). */
    public Map<String, Object> buscarUsuarios(String q, String rol, String estado, int pagina, int tamano) {
        Pagina p = Pagina.de(pagina, tamano);
        StringBuilder w = new StringBuilder(" WHERE u.estado <> 'eliminado'");
        List<Object> args = new ArrayList<>();
        String texto = Datos.vacioANull(q);
        if (texto != null) {
            String tel = Celular.normalizar(texto);
            if (tel != null) {
                w.append(" AND u.telefono = ?");
                args.add(tel);
            } else {
                w.append(" AND (u.nombres || ' ' || COALESCE(u.apellidos, '') ILIKE ? OR u.email ILIKE ?)");
                String like = "%" + texto.replace("%", "").replace("_", "") + "%";
                args.add(like);
                args.add(like);
            }
        }
        if (Datos.vacioANull(estado) != null) {
            w.append(" AND u.estado = ?");
            args.add(estado.trim().toLowerCase());
        }
        if (Datos.vacioANull(rol) != null) {
            String r = rol.trim().toLowerCase();
            if ("aspirante".equals(r)) {
                w.append(" AND u.registrado_como = 'conductor' AND NOT EXISTS (SELECT 1 FROM usuario_roles x WHERE x.usuario_id = u.id AND x.rol = 'conductor')");
            } else if ("equipo".equals(r)) {
                w.append(" AND EXISTS (SELECT 1 FROM usuario_roles x WHERE x.usuario_id = u.id AND x.rol IN ('admin', 'soporte'))");
            } else {
                w.append(" AND EXISTS (SELECT 1 FROM usuario_roles x WHERE x.usuario_id = u.id AND x.rol = ?)");
                args.add(r);
            }
        }
        long total = db.queryForObject("SELECT count(*) FROM usuarios u" + w, Long.class, args.toArray());
        List<Object> conPagina = new ArrayList<>(args);
        conPagina.add(p.tamano());
        conPagina.add(p.desplazamiento());
        List<Map<String, Object>> filas = db.queryForList("""
                SELECT u.id, trim(u.nombres || ' ' || COALESCE(u.apellidos, '')) AS nombre, u.email, u.telefono,
                       u.telefono_verificado, u.estado, u.motivo_bloqueo, u.calificacion_promedio, u.creado_en, u.ultimo_acceso,
                       u.foto_url AS foto, u.registrado_como,
                       COALESCE((SELECT string_agg(ur.rol, ',' ORDER BY ur.rol) FROM usuario_roles ur WHERE ur.usuario_id = u.id), '') AS roles,
                       (SELECT count(*) FROM servicios s WHERE s.cliente_id = u.id) AS servicios_pedidos
                FROM usuarios u""" + w + " ORDER BY u.creado_en DESC LIMIT ? OFFSET ?", conPagina.toArray());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> f : filas) {
            Map<String, Object> u = new LinkedHashMap<>(f);
            u.put("telefono", Datos.ocultarTelefono((String) f.get("telefono")));
            u.put("correo", Datos.ocultarCorreo((String) f.get("email")));
            u.remove("email");
            items.add(u);
        }
        return resultado(items, total, p);
    }

    /** Perfil completo de un usuario para soporte: datos, actividad, calificaciones recibidas, PQRS y bloqueos. */
    public Map<String, Object> detalleUsuario(Long id) {
        Map<String, Object> u = new HashMap<>(db.queryForList("""
                SELECT u.id, u.nombres, u.apellidos, trim(u.nombres || ' ' || COALESCE(u.apellidos, '')) AS nombre,
                       u.email AS correo, u.telefono, u.telefono_verificado, u.estado, u.motivo_bloqueo, u.foto_url AS foto,
                       u.calificacion_promedio, u.total_calificaciones, u.creado_en, u.ultimo_acceso, u.registrado_como,
                       COALESCE((SELECT string_agg(ur.rol, ',' ORDER BY ur.rol) FROM usuario_roles ur WHERE ur.usuario_id = u.id), '') AS roles,
                       EXISTS (SELECT 1 FROM conductores c WHERE c.usuario_id = u.id) AS es_conductor
                FROM usuarios u WHERE u.id = ? AND u.estado <> 'eliminado'
                """, id).stream().findFirst().orElseThrow(() -> new RecursoNoEncontradoException("Usuario no encontrado")));
        u.put("actividad", db.queryForMap("""
                SELECT count(*) AS pedidos,
                       count(*) FILTER (WHERE estado = 'finalizado') AS finalizados,
                       count(*) FILTER (WHERE estado = 'cancelado' AND cancelado_por = cliente_id) AS cancelados_por_el,
                       COALESCE(sum(valor_final) FILTER (WHERE estado = 'finalizado'), 0) AS pagado
                FROM servicios WHERE cliente_id = ?
                """, id));
        u.put("servicios", db.queryForList("""
                SELECT s.id, s.tipo_servicio, s.tipo_vehiculo, s.estado AS estado_codigo, e.nombre AS estado_nombre,
                       s.solicitado_en, COALESCE(s.valor_final, s.valor_estimado) AS tarifa,
                       CASE WHEN s.cliente_id = ? THEN 'cliente' ELSE 'conductor' END AS como,
                       s.origen_direccion AS origen, s.destino_direccion AS destino
                FROM servicios s JOIN estados e ON e.ambito = 'servicio' AND e.codigo = s.estado
                WHERE s.cliente_id = ? OR s.conductor_id = ?
                ORDER BY s.solicitado_en DESC LIMIT 20
                """, id, id, id));
        u.put("calificaciones", db.queryForList("""
                SELECT c.servicio_id, c.puntuacion, c.comentario, c.creado_en,
                       trim(x.nombres || ' ' || COALESCE(x.apellidos, '')) AS de
                FROM calificaciones c JOIN usuarios x ON x.id = c.calificador_id
                WHERE c.calificado_id = ? ORDER BY c.creado_en DESC LIMIT 10
                """, id));
        u.put("pqrs", db.queryForList("""
                SELECT t.id, t.tipo, t.asunto, t.estado, t.creado_en FROM tickets_soporte t
                WHERE t.usuario_id = ? ORDER BY t.creado_en DESC LIMIT 10
                """, id));
        u.put("reportes_en_contra", db.queryForList("""
                SELECT t.id, t.tipo, t.asunto, t.estado, t.creado_en, trim(r.nombres || ' ' || COALESCE(r.apellidos, '')) AS reporta
                FROM tickets_soporte t
                JOIN servicios s ON s.id = t.servicio_id
                JOIN usuarios r ON r.id = t.usuario_id
                WHERE t.usuario_id <> ? AND (s.cliente_id = ? OR s.conductor_id = ?)
                ORDER BY t.creado_en DESC LIMIT 10
                """, id, id, id));
        u.put("historial_estado", db.queryForList("""
                SELECT a.accion, a.detalle, a.creado_en, trim(x.nombres || ' ' || COALESCE(x.apellidos, '')) AS por
                FROM auditoria_admin a LEFT JOIN usuarios x ON x.id = a.admin_id
                WHERE a.entidad = 'usuarios' AND a.entidad_id = ? ORDER BY a.creado_en DESC LIMIT 20
                """, id));
        return u;
    }

    // ═════════════════════════════ 2. Conductores ═════════════════════════════

    /** Conductores con filtros en la base (estado, tipo, conectado) y datos personales parciales. */
    public Map<String, Object> buscarConductores(String estado, String tipoVehiculo, Boolean conectado, String q, int pagina, int tamano) {
        Pagina p = Pagina.de(pagina, tamano);
        StringBuilder w = new StringBuilder(" WHERE true");
        List<Object> args = new ArrayList<>();
        if (Datos.vacioANull(estado) != null) {
            w.append(" AND c.estado = ?");
            args.add(estado.trim().toLowerCase());
        }
        if (Datos.vacioANull(tipoVehiculo) != null) {
            w.append(" AND v.tipo_vehiculo = ?");
            args.add(tipoVehiculo.trim().toLowerCase());
        }
        if (conectado != null) {
            w.append(" AND c.disponible = ?");
            args.add(conectado);
        }
        String texto = Datos.vacioANull(q);
        if (texto != null) {
            w.append(" AND (u.nombres || ' ' || COALESCE(u.apellidos, '') ILIKE ? OR v.placa ILIKE ? OR u.telefono LIKE ?)");
            String like = "%" + texto.replace("%", "").replace("_", "") + "%";
            args.add(like);
            args.add(like);
            String digitos = texto.replaceAll("\\D", "");
            args.add(digitos.length() >= 4 ? "%" + digitos + "%" : "-");
        }
        String desde = """
                FROM conductores c
                JOIN usuarios u ON u.id = c.usuario_id
                JOIN estados e ON e.ambito = 'conductor' AND e.codigo = c.estado
                LEFT JOIN vehiculos v ON v.conductor_id = c.usuario_id AND v.activo
                """ + w;
        long total = db.queryForObject("SELECT count(*) " + desde, Long.class, args.toArray());
        List<Object> conPagina = new ArrayList<>(args);
        conPagina.add(p.tamano());
        conPagina.add(p.desplazamiento());
        List<Map<String, Object>> filas = db.queryForList("""
                SELECT c.usuario_id AS conductor_id, trim(u.nombres || ' ' || COALESCE(u.apellidos, '')) AS nombre,
                       u.telefono, c.numero_identificacion, v.placa, v.tipo_vehiculo,
                       trim(v.marca || ' ' || COALESCE(v.linea, '') || ' ' || v.modelo_anio) AS modelo,
                       c.estado, e.nombre AS estado_nombre, c.disponible AS en_linea, c.motivo_estado,
                       c.calificacion_promedio AS calificacion, c.total_servicios, c.total_cancelaciones, c.creado_en,
                       (SELECT count(*) FROM documentos_conductor d WHERE d.conductor_id = c.usuario_id AND d.vigente
                          AND d.estado = 'pendiente') AS documentos_por_revisar,
                       (SELECT count(*) FROM v_documentos_por_vencer x WHERE x.conductor_id = c.usuario_id) AS documentos_por_vencer
                """ + desde + " ORDER BY (c.estado = 'pendiente') DESC, c.creado_en DESC LIMIT ? OFFSET ?", conPagina.toArray());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> f : filas) {
            Map<String, Object> c = new LinkedHashMap<>(f);
            c.put("telefono", Datos.ocultarTelefono((String) f.get("telefono")));
            c.put("documento", Datos.ocultarDocumento((String) f.get("numero_identificacion")));
            c.remove("numero_identificacion");
            items.add(c);
        }
        Map<String, Object> conteos = db.queryForMap("""
                SELECT count(*) AS todos, count(*) FILTER (WHERE estado = 'pendiente') AS pendiente,
                       count(*) FILTER (WHERE estado = 'aprobado') AS aprobado, count(*) FILTER (WHERE estado = 'rechazado') AS rechazado,
                       count(*) FILTER (WHERE estado = 'suspendido') AS suspendido
                FROM conductores
                """);
        Map<String, Object> r = new HashMap<>(resultado(items, total, p));
        r.put("conteos", conteos);
        return r;
    }

    /**
     * Lo que el detalle del conductor suma a lo de siempre: el checklist de SU tipo de vehículo (un taxista necesita
     * tarjeta de operación y control; un motociclista no), su actividad, las últimas calificaciones y los cambios de estado.
     */
    public Map<String, Object> expedienteConductor(Long id) {
        Map<String, Object> c = db.queryForList("""
                SELECT v.tipo_vehiculo, v.modelo_anio FROM conductores c
                LEFT JOIN vehiculos v ON v.conductor_id = c.usuario_id AND v.activo WHERE c.usuario_id = ?
                """, id).stream().findFirst().orElseThrow(() -> new RecursoNoEncontradoException("Conductor no encontrado"));
        String tipo = (String) c.get("tipo_vehiculo");
        List<Map<String, Object>> requisitos = db.queryForList("""
                SELECT td.codigo, td.nombre, td.aplica_a, td.requiere_vencimiento,
                       CASE WHEN CAST(? AS varchar) = 'taxi' THEN td.obligatorio_taxi ELSE td.obligatorio_moto END
                         AND (td.exigir_desde_anios IS NULL OR CAST(? AS int) IS NULL
                              OR extract(year FROM CURRENT_DATE)::int - CAST(? AS int) >= td.exigir_desde_anios) AS obligatorio,
                       td.exigir_desde_anios, d.id AS documento_id, d.estado, d.fecha_vencimiento, d.observacion,
                       (d.fecha_vencimiento - CURRENT_DATE) AS dias_para_vencer
                FROM tipos_documento td
                LEFT JOIN documentos_conductor d ON d.conductor_id = ? AND d.tipo_documento = td.codigo AND d.vigente
                WHERE CAST(? AS varchar) IS NULL OR (CASE WHEN CAST(? AS varchar) = 'taxi' THEN td.obligatorio_taxi ELSE td.obligatorio_moto END)
                   OR d.id IS NOT NULL
                ORDER BY td.aplica_a, td.orden, td.nombre
                """, tipo, c.get("modelo_anio"), c.get("modelo_anio"), id, tipo, tipo);
        long faltan = requisitos.stream().filter(r -> Boolean.TRUE.equals(r.get("obligatorio"))
                && !"aprobado".equals(r.get("estado"))).count();
        Map<String, Object> r = new HashMap<>();
        r.put("tipo_vehiculo", tipo);
        r.put("requisitos", requisitos);
        r.put("obligatorios_sin_aprobar", faltan);
        r.put("actividad", db.queryForMap("""
                SELECT c.total_servicios, c.total_cancelaciones, c.calificacion_promedio,
                       CASE WHEN c.total_servicios + c.total_cancelaciones = 0 THEN 0
                            ELSE round(c.total_cancelaciones * 100.0 / (c.total_servicios + c.total_cancelaciones), 1) END AS tasa_cancelacion,
                       (SELECT COALESCE(sum(valor_final), 0) FROM servicios s WHERE s.conductor_id = c.usuario_id AND s.estado = 'finalizado'
                          AND s.finalizado_en >= now() - interval '30 days') AS cobrado_30_dias
                FROM conductores c WHERE c.usuario_id = ?
                """, id));
        r.put("servicios", db.queryForList("""
                SELECT s.id, s.tipo_servicio, s.estado AS estado_codigo, e.nombre AS estado_nombre, s.solicitado_en,
                       COALESCE(s.valor_final, s.valor_estimado) AS tarifa, s.destino_direccion AS destino
                FROM servicios s JOIN estados e ON e.ambito = 'servicio' AND e.codigo = s.estado
                WHERE s.conductor_id = ? ORDER BY s.solicitado_en DESC LIMIT 15
                """, id));
        r.put("calificaciones", db.queryForList("""
                SELECT c.servicio_id, c.puntuacion, c.comentario, c.creado_en FROM calificaciones c
                WHERE c.calificado_id = ? ORDER BY c.creado_en DESC LIMIT 10
                """, id));
        r.put("historial_estado", db.queryForList("""
                SELECT a.accion, a.detalle, a.creado_en, trim(x.nombres || ' ' || COALESCE(x.apellidos, '')) AS por
                FROM auditoria_admin a LEFT JOIN usuarios x ON x.id = a.admin_id
                WHERE a.entidad IN ('conductores', 'usuarios') AND a.entidad_id = ? ORDER BY a.creado_en DESC LIMIT 20
                """, id));
        return r;
    }

    // ═════════════════════════════ 8. Reportes ═════════════════════════════

    public Map<String, Object> reportes(String desde, String hasta) {
        LocalDate[] r = rango(desde, hasta);
        Object[] a = { r[0].toString(), r[1].toString() };
        String periodo = " s.solicitado_en >= (?::date AT TIME ZONE 'America/Bogota') AND s.solicitado_en < ((?::date + 1) AT TIME ZONE 'America/Bogota')";
        Map<String, Object> out = new HashMap<>();
        out.put("desde", r[0].toString());
        out.put("hasta", r[1].toString());
        out.put("totales", db.queryForMap("""
                SELECT count(*) AS solicitudes,
                       count(*) FILTER (WHERE estado = 'finalizado') AS finalizados,
                       count(*) FILTER (WHERE estado = 'cancelado') AS cancelados,
                       count(*) FILTER (WHERE estado = 'expirado') AS sin_conductor,
                       round(100.0 * count(*) FILTER (WHERE estado IN ('cancelado', 'expirado')) / NULLIF(count(*), 0), 1) AS tasa_perdida,
                       count(*) FILTER (WHERE estado = 'cancelado' AND cancelado_por = cliente_id) AS cancelados_cliente,
                       count(*) FILTER (WHERE estado = 'cancelado' AND cancelado_por = conductor_id) AS cancelados_conductor,
                       count(*) FILTER (WHERE estado = 'cancelado' AND cancelado_por NOT IN (cliente_id, COALESCE(conductor_id, 0))) AS cancelados_personal,
                       round(avg(EXTRACT(EPOCH FROM asignado_en - solicitado_en)) FILTER (WHERE asignado_en IS NOT NULL) / 60, 1) AS min_aceptacion,
                       round((percentile_cont(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM asignado_en - solicitado_en)))::numeric / 60, 1) AS min_aceptacion_mediana,
                       round(avg(EXTRACT(EPOCH FROM llegada_origen_en - asignado_en)) FILTER (WHERE llegada_origen_en IS NOT NULL) / 60, 1) AS min_llegada,
                       round(avg(EXTRACT(EPOCH FROM finalizado_en - iniciado_en)) FILTER (WHERE estado = 'finalizado') / 60, 1) AS min_viaje,
                       COALESCE(sum(valor_final) FILTER (WHERE estado = 'finalizado'), 0) AS efectivo,
                       round(avg(valor_final) FILTER (WHERE estado = 'finalizado'), 0) AS ticket_promedio
                FROM servicios s WHERE """ + periodo, a));
        out.put("por_tipo", db.queryForList("""
                SELECT tipo_servicio, tipo_vehiculo, count(*) AS total, count(*) FILTER (WHERE estado = 'finalizado') AS finalizados,
                       count(*) FILTER (WHERE estado IN ('cancelado', 'expirado')) AS perdidos
                FROM servicios s WHERE """ + periodo + " GROUP BY 1, 2 ORDER BY 3 DESC", a));
        out.put("por_dia", db.queryForList("""
                SELECT (solicitado_en AT TIME ZONE 'America/Bogota')::date AS fecha, count(*) AS total,
                       count(*) FILTER (WHERE estado = 'finalizado') AS finalizados
                FROM servicios s WHERE """ + periodo + " GROUP BY 1 ORDER BY 1", a));
        out.put("por_hora", db.queryForList("""
                SELECT EXTRACT(HOUR FROM solicitado_en AT TIME ZONE 'America/Bogota')::int AS hora, count(*) AS total
                FROM servicios s WHERE """ + periodo + " GROUP BY 1 ORDER BY 1", a));
        out.put("motivos_cancelacion", db.queryForList("""
                SELECT mc.descripcion, mc.actor, count(*) AS total FROM servicios s JOIN motivos_cancelacion mc ON mc.id = s.motivo_cancelacion_id
                WHERE s.estado IN ('cancelado', 'expirado') AND """ + periodo + " GROUP BY 1, 2 ORDER BY 3 DESC LIMIT 10", a));
        out.put("top_conductores", db.queryForList("""
                SELECT s.conductor_id, trim(u.nombres || ' ' || COALESCE(u.apellidos, '')) AS nombre, v.placa, v.tipo_vehiculo,
                       count(*) FILTER (WHERE s.estado = 'finalizado') AS finalizados, COALESCE(sum(s.valor_final), 0) AS cobrado,
                       c.calificacion_promedio AS calificacion
                FROM servicios s JOIN usuarios u ON u.id = s.conductor_id JOIN conductores c ON c.usuario_id = s.conductor_id
                LEFT JOIN vehiculos v ON v.conductor_id = s.conductor_id AND v.activo
                WHERE s.conductor_id IS NOT NULL AND """ + periodo + """
                 GROUP BY s.conductor_id, u.nombres, u.apellidos, v.placa, v.tipo_vehiculo, c.calificacion_promedio
                 ORDER BY finalizados DESC LIMIT 10
                """, a));
        return out;
    }

    /** Servicios del periodo en CSV para Excel (punto y coma, UTF-8 con BOM y sin fórmulas inyectadas). */
    public String csvServicios(String desde, String hasta) {
        LocalDate[] r = rango(desde, hasta);
        List<Map<String, Object>> filas = db.queryForList("""
                SELECT s.id, to_char(s.solicitado_en AT TIME ZONE 'America/Bogota', 'YYYY-MM-DD HH24:MI') AS fecha,
                       s.tipo_servicio, s.tipo_vehiculo, e.nombre AS estado,
                       trim(cli.nombres || ' ' || COALESCE(cli.apellidos, '')) AS cliente,
                       trim(con.nombres || ' ' || COALESCE(con.apellidos, '')) AS conductor, ve.placa,
                       s.origen_direccion, s.destino_direccion, s.distancia_km, COALESCE(s.valor_final, s.valor_estimado) AS valor,
                       mc.descripcion AS motivo_cancelacion
                FROM servicios s
                JOIN usuarios cli ON cli.id = s.cliente_id
                JOIN estados e ON e.ambito = 'servicio' AND e.codigo = s.estado
                LEFT JOIN usuarios con ON con.id = s.conductor_id
                LEFT JOIN vehiculos ve ON ve.id = s.vehiculo_id
                LEFT JOIN motivos_cancelacion mc ON mc.id = s.motivo_cancelacion_id
                WHERE s.solicitado_en >= (?::date AT TIME ZONE 'America/Bogota') AND s.solicitado_en < ((?::date + 1) AT TIME ZONE 'America/Bogota')
                ORDER BY s.solicitado_en LIMIT 50000
                """, r[0].toString(), r[1].toString());
        StringBuilder b = new StringBuilder("﻿");
        b.append("Servicio;Fecha;Tipo;Vehículo;Estado;Cliente;Conductor;Placa;Origen;Destino;Km;Valor;Motivo cancelación\r\n");
        for (Map<String, Object> f : filas) {
            b.append(String.join(";", celda(f.get("id")), celda(f.get("fecha")), celda(f.get("tipo_servicio")),
                    celda(f.get("tipo_vehiculo")), celda(f.get("estado")), celda(f.get("cliente")), celda(f.get("conductor")),
                    celda(f.get("placa")), celda(f.get("origen_direccion")), celda(f.get("destino_direccion")),
                    celda(f.get("distancia_km")), celda(f.get("valor")), celda(f.get("motivo_cancelacion")))).append("\r\n");
        }
        admin.auditar("reporte_exportado", "servicios", 0L, Map.of("desde", r[0].toString(), "hasta", r[1].toString(), "filas", filas.size()));
        return b.toString();
    }

    /** Una celda CSV segura: entre comillas y sin que Excel la tome como fórmula (=, +, -, @). */
    static String celda(Object v) {
        if (v == null) return "";
        String t = String.valueOf(v).replace("\r", " ").replace("\n", " ");
        if (!t.isEmpty() && "=+-@\t".indexOf(t.charAt(0)) >= 0 && !t.matches("-?\\d+(\\.\\d+)?")) t = "'" + t;
        return "\"" + t.replace("\"", "\"\"") + "\"";
    }

    // ═════════════════════════════ 9. Calificaciones bajas ═════════════════════════════

    public Map<String, Object> calificaciones(int maximo, String a, int pagina, int tamano) {
        Pagina p = Pagina.de(pagina, tamano);
        int max = Math.min(Math.max(maximo, 1), 5);
        // a: 'conductor' = calificaciones que recibieron conductores; 'cliente' = las que recibieron pasajeros
        String filtro = "conductor".equals(a) ? " AND s.conductor_id = c.calificado_id"
                : "cliente".equals(a) ? " AND s.cliente_id = c.calificado_id" : "";
        String desde = """
                FROM calificaciones c JOIN servicios s ON s.id = c.servicio_id
                JOIN usuarios de ON de.id = c.calificador_id JOIN usuarios para ON para.id = c.calificado_id
                WHERE c.puntuacion <= ?""" + filtro;
        long total = db.queryForObject("SELECT count(*) " + desde, Long.class, max);
        List<Map<String, Object>> items = db.queryForList("""
                SELECT c.id, c.servicio_id, c.puntuacion, c.comentario, c.creado_en, s.tipo_servicio, s.tipo_vehiculo,
                       c.calificado_id, trim(para.nombres || ' ' || COALESCE(para.apellidos, '')) AS calificado_nombre,
                       CASE WHEN s.conductor_id = c.calificado_id THEN 'conductor' ELSE 'cliente' END AS calificado_rol,
                       para.calificacion_promedio AS promedio_calificado, para.estado AS estado_calificado,
                       trim(de.nombres || ' ' || COALESCE(de.apellidos, '')) AS calificador_nombre
                """ + desde + " ORDER BY c.creado_en DESC LIMIT ? OFFSET ?", max, p.tamano(), p.desplazamiento());
        return resultado(items, total, p);
    }

    // ═════════════════════════════ 7. Equipo interno y permisos ═════════════════════════════

    /**
     * Agregar a alguien al equipo: la persona ya tiene cuenta (se registró en la app o en la web) y aquí se le da
     * el rol. No se crean cuentas con claves que alguien más conoce.
     */
    @Transactional
    public Map<String, Object> agregarAlEquipo(String identificador, String rol) {
        if (!Set.of("soporte", "admin").contains(rol)) throw new ReglaNegocioException(400, "El equipo es soporte o admin");
        String texto = Datos.vacioANull(identificador);
        if (texto == null) throw new ReglaNegocioException(400, "Escribe el celular o el correo de la persona");
        String tel = Celular.normalizar(texto);
        List<Map<String, Object>> u = tel != null
                ? db.queryForList("SELECT id, trim(nombres || ' ' || COALESCE(apellidos, '')) AS nombre, estado FROM usuarios WHERE telefono = ?", tel)
                : db.queryForList("SELECT id, trim(nombres || ' ' || COALESCE(apellidos, '')) AS nombre, estado FROM usuarios WHERE lower(email) = lower(?)", texto);
        if (u.isEmpty()) {
            throw new RecursoNoEncontradoException("Esa persona no tiene cuenta en MoviFY. Pídele que se registre y luego agrégala aquí.");
        }
        Map<String, Object> p = u.get(0);
        if (!"activo".equals(p.get("estado"))) throw new ReglaNegocioException(400, "La cuenta de esa persona no está activa");
        admin.asignarRol(Datos.aLong(p.get("id")), rol, false);
        return Map.of("id", p.get("id"), "nombre", p.get("nombre"),
                "message", p.get("nombre") + " ahora es " + rol + ". Lo verá al volver a iniciar sesión.");
    }

    /** Matriz rol × permiso. Solo los permisos de soporte se cambian desde aquí. */
    public Map<String, Object> permisos() {
        return Map.of(
                "permisos", db.queryForList("""
                        SELECT codigo, modulo, accion, descripcion, es_sensible FROM permisos WHERE activo
                          AND modulo NOT IN ('inicio', 'perfil')
                        ORDER BY modulo, codigo
                        """),
                "asignados", db.queryForList("SELECT rol, permiso FROM rol_permisos WHERE rol IN ('admin', 'soporte')"),
                "editables", ROLES_EDITABLES);
    }

    @Transactional
    public Map<String, Object> cambiarPermiso(String rol, String permiso, boolean activo) {
        if (!ROLES_EDITABLES.contains(rol)) {
            throw new ReglaNegocioException(400, "Solo se pueden ajustar los permisos de soporte. Los de admin son fijos.");
        }
        Boolean existe = db.queryForObject("SELECT EXISTS (SELECT 1 FROM permisos WHERE codigo = ? AND activo)", Boolean.class, permiso);
        if (!Boolean.TRUE.equals(existe)) throw new RecursoNoEncontradoException("Ese permiso no existe");
        actor.declarar(sesion.usuarioId(), ActorBd.ADMIN);
        if (activo) {
            db.update("INSERT INTO rol_permisos (rol, permiso, asignado_por) VALUES (?, ?, ?) ON CONFLICT DO NOTHING", rol, permiso,
                    sesion.usuarioId());
        } else {
            db.update("DELETE FROM rol_permisos WHERE rol = ? AND permiso = ?", rol, permiso);
        }
        admin.auditar(activo ? "permiso_dado" : "permiso_quitado", "rol_permisos", 0L, Map.of("rol", rol, "permiso", permiso));
        return Map.of("message", "Permiso actualizado. Aplica cuando cada persona de " + rol + " vuelva a iniciar sesión.");
    }

    // ═════════════════════════════ 6. Catálogos de operación ═════════════════════════════

    /** Vehículos, servicios, qué vehículo hace qué servicio y los límites de domicilios y encomiendas. */
    public Map<String, Object> catalogoOperacion() {
        return Map.of(
                "tipos_vehiculo", db.queryForList("SELECT * FROM tipos_vehiculo ORDER BY codigo"),
                "tipos_servicio", db.queryForList("SELECT * FROM tipos_servicio ORDER BY codigo"),
                "permitidos", db.queryForList("SELECT tipo_servicio, tipo_vehiculo FROM servicio_vehiculo_permitido ORDER BY 1, 2"),
                "limites", db.queryForList("""
                        SELECT clave, valor, descripcion FROM configuracion_sistema
                        WHERE clave IN ('peso_max_moto_kg', 'tope_compra_domicilio', 'max_aumento_tarifa_pct', 'cobertura_radio_km')
                        ORDER BY clave
                        """));
    }

    public Map<String, Object> contenidoProhibido() {
        return Map.of("base", ContenidoProhibido.listaBase(), "permitidos_base", ContenidoProhibido.permitidosBase(),
                "agregadas", db.queryForList("""
                        SELECT p.id, p.palabra, p.categoria, p.tipo, p.creado_en, trim(u.nombres || ' ' || COALESCE(u.apellidos, '')) AS creado_por
                        FROM palabras_prohibidas p LEFT JOIN usuarios u ON u.id = p.creado_por ORDER BY p.tipo, p.categoria, p.palabra
                        """));
    }

    @Transactional
    public Map<String, Object> agregarPalabra(String palabra, String categoria, String tipo) {
        String forma = ContenidoProhibido.forma(palabra);
        if (forma.length() < 3) throw new ReglaNegocioException(400, "Escribe una palabra o frase de al menos 3 letras");
        if (forma.length() > 60) throw new ReglaNegocioException(400, "Máximo 60 letras");
        String t = "permitida".equals(tipo) ? "permitida" : "prohibida";
        String cat = Datos.vacioANull(categoria) == null ? "otros" : Datos.recortar(categoria.trim().toLowerCase(), 40);
        if ("prohibida".equals(t) && ContenidoProhibido.detectar(forma).isPresent()) {
            throw new ReglaNegocioException(400, "Esa palabra ya está bloqueada por la lista base");
        }
        Long id = db.queryForObject("""
                INSERT INTO palabras_prohibidas (palabra, categoria, tipo, creado_por) VALUES (?, ?, ?, ?)
                ON CONFLICT (palabra, tipo) DO UPDATE SET categoria = EXCLUDED.categoria RETURNING id
                """, Long.class, forma, cat, t, sesion.usuarioId());
        admin.auditar("palabra_" + t, "palabras_prohibidas", id, Map.of("palabra", forma, "categoria", cat));
        prohibidas.recargar();
        return Map.of("id", id, "palabra", forma, "message", "permitida".equals(t)
                ? "Listo: «" + forma + "» ya no se bloquea." : "Listo: «" + forma + "» se bloquea desde ya en domicilios y encomiendas.");
    }

    @Transactional
    public void quitarPalabra(Long id) {
        String palabra = db.queryForList("DELETE FROM palabras_prohibidas WHERE id = ? RETURNING palabra", String.class, id)
                .stream().findFirst().orElseThrow(() -> new RecursoNoEncontradoException("Palabra no encontrada"));
        admin.auditar("palabra_quitada", "palabras_prohibidas", id, Map.of("palabra", palabra));
        prohibidas.recargar();
    }

    // ═════════════════════════════ Utilidades ═════════════════════════════

    static String exigirMotivo(String motivo) {
        String m = Datos.vacioANull(motivo);
        if (m == null || m.length() < 5) throw new ReglaNegocioException(400, "Escribe el motivo (al menos 5 letras): queda en la auditoría");
        return Datos.recortar(m, 300);
    }

    private static LocalDate fecha(String texto) {
        if (Datos.vacioANull(texto) == null) return null;
        try {
            return LocalDate.parse(texto.trim());
        } catch (DateTimeParseException e) {
            throw new ReglaNegocioException(400, "La fecha debe tener el formato AAAA-MM-DD");
        }
    }

    /** Periodo de un reporte: por defecto los últimos 30 días; máximo 1 año. */
    private static LocalDate[] rango(String desde, String hasta) {
        LocalDate h = fecha(hasta);
        LocalDate d = fecha(desde);
        if (h == null) h = LocalDate.now(java.time.ZoneId.of("America/Bogota"));
        if (d == null) d = h.minusDays(29);
        if (d.isAfter(h)) throw new ReglaNegocioException(400, "La fecha inicial es posterior a la final");
        if (d.isBefore(h.minusDays(366))) throw new ReglaNegocioException(400, "El periodo máximo es de un año");
        return new LocalDate[] { d, h };
    }
}
