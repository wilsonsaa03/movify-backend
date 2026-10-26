package com.movify.backend.administracion;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Consultas de solo lectura del panel de administración y soporte (listados, resumen y auditoría).
 * Las acciones que cambian datos y quedan auditadas están en AdminServicio.
 */
@Service
public class AdminConsultaServicio {

    private final JdbcTemplate db;

    public AdminConsultaServicio(JdbcTemplate db) {
        this.db = db;
    }

    /** Servicios de la plataforma, opcionalmente por estado (1 a 1000). */
    public List<Map<String, Object>> servicios(String estado, int limite) {
        return db.queryForList("""
                SELECT v.id, v.tipo, v.tipo_servicio, v.tipo_vehiculo, v.estado, v.estado_codigo, v.estado_nombre,
                       v.estado_color, v.tarifa, v.fecha_solicitud, v.origen, v.destino, v.destino_lat, v.destino_lng,
                       v.usuario_nombre, trim(u.nombres || ' ' || COALESCE(u.apellidos, '')) AS conductor_nombre,
                       v.motivo_cancelacion, v.cancelado_por
                FROM v_servicios_api v
                LEFT JOIN usuarios u ON u.id = v.conductor_id
                WHERE CAST(? AS varchar) IS NULL OR v.estado_codigo = ?
                ORDER BY v.fecha_solicitud DESC
                LIMIT ?
                """, estado, estado, Math.min(Math.max(limite, 1), 1000));
    }

    public List<Map<String, Object>> conductoresPendientes() {
        return db.queryForList("SELECT * FROM v_conductores_pendientes ORDER BY creado_en");
    }

    public List<Map<String, Object>> documentosPorVencer() {
        return db.queryForList("SELECT * FROM v_documentos_por_vencer ORDER BY dias_restantes");
    }

    public Map<String, Object> resumen() {
        return Map.of(
                "por_dia", db.queryForList("SELECT * FROM v_resumen_diario WHERE fecha >= CURRENT_DATE - 30 ORDER BY fecha DESC"),
                "conductores_en_linea", db.queryForList("SELECT usuario_id, nombres, tipo_vehiculo, placa, lat, lng, ocupado FROM v_conductores_en_linea"),
                "totales", db.queryForMap("""
                        SELECT (SELECT count(*) FROM usuarios WHERE estado = 'activo') AS usuarios_activos,
                               (SELECT count(*) FROM conductores WHERE estado = 'aprobado') AS conductores_aprobados,
                               (SELECT count(*) FROM conductores WHERE estado = 'pendiente') AS conductores_pendientes,
                               (SELECT count(*) FROM servicios WHERE estado IN ('buscando','asignado','en_origen','en_curso')) AS servicios_activos,
                               (SELECT count(*) FROM alertas_sos WHERE estado IN ('abierta','en_atencion')) AS sos_abiertas,
                               (SELECT count(*) FROM tickets_soporte WHERE estado IN ('abierto','en_proceso')) AS pqrs_abiertas,
                               (SELECT count(*) FROM tickets_soporte WHERE estado IN ('abierto','en_proceso')
                                  AND fecha_limite_respuesta < CURRENT_DATE) AS pqrs_vencidas,
                               (SELECT count(*) FROM alertas_viaje WHERE respuesta <> 'estoy_bien' AND revisada_en IS NULL) AS alertas_viaje_pendientes,
                               (SELECT count(*) FROM v_documentos_por_vencer) AS documentos_por_vencer,
                               (SELECT count(*) FROM viajes_programados WHERE estado = 'pendiente') AS programados_pendientes
                        """));
    }

    public List<Map<String, Object>> tarifas() {
        return db.queryForList("SELECT * FROM tarifas ORDER BY tipo_servicio, tipo_vehiculo, activo DESC, vigente_desde DESC");
    }

    public List<Map<String, Object>> configuracion() {
        return db.queryForList("SELECT clave, valor, descripcion, actualizado_en FROM configuracion_sistema ORDER BY clave");
    }

    public Map<String, Object> auditoria() {
        return Map.of(
                "acciones", db.queryForList("""
                        SELECT a.*, trim(u.nombres || ' ' || COALESCE(u.apellidos, '')) AS admin_nombre
                        FROM auditoria_admin a LEFT JOIN usuarios u ON u.id = a.admin_id
                        ORDER BY a.creado_en DESC LIMIT 200
                        """),
                "cambios", db.queryForList("""
                        SELECT c.*, trim(u.nombres || ' ' || COALESCE(u.apellidos, '')) AS usuario_nombre
                        FROM auditoria_cambios c LEFT JOIN usuarios u ON u.id = c.usuario_id
                        ORDER BY c.creado_en DESC LIMIT 200
                        """),
                "accesos_sospechosos", db.queryForList("SELECT * FROM v_accesos_sospechosos"));
    }
}
