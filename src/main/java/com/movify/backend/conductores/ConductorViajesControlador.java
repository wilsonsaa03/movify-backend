package com.movify.backend.conductores;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.movify.backend.comun.Datos;
import com.movify.backend.seguridad.SesionActual;
import com.movify.backend.servicios.EstadisticasServicio;
import com.movify.backend.servicios.ServicioDtos.UbicacionRequest;
import com.movify.backend.servicios.ViajeServicio;

import jakarta.validation.Valid;

/**
 * El conductor en el día a día (rutas /api/transporte que ya usa la web): conectarse, enviar el GPS,
 * ver solicitudes, ganancias e historial. Todo id que llega por URL se valida contra el token.
 */
@RestController
@RequestMapping("/api/transporte")
public class ConductorViajesControlador {

    private final SesionActual sesion;
    private final ConductorServicio conductores;
    private final ViajeServicio viajes;
    private final EstadisticasServicio estadisticas;

    public ConductorViajesControlador(SesionActual sesion, ConductorServicio conductores, ViajeServicio viajes,
            EstadisticasServicio estadisticas) {
        this.sesion = sesion;
        this.conductores = conductores;
        this.viajes = viajes;
        this.estadisticas = estadisticas;
    }

    // ───────────── Disponibilidad y GPS ─────────────

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

    // ───────────── Solicitudes ─────────────

    @GetMapping("/solicitudes-pendientes/{conductorId}")
    public ResponseEntity<?> solicitudesPendientes(@PathVariable Long conductorId) {
        sesion.exigirConductor(conductorId);
        Double tasa = viajes.tasaSancion(conductorId);
        if (tasa != null) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "Sanción por alta cancelación",
                    "tasaActual", tasa,
                    "message", "Tu tasa de cancelación supera el 20%. No recibirás solicitudes temporalmente."));
        }
        return ResponseEntity.ok(viajes.solicitudesPara(conductorId));
    }

    // ───────────── Ganancias e historial ─────────────

    @GetMapping("/ganancias-stats/{conductorId}")
    public Map<String, Object> gananciasStats(@PathVariable Long conductorId) {
        sesion.exigirConductor(conductorId);
        return estadisticas.ganancias(conductorId);
    }

    /** Pantalla "Ganancias" de la app: periodo = hoy | semana | mes (el mes en curso). */
    @GetMapping("/ganancias-resumen/{conductorId}")
    public Map<String, Object> gananciasResumen(@PathVariable Long conductorId, @RequestParam(defaultValue = "hoy") String periodo) {
        sesion.exigirConductor(conductorId);
        return estadisticas.resumenGanancias(conductorId, periodo);
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
}
