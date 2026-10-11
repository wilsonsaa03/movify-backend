package com.movify.backend.Controlador;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.movify.backend.Comun.AccesoDenegadoException;
import com.movify.backend.Comun.RecursoNoEncontradoException;
import com.movify.backend.Dto.ServicioDtos.CalificacionRequest;
import com.movify.backend.Dto.ServicioDtos.CambioEstadoRequest;
import com.movify.backend.Dto.ServicioDtos.EstadoConductorRequest;
import com.movify.backend.Dto.ServicioDtos.RetiroRequest;
import com.movify.backend.Dto.ServicioDtos.SolicitudRequest;
import com.movify.backend.Dto.ServicioDtos.UbicacionRequest;
import com.movify.backend.Modelo.NotificacionConductor.EstadoNotificacion;
import com.movify.backend.Modelo.Servicio.EstadoServicio;
import com.movify.backend.Seguridad.SesionActual;
import com.movify.backend.Servicio.GananciasServicio;
import com.movify.backend.Servicio.ViajeServicio;

import jakarta.validation.Valid;

/**
 * API de servicios. La lógica de solicitud, estados y calificación vive en ViajeServicio;
 * aquí quedan consultas de lectura. Todo id que llegue por URL se valida contra el token.
 */
@RestController
@RequestMapping("/api/transporte")
public class TransporteControlador {

    private final JdbcTemplate db;
    private final SesionActual sesion;
    private final ViajeServicio viajes;
    private final GananciasServicio ganancias;

    public TransporteControlador(JdbcTemplate db, SesionActual sesion, ViajeServicio viajes,
            GananciasServicio ganancias) {
        this.db = db;
        this.sesion = sesion;
        this.viajes = viajes;
        this.ganancias = ganancias;
    }

    // ───────────────────────── Mapa ─────────────────────────

    /** Solo posición: el cliente no necesita nombre ni foto de conductores que no le fueron asignados. */
    @GetMapping("/conductores-activos")
    public List<Map<String, Object>> conductoresActivos() {
        return db.queryForList("""
                SELECT c.id AS conductor_id, c.latitud, c.longitud, c.en_linea
                FROM conductores c
                WHERE c.en_linea = true AND c.estado = 'aprobado'
                  AND c.latitud IS NOT NULL AND c.longitud IS NOT NULL
                """);
    }

    // ───────────────────────── Conductor: disponibilidad y GPS ─────────────────────────

    /** Body opcional { "activo": false } para desconectarse. */
    @PostMapping("/activar")
    public Map<String, Object> activarConductor(@RequestBody(required = false) Map<String, Object> body) {
        Long conductorId = sesion.conductorId();
        boolean activo = body == null || !Boolean.FALSE.equals(body.get("activo"));
        if (activo) {
            String estado = db.queryForObject("SELECT estado FROM conductores WHERE id = ?", String.class, conductorId);
            if (!"aprobado".equalsIgnoreCase(estado)) {
                throw new AccesoDenegadoException("Tu cuenta de conductor aún no está aprobada");
            }
        }
        db.update("UPDATE conductores SET en_linea = ? WHERE id = ?", activo, conductorId);
        return Map.of("message", activo ? "Ahora estás en línea" : "Te desconectaste", "en_linea", activo);
    }

    @PostMapping("/ubicacion")
    public Map<String, Object> actualizarUbicacion(@Valid @RequestBody UbicacionRequest body) {
        Long conductorId = sesion.conductorId();
        db.update("UPDATE conductores SET latitud = ?, longitud = ? WHERE id = ?", body.lat(), body.lng(),
                conductorId);
        return Map.of("message", "Ubicación actualizada");
    }

    // ───────────────────────── Ciclo del servicio ─────────────────────────

    @PostMapping("/solicitar")
    public Map<String, Object> crearSolicitud(@Valid @RequestBody SolicitudRequest solicitud) {
        return viajes.crearSolicitud(solicitud);
    }

    @GetMapping("/solicitudes-pendientes/{conductorId}")
    public ResponseEntity<?> solicitudesPendientes(@PathVariable Long conductorId) {
        sesion.exigirConductor(conductorId);

        // Sanción solo por cancelaciones del propio conductor y con historial suficiente (mín. 5 cierres)
        Map<String, Object> t = db.queryForMap("""
                SELECT COUNT(*) AS cerrados,
                       COUNT(*) FILTER (WHERE estado = 'CANCELADO' AND cancelado_por = 'conductor') AS cancelados
                FROM servicios
                WHERE conductor_id = ? AND estado IN ('FINALIZADO', 'CANCELADO')
                """, conductorId);
        long cerrados = ((Number) t.get("cerrados")).longValue();
        long cancelados = ((Number) t.get("cancelados")).longValue();
        double tasa = cerrados == 0 ? 0 : cancelados * 100.0 / cerrados;
        if (cerrados >= 5 && tasa > 20.0) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "Sanción por alta cancelación",
                    "tasaActual", Math.round(tasa * 10) / 10.0,
                    "message", "Tu tasa de cancelación supera el 20%. No recibirás solicitudes temporalmente."));
        }

        return ResponseEntity.ok(db.queryForList("""
                SELECT nc.id AS notificacion_id, s.id AS servicio_id, s.tipo, s.descripcion,
                       s.origen_lat, s.origen_lng, s.destino_lat, s.destino_lng,
                       s.origen, s.destino, s.distancia_km, s.tarifa,
                       u.nombre AS usuario_nombre
                FROM servicios s
                JOIN notificaciones_conductor nc ON s.id = nc.servicio_id
                LEFT JOIN usuarios u ON s.usuario_id = u.id
                WHERE nc.conductor_id = ? AND nc.estado_notificacion = ? AND s.estado = ?
                ORDER BY nc.fecha_creacion DESC
                """, conductorId, EstadoNotificacion.ENVIADA.name(), EstadoServicio.PENDIENTE.name()));
    }

    @GetMapping("/servicio/{servicioId}")
    public Map<String, Object> estadoServicio(@PathVariable Long servicioId) {
        return viajes.obtener(servicioId);
    }

    @PatchMapping("/servicio/{servicioId}/estado")
    public Map<String, Object> cambiarEstado(@PathVariable Long servicioId,
            @Valid @RequestBody CambioEstadoRequest body) {
        return viajes.cambiarEstado(servicioId, body.estado());
    }

    @PostMapping("/calificar")
    public Map<String, Object> calificar(@Valid @RequestBody CalificacionRequest body) {
        viajes.calificar(body);
        return Map.of("message", "Calificación guardada.");
    }

    // ───────────────────────── Ganancias del conductor ─────────────────────────

    @GetMapping("/ganancias-stats/{conductorId}")
    public Map<String, Object> gananciasStats(@PathVariable Long conductorId) {
        sesion.exigirConductor(conductorId);
        Map<String, Object> g = db.queryForMap("""
                SELECT
                  COALESCE(SUM(tarifa) FILTER (WHERE DATE(fecha_fin) = CURRENT_DATE), 0)       AS g_hoy,
                  COUNT(*)             FILTER (WHERE DATE(fecha_fin) = CURRENT_DATE)           AS v_hoy,
                  COALESCE(SUM(tarifa) FILTER (WHERE DATE(fecha_fin) = CURRENT_DATE - 1), 0)   AS g_ayer,
                  COALESCE(SUM(tarifa) FILTER (WHERE fecha_fin >= DATE_TRUNC('week', CURRENT_DATE)), 0) AS g_sem,
                  COUNT(*)             FILTER (WHERE fecha_fin >= DATE_TRUNC('week', CURRENT_DATE))     AS v_sem,
                  COALESCE(SUM(tarifa) FILTER (WHERE fecha_fin >= DATE_TRUNC('week', CURRENT_DATE) - INTERVAL '7 days'
                                                 AND fecha_fin <  DATE_TRUNC('week', CURRENT_DATE)), 0) AS g_sem_ant,
                  COALESCE(SUM(tarifa) FILTER (WHERE fecha_fin >= DATE_TRUNC('month', CURRENT_DATE)), 0) AS g_mes,
                  COUNT(*)             FILTER (WHERE fecha_fin >= DATE_TRUNC('month', CURRENT_DATE))     AS v_mes,
                  COALESCE(SUM(tarifa) FILTER (WHERE fecha_fin >= DATE_TRUNC('month', CURRENT_DATE) - INTERVAL '1 month'
                                                 AND fecha_fin <  DATE_TRUNC('month', CURRENT_DATE)), 0) AS g_mes_ant,
                  COALESCE(SUM(tarifa), 0) AS total
                FROM servicios
                WHERE conductor_id = ? AND estado = 'FINALIZADO'
                """, conductorId);

        List<Map<String, Object>> metodosRaw = db.queryForList("""
                SELECT COALESCE(metodo_pago, 'efectivo') AS nombre, COALESCE(SUM(tarifa), 0) AS monto
                FROM servicios
                WHERE conductor_id = ? AND estado = 'FINALIZADO' AND fecha_fin >= DATE_TRUNC('month', CURRENT_DATE)
                GROUP BY COALESCE(metodo_pago, 'efectivo')
                """, conductorId);

        double gMes = num(g.get("g_mes"));
        List<Map<String, Object>> metodos = new ArrayList<>();
        for (Map<String, Object> m : metodosRaw) {
            double monto = num(m.get("monto"));
            String nombre = String.valueOf(m.get("nombre"));
            Map<String, Object> metodo = new HashMap<>();
            metodo.put("nombre", nombre.isEmpty() ? "Efectivo" : nombre.substring(0, 1).toUpperCase() + nombre.substring(1));
            metodo.put("monto", monto);
            metodo.put("pct", gMes > 0 ? Math.round(monto / gMes * 1000.0) / 10.0 : 0.0);
            metodos.add(metodo);
        }

        Map<String, Object> r = new HashMap<>();
        r.put("ganancias_hoy", num(g.get("g_hoy")));
        r.put("viajes_hoy", ((Number) g.get("v_hoy")).intValue());
        r.put("trend_hoy", tendencia(num(g.get("g_hoy")), num(g.get("g_ayer"))));
        r.put("ganancias_semana", num(g.get("g_sem")));
        r.put("viajes_semana", ((Number) g.get("v_sem")).intValue());
        r.put("trend_semana", tendencia(num(g.get("g_sem")), num(g.get("g_sem_ant"))));
        r.put("ganancias_mes", gMes);
        r.put("viajes_mes", ((Number) g.get("v_mes")).intValue());
        r.put("trend_mes", tendencia(gMes, num(g.get("g_mes_ant"))));
        r.put("saldo_disponible", ganancias.saldoDisponible(conductorId));
        r.put("total_acumulado", num(g.get("total")));
        r.put("ganancia_viajes", gMes);
        // No hay propinas ni incentivos en el MVP (sin pagos en línea): se informan en cero, no inventados
        r.put("propinas", 0.0);
        r.put("incentivos", 0.0);
        r.put("metodos_pago", metodos);
        r.put("ultimos_cuatro", null);
        return r;
    }

    @GetMapping("/transacciones-conductor/{conductorId}")
    public List<Map<String, Object>> transacciones(@PathVariable Long conductorId,
            @RequestParam(defaultValue = "mes") String filtro,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size) {
        sesion.exigirConductor(conductorId);
        int tamano = Math.min(Math.max(size, 1), 50);
        int offset = Math.max(page, 0) * tamano;
        String desde = desdeFiltro(filtro);

        List<Map<String, Object>> todos = new ArrayList<>(db.queryForList("""
                SELECT s.id, 'Pago' AS tipo, 'Viaje completado' AS titulo,
                       COALESCE(NULLIF(s.origen, '') || ' → ' || COALESCE(s.destino, ''),
                                TRUNC(s.origen_lat::numeric, 4) || ', ' || TRUNC(s.origen_lng::numeric, 4) || ' → ' ||
                                TRUNC(s.destino_lat::numeric, 4) || ', ' || TRUNC(s.destino_lng::numeric, 4)) AS subtitulo,
                       TO_CHAR(s.fecha_fin, 'DD Mon YYYY') AS fecha,
                       TO_CHAR(s.fecha_fin, 'HH12:MI AM') AS hora,
                       s.tarifa AS monto, s.fecha_fin AS orden
                FROM servicios s
                WHERE s.conductor_id = ? AND s.estado = 'FINALIZADO'
                  AND (CAST(? AS timestamptz) IS NULL OR s.fecha_fin >= CAST(? AS timestamptz))
                ORDER BY s.fecha_fin DESC
                LIMIT ? OFFSET ?
                """, conductorId, desde, desde, tamano, offset));

        todos.addAll(db.queryForList("""
                SELECT r.id, 'RETIRO' AS tipo, 'Retiro solicitado' AS titulo, r.estado AS subtitulo,
                       TO_CHAR(r.fecha, 'DD Mon YYYY') AS fecha, TO_CHAR(r.fecha, 'HH12:MI AM') AS hora,
                       -r.monto AS monto, r.fecha AS orden
                FROM retiros_conductor r
                WHERE r.conductor_id = ?
                  AND (CAST(? AS timestamptz) IS NULL OR r.fecha >= CAST(? AS timestamptz))
                ORDER BY r.fecha DESC
                LIMIT ? OFFSET ?
                """, conductorId, desde, desde, tamano, offset));

        todos.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("orden"))).reversed());
        todos.forEach(m -> m.remove("orden"));
        return todos;
    }

    @GetMapping("/grafica-conductor/{conductorId}")
    public List<Map<String, Object>> grafica(@PathVariable Long conductorId,
            @RequestParam(defaultValue = "mes") String filtro) {
        sesion.exigirConductor(conductorId);
        boolean anio = "anio".equals(filtro);
        String unidad = anio ? "month" : "day";
        String formato = anio ? "Mon YYYY" : "DD Mon";
        String inicio = switch (filtro) {
            case "semana" -> "week";
            case "anio" -> "year";
            default -> "month";
        };
        // unidad, formato e inicio salen de constantes, nunca del texto del usuario
        return db.queryForList("""
                SELECT TO_CHAR(DATE_TRUNC('%1$s', fecha_fin), '%2$s') AS dia, COALESCE(SUM(tarifa), 0) AS valor
                FROM servicios
                WHERE conductor_id = ? AND estado = 'FINALIZADO' AND fecha_fin >= DATE_TRUNC('%3$s', CURRENT_DATE)
                GROUP BY DATE_TRUNC('%1$s', fecha_fin)
                ORDER BY DATE_TRUNC('%1$s', fecha_fin)
                """.formatted(unidad, formato, inicio), conductorId);
    }

    @PostMapping("/solicitar-retiro")
    public Map<String, Object> solicitarRetiro(@Valid @RequestBody RetiroRequest body) {
        return ganancias.solicitarRetiro(sesion.conductorId(), body.monto());
    }

    @GetMapping("/stats-conductor/{conductorId}")
    public Map<String, Object> statsConductor(@PathVariable Long conductorId) {
        sesion.exigirConductor(conductorId);
        Map<String, Object> s = db.queryForMap("""
                SELECT
                  COUNT(*) FILTER (WHERE estado = 'FINALIZADO' AND fecha_solicitud::date = CURRENT_DATE)     AS v_hoy,
                  COALESCE(SUM(tarifa) FILTER (WHERE estado = 'FINALIZADO' AND fecha_solicitud::date = CURRENT_DATE), 0) AS g_hoy,
                  COUNT(*) FILTER (WHERE estado = 'FINALIZADO' AND fecha_solicitud::date = CURRENT_DATE - 1) AS v_ayer,
                  COALESCE(SUM(tarifa) FILTER (WHERE estado = 'FINALIZADO' AND fecha_solicitud::date = CURRENT_DATE - 1), 0) AS g_ayer,
                  COUNT(*) FILTER (WHERE estado IN ('EN_VIAJE','ACEPTADO','EN_CAMINO','EN_CAMINO_AL_USUARIO',
                                                    'LLEGO_AL_ORIGEN','PAQUETE_RECOGIDO')) AS activos,
                  COUNT(*) FILTER (WHERE estado = 'FINALIZADO') AS total
                FROM servicios WHERE conductor_id = ?
                """, conductorId);
        long vHoy = ((Number) s.get("v_hoy")).longValue();
        double gHoy = num(s.get("g_hoy"));
        Map<String, Object> r = new HashMap<>();
        r.put("viajes_hoy", vHoy);
        r.put("ganancias_hoy", gHoy);
        r.put("activos", ((Number) s.get("activos")).longValue());
        r.put("total_viajes", ((Number) s.get("total")).longValue());
        r.put("tendencia_hoy", vHoy - ((Number) s.get("v_ayer")).longValue());
        r.put("tendencia_ganancia", tendencia(gHoy, num(s.get("g_ayer"))));
        return r;
    }

    /**
     * Historial paginado del conductor. La búsqueda va como parámetro (?) — antes se
     * concatenaba al SQL y permitía inyección.
     */
    @GetMapping("/historial-conductor/{conductorId}")
    public Map<String, Object> historialConductor(@PathVariable Long conductorId,
            @RequestParam(defaultValue = "todos") String filtro,
            @RequestParam(defaultValue = "") String busqueda,
            @RequestParam(defaultValue = "1") int pagina,
            @RequestParam(defaultValue = "10") int limite) {
        sesion.exigirConductor(conductorId);
        int lim = Math.min(Math.max(limite, 1), 100);
        int pag = Math.max(pagina, 1);

        String condFecha = switch (filtro) {
            case "hoy" -> "AND s.fecha_solicitud::date = CURRENT_DATE";
            case "semana" -> "AND s.fecha_solicitud >= NOW() - INTERVAL '7 days'";
            case "mes" -> "AND s.fecha_solicitud >= NOW() - INTERVAL '30 days'";
            default -> "";
        };
        String patron = busqueda == null || busqueda.isBlank() ? null
                : "%" + busqueda.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String condBusqueda = """
                AND (CAST(? AS text) IS NULL
                     OR LOWER(COALESCE(s.origen, ''))  LIKE CAST(? AS text)
                     OR LOWER(COALESCE(s.destino, '')) LIKE CAST(? AS text)
                     OR LOWER(COALESCE(u.nombre, ''))  LIKE CAST(? AS text))
                """;

        Long total = db.queryForObject("""
                SELECT COUNT(*) FROM servicios s LEFT JOIN usuarios u ON s.usuario_id = u.id
                WHERE s.conductor_id = ?
                """ + condFecha + condBusqueda, Long.class, conductorId, patron, patron, patron, patron);
        long t = total == null ? 0 : total;
        int totalPaginas = Math.max(1, (int) Math.ceil((double) t / lim));

        List<Map<String, Object>> lista = db.queryForList("""
                SELECT s.id AS servicio_id, s.estado,
                       TO_CHAR(s.fecha_solicitud, 'YYYY-MM-DD"T"HH24:MI:SS') AS fecha_solicitud,
                       s.origen_lat, s.origen_lng, s.destino_lat, s.destino_lng,
                       COALESCE(s.origen, 'Origen del servicio')  AS origen_direccion,
                       'Buenaventura'                             AS origen_ciudad,
                       COALESCE(s.destino, 'Destino del cliente') AS destino_direccion,
                       'Buenaventura'                             AS destino_ciudad,
                       COALESCE(s.distancia_km, 0)                AS distancia_km,
                       0                                          AS duracion_min,
                       COALESCE(s.tarifa, 0)                      AS tarifa,
                       COALESCE(s.metodo_pago, 'Efectivo')        AS metodo_pago,
                       COALESCE(u.nombre, 'Cliente')              AS usuario_nombre,
                       COALESCE(s.tipo, 'TRANSPORTE')             AS tipo,
                       s.descripcion                              AS razon_cancelacion
                FROM servicios s LEFT JOIN usuarios u ON s.usuario_id = u.id
                WHERE s.conductor_id = ?
                """ + condFecha + condBusqueda + """
                ORDER BY s.fecha_solicitud DESC
                LIMIT ? OFFSET ?
                """, conductorId, patron, patron, patron, patron, lim, (pag - 1) * lim);

        Map<String, Object> r = new HashMap<>();
        r.put("viajes", lista);
        r.put("total", t);
        r.put("total_paginas", totalPaginas);
        r.put("pagina_actual", pag);
        return r;
    }

    @GetMapping("/exportar-ganancias/{conductorId}")
    public ResponseEntity<byte[]> exportarGanancias(@PathVariable Long conductorId) {
        sesion.exigirConductor(conductorId);
        List<Map<String, Object>> filas = db.queryForList("""
                SELECT s.id, TO_CHAR(s.fecha_solicitud, 'DD/MM/YYYY HH24:MI') AS fecha, s.estado,
                       COALESCE(s.origen, '') AS origen, COALESCE(s.destino, '') AS destino,
                       COALESCE(s.distancia_km, 0) AS distancia_km, 0 AS duracion_min,
                       COALESCE(s.tarifa, 0) AS tarifa, COALESCE(s.metodo_pago, 'Efectivo') AS metodo_pago
                FROM servicios s
                WHERE s.conductor_id = ? AND s.estado = 'FINALIZADO'
                ORDER BY s.fecha_solicitud DESC
                """, conductorId);

        StringBuilder csv = new StringBuilder("﻿ID,Fecha,Estado,Origen,Destino,Distancia(km),Duracion(min),Tarifa,Metodo pago\n");
        for (Map<String, Object> v : filas) {
            csv.append(String.join(",", celda(v.get("id")), celda(v.get("fecha")), celda(v.get("estado")),
                    celda(v.get("origen")), celda(v.get("destino")), celda(v.get("distancia_km")),
                    celda(v.get("duracion_min")), celda(v.get("tarifa")), celda(v.get("metodo_pago")))).append('\n');
        }
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"ganancias-" + conductorId + ".csv\"")
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ───────────────────────── Historial del usuario ─────────────────────────

    @GetMapping("/servicios/usuario/{usuarioId}")
    public List<Map<String, Object>> historialUsuario(@PathVariable Long usuarioId) {
        sesion.exigirUsuario(usuarioId);
        return db.queryForList("SELECT * FROM servicios WHERE usuario_id = ? ORDER BY fecha_solicitud DESC",
                usuarioId);
    }

    @GetMapping("/servicios/usuario/{usuarioId}/mes-actual")
    public Map<String, Object> statsMesActual(@PathVariable Long usuarioId) {
        sesion.exigirUsuario(usuarioId);
        return db.queryForMap("""
                SELECT COUNT(*) AS total_servicios, COALESCE(SUM(tarifa), 0) AS total_gasto
                FROM servicios
                WHERE usuario_id = ? AND estado = 'FINALIZADO'
                  AND fecha_solicitud >= DATE_TRUNC('month', CURRENT_DATE)
                """, usuarioId);
    }

    // ───────────────────────── Administración (roles en SecurityConfig) ─────────────────────────

    @GetMapping("/admin/conductores")
    public List<Map<String, Object>> todosLosConductores() {
        return db.queryForList("""
                SELECT c.id AS conductor_id, u.nombre, u.correo, u.telefono, c.placa, c.modelo, c.estado
                FROM conductores c JOIN usuarios u ON c.usuario_id = u.id
                ORDER BY c.id DESC
                """);
    }

    @GetMapping("/admin/conductor/{id}/detalle")
    public Map<String, Object> detalleConductor(@PathVariable Long id) {
        List<Map<String, Object>> filas = db.queryForList("""
                SELECT c.id AS conductor_id, u.nombre, u.correo, u.telefono, c.placa, c.modelo, c.estado,
                       c.licencia, c.soat, c.tarjeta_propiedad, c.cedula, c.fecha_verificacion, c.motivo_rechazo
                FROM conductores c JOIN usuarios u ON c.usuario_id = u.id
                WHERE c.id = ?
                """, id);
        if (filas.isEmpty()) throw new RecursoNoEncontradoException("Conductor no encontrado");
        return filas.get(0);
    }

    @PatchMapping("/admin/conductor/{id}/estado")
    public Map<String, Object> estadoConductor(@PathVariable Long id, @Valid @RequestBody EstadoConductorRequest body) {
        String estado = body.estado().toLowerCase();
        int filas = db.update("""
                UPDATE conductores
                SET estado = ?, motivo_rechazo = ?, fecha_verificacion = NOW(),
                    en_linea = CASE WHEN ? = 'aprobado' THEN en_linea ELSE false END
                WHERE id = ?
                """, estado, "rechazado".equals(estado) ? body.motivoRechazo() : null, estado, id);
        if (filas == 0) throw new RecursoNoEncontradoException("Conductor no encontrado");
        return Map.of("message", "Estado del conductor actualizado a " + estado);
    }

    @GetMapping("/admin/servicios")
    public List<Map<String, Object>> todosLosServicios() {
        return db.queryForList("""
                SELECT s.id, s.tipo, s.estado, s.tarifa, s.fecha_solicitud, s.destino_lat, s.destino_lng,
                       u.nombre AS usuario_nombre, uc.nombre AS conductor_nombre
                FROM servicios s
                LEFT JOIN usuarios u ON s.usuario_id = u.id
                LEFT JOIN conductores c ON s.conductor_id = c.id
                LEFT JOIN usuarios uc ON c.usuario_id = uc.id
                ORDER BY s.fecha_solicitud DESC
                """);
    }

    // ───────────────────────── Utilidades ─────────────────────────

    private static String desdeFiltro(String filtro) {
        return switch (filtro) {
            case "hoy" -> java.time.LocalDate.now(java.time.ZoneId.of("America/Bogota")).toString();
            case "semana" -> java.time.LocalDate.now(java.time.ZoneId.of("America/Bogota"))
                    .with(java.time.DayOfWeek.MONDAY).toString();
            case "mes", "mes_cal" -> java.time.LocalDate.now(java.time.ZoneId.of("America/Bogota"))
                    .withDayOfMonth(1).toString();
            case "anio" -> java.time.LocalDate.now(java.time.ZoneId.of("America/Bogota")).withDayOfYear(1).toString();
            default -> null;
        };
    }

    private static double num(Object o) {
        return o == null ? 0 : ((Number) o).doubleValue();
    }

    private static double tendencia(double actual, double anterior) {
        return anterior > 0 ? Math.round((actual - anterior) / anterior * 100.0) : 0;
    }

    /** Escapa comillas y evita inyección de fórmulas al abrir el CSV en Excel. */
    private static String celda(Object o) {
        String s = o == null ? "" : o.toString();
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
