package com.movify.backend.servicios;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.movify.backend.comun.Datos;

/**
 * Historiales y cifras para las pantallas del conductor y del cliente.
 * Sin pagos en línea: lo "ganado" es valor_final de los servicios finalizados, cobrado en efectivo.
 * Las fechas se agrupan en hora de Colombia, no la del servidor.
 */
@Service
public class EstadisticasServicio {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    /** Expresión SQL de "fecha en Colombia" de una columna timestamptz. */
    private static final String DIA = "(%s AT TIME ZONE 'America/Bogota')::date";
    private static final String HOY = "(now() AT TIME ZONE 'America/Bogota')::date";

    private final JdbcTemplate db;

    public EstadisticasServicio(JdbcTemplate db) {
        this.db = db;
    }

    // ───────────── Conductor ─────────────

    public Map<String, Object> ganancias(Long conductorId) {
        String fin = DIA.formatted("finalizado_en");
        Map<String, Object> g = db.queryForMap("""
                SELECT
                  COALESCE(SUM(valor_final) FILTER (WHERE %1$s = %2$s), 0)         AS g_hoy,
                  COUNT(*)                  FILTER (WHERE %1$s = %2$s)             AS v_hoy,
                  COALESCE(SUM(valor_final) FILTER (WHERE %1$s = %2$s - 1), 0)     AS g_ayer,
                  COALESCE(SUM(valor_final) FILTER (WHERE %1$s >= date_trunc('week', %2$s)::date), 0) AS g_sem,
                  COUNT(*)                  FILTER (WHERE %1$s >= date_trunc('week', %2$s)::date)     AS v_sem,
                  COALESCE(SUM(valor_final) FILTER (WHERE %1$s >= date_trunc('week', %2$s)::date - 7
                                                     AND %1$s <  date_trunc('week', %2$s)::date), 0) AS g_sem_ant,
                  COALESCE(SUM(valor_final) FILTER (WHERE %1$s >= date_trunc('month', %2$s)::date), 0) AS g_mes,
                  COUNT(*)                  FILTER (WHERE %1$s >= date_trunc('month', %2$s)::date)     AS v_mes,
                  COALESCE(SUM(valor_final) FILTER (WHERE %1$s >= (date_trunc('month', %2$s) - INTERVAL '1 month')::date
                                                     AND %1$s <  date_trunc('month', %2$s)::date), 0) AS g_mes_ant,
                  COALESCE(SUM(valor_final), 0) AS total
                FROM servicios
                WHERE conductor_id = ? AND estado = 'finalizado'
                """.formatted(fin, HOY), conductorId);

        double gMes = Datos.num(g.get("g_mes"));
        Map<String, Object> r = new HashMap<>();
        r.put("ganancias_hoy", Datos.num(g.get("g_hoy")));
        r.put("viajes_hoy", ((Number) g.get("v_hoy")).intValue());
        r.put("trend_hoy", tendencia(Datos.num(g.get("g_hoy")), Datos.num(g.get("g_ayer"))));
        r.put("ganancias_semana", Datos.num(g.get("g_sem")));
        r.put("viajes_semana", ((Number) g.get("v_sem")).intValue());
        r.put("trend_semana", tendencia(Datos.num(g.get("g_sem")), Datos.num(g.get("g_sem_ant"))));
        r.put("ganancias_mes", gMes);
        r.put("viajes_mes", ((Number) g.get("v_mes")).intValue());
        r.put("trend_mes", tendencia(gMes, Datos.num(g.get("g_mes_ant"))));
        r.put("total_acumulado", Datos.num(g.get("total")));
        r.put("ganancia_viajes", gMes);
        // Todo se cobra en efectivo directamente: no hay saldo en la plataforma, ni propinas ni incentivos
        r.put("saldo_disponible", 0.0);
        r.put("propinas", 0.0);
        r.put("incentivos", 0.0);
        r.put("metodos_pago", gMes > 0 ? List.of(Map.of("nombre", "Efectivo", "monto", gMes, "pct", 100.0)) : List.of());
        r.put("ultimos_cuatro", null);
        return r;
    }

    public List<Map<String, Object>> transacciones(Long conductorId, String filtro, int page, int size) {
        int tamano = Math.min(Math.max(size, 1), 50);
        LocalDate desde = desde(filtro);
        return db.queryForList("""
                SELECT s.id, 'Pago' AS tipo, 'Viaje completado' AS titulo,
                       s.origen_direccion || ' → ' || s.destino_direccion AS subtitulo,
                       TO_CHAR(s.finalizado_en AT TIME ZONE 'America/Bogota', 'DD Mon YYYY') AS fecha,
                       TO_CHAR(s.finalizado_en AT TIME ZONE 'America/Bogota', 'HH12:MI AM') AS hora,
                       s.valor_final AS monto
                FROM servicios s
                WHERE s.conductor_id = ? AND s.estado = 'finalizado'
                  AND (CAST(? AS date) IS NULL OR (s.finalizado_en AT TIME ZONE 'America/Bogota')::date >= CAST(? AS date))
                ORDER BY s.finalizado_en DESC
                LIMIT ? OFFSET ?
                """, conductorId, desde, desde, tamano, Math.max(page, 0) * tamano);
    }

    public List<Map<String, Object>> grafica(Long conductorId, String filtro) {
        boolean anio = "anio".equals(filtro);
        // unidad, formato e inicio salen de constantes, nunca del texto del usuario
        String unidad = anio ? "month" : "day";
        String formato = anio ? "Mon YYYY" : "DD Mon";
        String inicio = switch (filtro) {
            case "semana" -> "week";
            case "anio" -> "year";
            default -> "month";
        };
        return db.queryForList("""
                SELECT TO_CHAR(date_trunc('%1$s', finalizado_en AT TIME ZONE 'America/Bogota'), '%2$s') AS dia,
                       COALESCE(SUM(valor_final), 0) AS valor
                FROM servicios
                WHERE conductor_id = ? AND estado = 'finalizado'
                  AND finalizado_en AT TIME ZONE 'America/Bogota' >= date_trunc('%3$s', now() AT TIME ZONE 'America/Bogota')
                GROUP BY date_trunc('%1$s', finalizado_en AT TIME ZONE 'America/Bogota')
                ORDER BY date_trunc('%1$s', finalizado_en AT TIME ZONE 'America/Bogota')
                """.formatted(unidad, formato, inicio), conductorId);
    }

    public Map<String, Object> resumenConductor(Long conductorId) {
        String sol = DIA.formatted("solicitado_en");
        Map<String, Object> s = db.queryForMap("""
                SELECT COUNT(*) FILTER (WHERE estado = 'finalizado' AND %1$s = %2$s)                       AS v_hoy,
                       COALESCE(SUM(valor_final) FILTER (WHERE estado = 'finalizado' AND %1$s = %2$s), 0)  AS g_hoy,
                       COUNT(*) FILTER (WHERE estado = 'finalizado' AND %1$s = %2$s - 1)                   AS v_ayer,
                       COALESCE(SUM(valor_final) FILTER (WHERE estado = 'finalizado' AND %1$s = %2$s - 1), 0) AS g_ayer,
                       COUNT(*) FILTER (WHERE estado IN ('asignado', 'en_origen', 'en_curso'))             AS activos,
                       COUNT(*) FILTER (WHERE estado = 'finalizado')                                        AS total
                FROM servicios WHERE conductor_id = ?
                """.formatted(sol, HOY), conductorId);
        long vHoy = ((Number) s.get("v_hoy")).longValue();
        double gHoy = Datos.num(s.get("g_hoy"));
        Map<String, Object> r = new HashMap<>();
        r.put("viajes_hoy", vHoy);
        r.put("ganancias_hoy", gHoy);
        r.put("activos", ((Number) s.get("activos")).longValue());
        r.put("total_viajes", ((Number) s.get("total")).longValue());
        r.put("tendencia_hoy", vHoy - ((Number) s.get("v_ayer")).longValue());
        r.put("tendencia_ganancia", tendencia(gHoy, Datos.num(s.get("g_ayer"))));
        return r;
    }

    /** Historial paginado del conductor. La búsqueda va como parámetro, nunca concatenada. */
    public Map<String, Object> historialConductor(Long conductorId, String filtro, String busqueda, int pagina, int limite) {
        int lim = Math.min(Math.max(limite, 1), 100);
        int pag = Math.max(pagina, 1);
        String condFecha = switch (filtro) {
            case "hoy" -> "AND (v.fecha_solicitud AT TIME ZONE 'America/Bogota')::date = (now() AT TIME ZONE 'America/Bogota')::date";
            case "semana" -> "AND v.fecha_solicitud >= now() - INTERVAL '7 days'";
            case "mes" -> "AND v.fecha_solicitud >= now() - INTERVAL '30 days'";
            default -> "";
        };
        String patron = busqueda == null || busqueda.isBlank() ? null
                : "%" + busqueda.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String condBusqueda = """
                AND (CAST(? AS text) IS NULL
                     OR lower(v.origen) LIKE CAST(? AS text)
                     OR lower(v.destino) LIKE CAST(? AS text)
                     OR lower(v.usuario_nombre) LIKE CAST(? AS text))
                """;
        Long total = db.queryForObject("SELECT COUNT(*) FROM v_servicios_api v WHERE v.conductor_id = ? "
                + condFecha + condBusqueda, Long.class, conductorId, patron, patron, patron, patron);
        long t = total == null ? 0 : total;

        List<Map<String, Object>> lista = db.queryForList("""
                SELECT v.id AS servicio_id, v.estado, v.estado_codigo, v.estado_nombre,
                       TO_CHAR(v.fecha_solicitud AT TIME ZONE 'America/Bogota', 'YYYY-MM-DD"T"HH24:MI:SS') AS fecha_solicitud,
                       v.origen_lat, v.origen_lng, v.destino_lat, v.destino_lng,
                       v.origen AS origen_direccion, 'Buenaventura' AS origen_ciudad,
                       v.destino AS destino_direccion, 'Buenaventura' AS destino_ciudad,
                       COALESCE(v.distancia_km, 0) AS distancia_km, COALESCE(v.duracion_estimada_min, 0) AS duracion_min,
                       COALESCE(v.tarifa, 0) AS tarifa, 'Efectivo' AS metodo_pago, v.usuario_nombre,
                       v.tipo, v.tipo_vehiculo, COALESCE(v.motivo_cancelacion, v.detalle_cancelacion) AS razon_cancelacion
                FROM v_servicios_api v
                WHERE v.conductor_id = ?
                """ + condFecha + condBusqueda + """
                ORDER BY v.fecha_solicitud DESC
                LIMIT ? OFFSET ?
                """, conductorId, patron, patron, patron, patron, lim, (pag - 1) * lim);

        Map<String, Object> r = new HashMap<>();
        r.put("viajes", lista);
        r.put("total", t);
        r.put("total_paginas", Math.max(1, (int) Math.ceil((double) t / lim)));
        r.put("pagina_actual", pag);
        return r;
    }

    public byte[] csvGanancias(Long conductorId) {
        List<Map<String, Object>> filas = db.queryForList("""
                SELECT v.id, TO_CHAR(v.finalizado_en AT TIME ZONE 'America/Bogota', 'DD/MM/YYYY HH24:MI') AS fecha,
                       v.estado_nombre AS estado, v.tipo_servicio, v.tipo_vehiculo, v.origen, v.destino,
                       COALESCE(v.distancia_km, 0) AS distancia_km, COALESCE(v.duracion_estimada_min, 0) AS duracion_min,
                       COALESCE(v.valor_final, 0) AS valor
                FROM v_servicios_api v
                WHERE v.conductor_id = ? AND v.estado_codigo = 'finalizado'
                ORDER BY v.finalizado_en DESC
                """, conductorId);
        StringBuilder csv = new StringBuilder("﻿ID,Fecha,Estado,Servicio,Vehiculo,Origen,Destino,Distancia(km),Duracion(min),Valor,Metodo pago\n");
        for (Map<String, Object> v : filas) {
            csv.append(String.join(",", celda(v.get("id")), celda(v.get("fecha")), celda(v.get("estado")),
                    celda(v.get("tipo_servicio")), celda(v.get("tipo_vehiculo")), celda(v.get("origen")),
                    celda(v.get("destino")), celda(v.get("distancia_km")), celda(v.get("duracion_min")),
                    celda(v.get("valor")), celda("Efectivo"))).append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Últimos servicios del conductor (1 a 200). */
    public List<Map<String, Object>> serviciosConductor(Long conductorId, int limite) {
        return db.queryForList("SELECT * FROM v_servicios_api WHERE conductor_id = ? ORDER BY fecha_solicitud DESC LIMIT ?",
                conductorId, Math.min(Math.max(limite, 1), 200));
    }

    // ───────────── Cliente ─────────────

    public List<Map<String, Object>> historialCliente(Long clienteId, boolean soloMes) {
        return db.queryForList("""
                SELECT v.*
                FROM v_servicios_api v
                WHERE v.cliente_id = ?
                """ + (soloMes ? " AND v.fecha_solicitud >= date_trunc('month', now() AT TIME ZONE 'America/Bogota') AT TIME ZONE 'America/Bogota' " : "")
                + " ORDER BY v.fecha_solicitud DESC", clienteId);
    }

    public Map<String, Object> resumenMesCliente(Long clienteId) {
        return db.queryForMap("""
                SELECT COUNT(*) AS total_servicios, COALESCE(SUM(valor_final), 0) AS total_gasto
                FROM servicios
                WHERE cliente_id = ? AND estado = 'finalizado'
                  AND solicitado_en >= date_trunc('month', now() AT TIME ZONE 'America/Bogota') AT TIME ZONE 'America/Bogota'
                """, clienteId);
    }

    // ───────────── Utilidades ─────────────

    private static LocalDate desde(String filtro) {
        LocalDate hoy = LocalDate.now(BOGOTA);
        return switch (filtro) {
            case "hoy" -> hoy;
            case "semana" -> hoy.with(DayOfWeek.MONDAY);
            case "mes", "mes_cal" -> hoy.withDayOfMonth(1);
            case "anio" -> hoy.withDayOfYear(1);
            default -> null;
        };
    }

    private static double tendencia(double actual, double anterior) {
        return anterior > 0 ? Math.round((actual - anterior) / anterior * 100.0) : 0;
    }

    /** Escapa comillas y evita inyección de fórmulas al abrir el CSV en Excel. */
    static String celda(Object o) {
        String s = o == null ? "" : o.toString();
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
