package com.movify.backend.servicios;

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

import com.movify.backend.comun.Datos;
import com.movify.backend.conductores.ConductorDtos;
import com.movify.backend.conductores.ConductorServicio;
import com.movify.backend.seguridad.SesionActual;
import com.movify.backend.servicios.ServicioDtos.CalificacionRequest;
import com.movify.backend.servicios.ServicioDtos.CambioEstadoRequest;
import com.movify.backend.servicios.ServicioDtos.SolicitudRequest;
import com.movify.backend.servicios.ServicioDtos.UbicacionRequest;

import jakarta.validation.Valid;

/**
 * Rutas /api/transporte que usa la web Angular. Mantienen los mismos nombres y campos
 * de antes, pero ahora trabajan sobre el modelo v2.3 (servicios, ofertas, ubicacion_conductores).
 * Todo id que llega por URL se valida contra el token.
 */
@RestController
@RequestMapping("/api/transporte")
public class TransporteControlador {

    private final JdbcTemplate db;
    private final SesionActual sesion;
    private final ViajeServicio viajes;
    private final EstadisticasServicio estadisticas;
    private final ConductorServicio conductores;

    public TransporteControlador(JdbcTemplate db, SesionActual sesion, ViajeServicio viajes,
            EstadisticasServicio estadisticas, ConductorServicio conductores) {
        this.db = db;
        this.sesion = sesion;
        this.viajes = viajes;
        this.estadisticas = estadisticas;
        this.conductores = conductores;
    }

    // ───────────── Mapa ─────────────

    /** Solo posición: el cliente no necesita nombre ni foto de conductores que no le fueron asignados. */
    @GetMapping("/conductores-activos")
    public List<Map<String, Object>> conductoresActivos(@RequestParam(required = false) String tipoVehiculo) {
        return db.queryForList("""
                SELECT usuario_id AS conductor_id, lat AS latitud, lng AS longitud, true AS en_linea, tipo_vehiculo
                FROM v_conductores_en_linea
                WHERE NOT ocupado
                  AND actualizado_en > now() - make_interval(secs => fn_config_num('ubicacion_vigencia_seg', 300)::int)
                  AND (CAST(? AS varchar) IS NULL OR tipo_vehiculo = ?)
                """, tipoVehiculo, tipoVehiculo);
    }

    // ───────────── Conductor: disponibilidad y GPS ─────────────

    /** Body { "activo": true|false, "lat": .., "lng": .. }. Las coordenadas son opcionales pero recomendadas. */
    @PostMapping("/activar")
    public Map<String, Object> activarConductor(@RequestBody(required = false) Map<String, Object> body) {
        boolean activo = body == null || !Boolean.FALSE.equals(body.get("activo"));
        Double lat = body == null ? null : Datos.decimal(body.get("lat"));
        Double lng = body == null ? null : body.containsKey("lng") ? Datos.decimal(body.get("lng")) : Datos.decimal(body.get("lon"));
        return conductores.disponibilidad(sesion.conductorId(), activo, lat, lng);
    }

    @PostMapping("/ubicacion")
    public Map<String, Object> actualizarUbicacion(@Valid @RequestBody UbicacionRequest body) {
        conductores.ubicacion(sesion.conductorId(), new ConductorDtos.UbicacionRequest(body.lat(), body.lng(), null, null, null));
        return Map.of("message", "Ubicación actualizada");
    }

    // ───────────── Ciclo del servicio ─────────────

    @PostMapping("/solicitar")
    public Map<String, Object> crearSolicitud(@Valid @RequestBody SolicitudRequest solicitud) {
        return viajes.crearSolicitud(solicitud);
    }

    @GetMapping("/solicitudes-pendientes/{conductorId}")
    public ResponseEntity<?> solicitudesPendientes(@PathVariable Long conductorId) {
        sesion.exigirConductor(conductorId);
        // Sanción solo con historial suficiente (mín. 5 cierres) y más de 20 % de cancelaciones propias
        Map<String, Object> t = db.queryForMap(
                "SELECT total_servicios, total_cancelaciones FROM conductores WHERE usuario_id = ?", conductorId);
        long cerrados = ((Number) t.get("total_servicios")).longValue() + ((Number) t.get("total_cancelaciones")).longValue();
        long cancelados = ((Number) t.get("total_cancelaciones")).longValue();
        double tasa = cerrados == 0 ? 0 : cancelados * 100.0 / cerrados;
        if (cerrados >= 5 && tasa > 20.0) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "Sanción por alta cancelación",
                    "tasaActual", Math.round(tasa * 10) / 10.0,
                    "message", "Tu tasa de cancelación supera el 20%. No recibirás solicitudes temporalmente."));
        }
        return ResponseEntity.ok(viajes.solicitudesPara(conductorId));
    }

    @GetMapping("/servicio/{servicioId}")
    public Map<String, Object> estadoServicio(@PathVariable Long servicioId) {
        return viajes.obtener(servicioId);
    }

    @PatchMapping("/servicio/{servicioId}/estado")
    public Map<String, Object> cambiarEstado(@PathVariable Long servicioId, @Valid @RequestBody CambioEstadoRequest body) {
        return viajes.cambiarEstado(servicioId, body);
    }

    @PostMapping("/calificar")
    public Map<String, Object> calificar(@Valid @RequestBody CalificacionRequest body) {
        viajes.calificar(body.servicioId(), body.puntos(), body.comentario());
        return Map.of("message", "Calificación guardada.");
    }

    // ───────────── Ganancias y viajes del conductor ─────────────

    @GetMapping("/ganancias-stats/{conductorId}")
    public Map<String, Object> gananciasStats(@PathVariable Long conductorId) {
        sesion.exigirConductor(conductorId);
        return estadisticas.ganancias(conductorId);
    }

    @GetMapping("/transacciones-conductor/{conductorId}")
    public List<Map<String, Object>> transacciones(@PathVariable Long conductorId,
            @RequestParam(defaultValue = "mes") String filtro,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size) {
        sesion.exigirConductor(conductorId);
        return estadisticas.transacciones(conductorId, filtro, page, size);
    }

    @GetMapping("/grafica-conductor/{conductorId}")
    public List<Map<String, Object>> grafica(@PathVariable Long conductorId, @RequestParam(defaultValue = "mes") String filtro) {
        sesion.exigirConductor(conductorId);
        return estadisticas.grafica(conductorId, filtro);
    }

    /** En MoviFY el conductor cobra en efectivo directamente: no hay saldo en la plataforma para retirar. */
    @PostMapping("/solicitar-retiro")
    public ResponseEntity<Map<String, Object>> solicitarRetiro() {
        return ResponseEntity.status(410).body(Map.of("error",
                "En MoviFY los servicios se cobran en efectivo directamente; no hay saldo para retirar."));
    }

    @GetMapping("/stats-conductor/{conductorId}")
    public Map<String, Object> statsConductor(@PathVariable Long conductorId) {
        sesion.exigirConductor(conductorId);
        return estadisticas.resumenConductor(conductorId);
    }

    @GetMapping("/historial-conductor/{conductorId}")
    public Map<String, Object> historialConductor(@PathVariable Long conductorId,
            @RequestParam(defaultValue = "todos") String filtro,
            @RequestParam(defaultValue = "") String busqueda,
            @RequestParam(defaultValue = "1") int pagina,
            @RequestParam(defaultValue = "10") int limite) {
        sesion.exigirConductor(conductorId);
        return estadisticas.historialConductor(conductorId, filtro, busqueda, pagina, limite);
    }

    @GetMapping("/exportar-ganancias/{conductorId}")
    public ResponseEntity<byte[]> exportarGanancias(@PathVariable Long conductorId) {
        sesion.exigirConductor(conductorId);
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"ganancias-" + conductorId + ".csv\"")
                .body(estadisticas.csvGanancias(conductorId));
    }

    // ───────────── Historial del cliente ─────────────

    @GetMapping("/servicios/usuario/{usuarioId}")
    public List<Map<String, Object>> historialUsuario(@PathVariable Long usuarioId) {
        sesion.exigirUsuario(usuarioId);
        return estadisticas.historialCliente(usuarioId, false);
    }

    @GetMapping("/servicios/usuario/{usuarioId}/mes-actual")
    public Map<String, Object> statsMesActual(@PathVariable Long usuarioId) {
        sesion.exigirUsuario(usuarioId);
        return estadisticas.resumenMesCliente(usuarioId);
    }
}
