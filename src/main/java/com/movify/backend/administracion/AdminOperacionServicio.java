package com.movify.backend.administracion;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.movify.backend.comun.ActorBd;
import com.movify.backend.comun.Datos;
import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.seguridad.SesionActual;

/**
 * Centro de operación del panel web: lo que pasa ahora mismo en Buenaventura (conductores en línea y
 * servicios activos), las alertas "¿Todo bien?" de los viajes, los viajes programados y las PQRS con
 * todo el contexto (quién reporta, a quién, de qué viaje). Lo de solo lectura no se audita; revisar
 * una alerta sí.
 */
@Service
public class AdminOperacionServicio {

    private final JdbcTemplate db;
    private final SesionActual sesion;
    private final ActorBd actor;
    private final AdminServicio admin;

    public AdminOperacionServicio(JdbcTemplate db, SesionActual sesion, ActorBd actor, AdminServicio admin) {
        this.db = db;
        this.sesion = sesion;
        this.actor = actor;
        this.admin = admin;
    }

    // ═════════════════════════════ En vivo ═════════════════════════════

    /** Mapa de operación: conductores conectados (libres u ocupados), servicios activos y alertas abiertas. */
    public Map<String, Object> enVivo() {
        List<Map<String, Object>> conductores = db.queryForList("""
                SELECT c.usuario_id AS id, trim(u.nombres || ' ' || COALESCE(u.apellidos, '')) AS nombre, u.telefono,
                       v.tipo_vehiculo, v.placa, ub.lat, ub.lng, ub.actualizado_en,
                       EXTRACT(EPOCH FROM now() - ub.actualizado_en)::int AS segundos_sin_reportar,
                       s.id AS servicio_id, s.estado AS servicio_estado
                FROM conductores c
                JOIN usuarios u ON u.id = c.usuario_id
                JOIN ubicacion_conductores ub ON ub.conductor_id = c.usuario_id
                LEFT JOIN vehiculos v ON v.conductor_id = c.usuario_id AND v.activo
                LEFT JOIN servicios s ON s.conductor_id = c.usuario_id AND s.estado IN ('asignado', 'en_origen', 'en_curso')
                WHERE c.disponible AND c.estado = 'aprobado'
                  AND ub.actualizado_en > now() - interval '30 minutes'
                ORDER BY (s.id IS NULL), u.nombres
                """);
        List<Map<String, Object>> servicios = db.queryForList("""
                SELECT v.id, v.tipo_servicio, v.tipo_vehiculo, v.estado_codigo, v.estado_nombre, v.estado_color,
                       v.usuario_nombre AS cliente_nombre, v.usuario_telefono AS cliente_telefono,
                       trim(cu.nombres || ' ' || COALESCE(cu.apellidos, '')) AS conductor_nombre, cu.telefono AS conductor_telefono,
                       v.origen, v.destino, v.origen_lat, v.origen_lng, v.destino_lat, v.destino_lng, v.tarifa,
                       v.valor_sugerido, v.fecha_solicitud, v.expira_busqueda_en, v.pasajero_nombre,
                       (EXTRACT(EPOCH FROM now() - v.solicitado_en) / 60)::int AS minutos,
                       (SELECT count(*) FROM servicio_ofertas o WHERE o.servicio_id = v.id) AS ofertas,
                       EXISTS (SELECT 1 FROM alertas_sos a WHERE a.servicio_id = v.id AND a.estado IN ('abierta', 'en_atencion')) AS con_sos,
                       EXISTS (SELECT 1 FROM alertas_viaje a WHERE a.servicio_id = v.id AND a.respuesta <> 'estoy_bien'
                               AND a.revisada_en IS NULL) AS con_alerta
                FROM v_servicios_api v
                LEFT JOIN usuarios cu ON cu.id = v.conductor_id
                WHERE v.estado_codigo IN ('buscando', 'asignado', 'en_origen', 'en_curso')
                ORDER BY con_sos DESC, v.fecha_solicitud
                """);
        Map<String, Object> contadores = db.queryForMap("""
                SELECT (SELECT count(*) FROM alertas_sos WHERE estado IN ('abierta', 'en_atencion')) AS sos_abiertas,
                       (SELECT count(*) FROM alertas_viaje WHERE respuesta <> 'estoy_bien' AND revisada_en IS NULL) AS alertas_pendientes,
                       (SELECT count(*) FROM viajes_programados WHERE estado = 'pendiente'
                         AND programado_para < now() + interval '24 hours') AS programados_24h,
                       (SELECT count(*) FROM servicios WHERE estado = 'expirado' AND cancelado_en > now() - interval '1 hour') AS expirados_hora
                """);
        return Map.of("conductores", conductores, "servicios", servicios, "contadores", contadores);
    }

    // ═════════════════════════════ ¿Todo bien? ═════════════════════════════

    public List<Map<String, Object>> alertasViaje(boolean soloPendientes) {
        return db.queryForList("""
                SELECT a.id, a.servicio_id, a.tipo, a.respuesta, a.lat, a.lng, a.creado_en, a.revisada_en, a.notas,
                       trim(u.nombres || ' ' || COALESCE(u.apellidos, '')) AS usuario_nombre, u.telefono AS usuario_telefono,
                       s.tipo_vehiculo, s.estado AS servicio_estado, s.origen_direccion AS origen, s.destino_direccion AS destino,
                       trim(c.nombres || ' ' || COALESCE(c.apellidos, '')) AS conductor_nombre, c.telefono AS conductor_telefono,
                       ve.placa, trim(r.nombres || ' ' || COALESCE(r.apellidos, '')) AS revisada_por_nombre
                FROM alertas_viaje a
                JOIN usuarios u ON u.id = a.usuario_id
                JOIN servicios s ON s.id = a.servicio_id
                LEFT JOIN usuarios c ON c.id = s.conductor_id
                LEFT JOIN vehiculos ve ON ve.id = s.vehiculo_id
                LEFT JOIN usuarios r ON r.id = a.revisada_por
                WHERE NOT ? OR (a.respuesta <> 'estoy_bien' AND a.revisada_en IS NULL)
                ORDER BY (a.respuesta <> 'estoy_bien' AND a.revisada_en IS NULL) DESC, a.creado_en DESC
                LIMIT 200
                """, soloPendientes);
    }

    @Transactional
    public Map<String, Object> revisarAlerta(Long id, String notas) {
        actor.declarar(sesion.usuarioId(), sesion.actorPersonal());
        int n = db.update("""
                UPDATE alertas_viaje SET revisada_por = ?, revisada_en = now(), notas = ?
                WHERE id = ? AND revisada_en IS NULL
                """, sesion.usuarioId(), Datos.recortar(Datos.vacioANull(notas), 500), id);
        if (n == 0) throw new RecursoNoEncontradoException("La alerta no existe o ya fue revisada");
        admin.auditar("alerta_viaje_revisada", "alertas_viaje", id, Map.of("notas", Datos.nvl(notas)));
        return Map.of("message", "Alerta revisada");
    }

    // ═════════════════════════════ SOS ═════════════════════════════

    /** Alertas SOS con el viaje (conductor, placa) y quién la atendió. Las abiertas primero. */
    public List<Map<String, Object>> sos() {
        return db.queryForList("""
                SELECT a.id, a.servicio_id, a.lat, a.lng, a.estado, a.notas, a.creado_en, a.atendida_en,
                       trim(u.nombres || ' ' || COALESCE(u.apellidos, '')) AS usuario_nombre, u.telefono,
                       CASE WHEN s.cliente_id = a.usuario_id THEN 'cliente' WHEN s.conductor_id = a.usuario_id THEN 'conductor' END AS rol,
                       s.tipo_vehiculo, s.estado AS servicio_estado, ve.placa,
                       trim(c.nombres || ' ' || COALESCE(c.apellidos, '')) AS conductor_nombre, c.telefono AS conductor_telefono,
                       trim(r.nombres || ' ' || COALESCE(r.apellidos, '')) AS atendida_por_nombre
                FROM alertas_sos a
                JOIN usuarios u ON u.id = a.usuario_id
                LEFT JOIN servicios s ON s.id = a.servicio_id
                LEFT JOIN usuarios c ON c.id = s.conductor_id
                LEFT JOIN vehiculos ve ON ve.id = s.vehiculo_id
                LEFT JOIN usuarios r ON r.id = a.atendida_por
                ORDER BY (a.estado IN ('abierta', 'en_atencion')) DESC, a.creado_en DESC
                LIMIT 200
                """);
    }

    // ═════════════════════════════ Programados ═════════════════════════════

    public List<Map<String, Object>> programados() {
        return db.queryForList("""
                SELECT p.id, p.tipo_vehiculo, p.origen_direccion AS origen, p.destino_direccion AS destino, p.pasajeros,
                       p.necesita_casco, p.pasajero_nombre, p.valor_cotizado, p.programado_para, p.estado, p.servicio_id,
                       p.motivo_fallo, trim(u.nombres || ' ' || COALESCE(u.apellidos, '')) AS cliente_nombre, u.telefono
                FROM viajes_programados p JOIN usuarios u ON u.id = p.cliente_id
                WHERE p.estado = 'pendiente' OR p.programado_para > now() - interval '2 days'
                ORDER BY p.estado <> 'pendiente', p.programado_para
                LIMIT 300
                """);
    }

    // ═════════════════════════════ PQRS ═════════════════════════════

    /**
     * PQRS con el contexto completo: quién reporta (y si es pasajero o conductor), el viaje y la otra persona
     * del viaje (a quién se reporta). filtro: abiertos | todos.
     */
    public List<Map<String, Object>> pqrs(String filtro) {
        return db.queryForList("""
                SELECT t.id, t.tipo, t.prioridad, t.estado, e.nombre AS estado_nombre, e.color AS estado_color, t.asunto,
                       t.descripcion, t.respuesta, t.creado_en, t.resuelto_en, t.fecha_limite_respuesta,
                       (t.fecha_limite_respuesta - CURRENT_DATE) AS dias_restantes,
                       (t.estado IN ('abierto', 'en_proceso') AND t.fecha_limite_respuesta < CURRENT_DATE) AS vencida,
                       t.usuario_id, trim(u.nombres || ' ' || COALESCE(u.apellidos, '')) AS reporta_nombre,
                       u.telefono AS reporta_telefono,
                       CASE WHEN s.cliente_id = t.usuario_id THEN 'cliente'
                            WHEN s.conductor_id = t.usuario_id THEN 'conductor' END AS reporta_rol,
                       t.servicio_id, s.tipo_servicio, s.origen_direccion, s.destino_direccion, s.solicitado_en AS servicio_fecha,
                       r.id AS reportado_id, trim(r.nombres || ' ' || COALESCE(r.apellidos, '')) AS reportado_nombre,
                       r.telefono AS reportado_telefono, r.estado AS reportado_estado,
                       trim(a.nombres || ' ' || COALESCE(a.apellidos, '')) AS asignado_nombre
                FROM tickets_soporte t
                JOIN estados e ON e.ambito = 'ticket' AND e.codigo = t.estado
                JOIN usuarios u ON u.id = t.usuario_id
                LEFT JOIN servicios s ON s.id = t.servicio_id
                LEFT JOIN usuarios r ON r.id = CASE WHEN s.cliente_id = t.usuario_id THEN s.conductor_id
                                                    WHEN s.conductor_id = t.usuario_id THEN s.cliente_id END
                LEFT JOIN usuarios a ON a.id = t.asignado_a
                WHERE ? = 'todos' OR t.estado IN ('abierto', 'en_proceso')
                ORDER BY (t.estado IN ('abierto', 'en_proceso')) DESC, (t.prioridad = 'urgente') DESC,
                         t.fecha_limite_respuesta, t.creado_en DESC
                LIMIT 300
                """, "todos".equals(filtro) ? "todos" : "abiertos");
    }
}
