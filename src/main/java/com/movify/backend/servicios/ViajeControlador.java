package com.movify.backend.servicios;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.movify.backend.seguridad.SesionActual;
import com.movify.backend.servicios.ServicioDtos.CalificacionRequest;
import com.movify.backend.servicios.ServicioDtos.CambioEstadoRequest;
import com.movify.backend.servicios.ServicioDtos.SolicitudRequest;

import jakarta.validation.Valid;

/**
 * Transporte (moto o taxi) desde el lado del cliente, con las rutas /api/transporte que ya usa
 * la web Angular: mismos nombres y campos de antes. Las rutas del conductor están en
 * ConductorViajesControlador y las del panel en AdminControlador.
 */
@RestController
@RequestMapping("/api/transporte")
public class ViajeControlador {

    private final SesionActual sesion;
    private final ViajeServicio viajes;
    private final EstadisticasServicio estadisticas;

    public ViajeControlador(SesionActual sesion, ViajeServicio viajes, EstadisticasServicio estadisticas) {
        this.sesion = sesion;
        this.viajes = viajes;
        this.estadisticas = estadisticas;
    }

    /** Solo posición: el cliente no necesita nombre ni foto de conductores que no le fueron asignados. */
    @GetMapping("/conductores-activos")
    public List<Map<String, Object>> conductoresActivos(@RequestParam(required = false) String tipoVehiculo) {
        return viajes.conductoresLibres(tipoVehiculo);
    }

    @PostMapping("/solicitar")
    public Map<String, Object> crearSolicitud(@Valid @RequestBody SolicitudRequest solicitud) {
        return viajes.crearSolicitud(solicitud);
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
