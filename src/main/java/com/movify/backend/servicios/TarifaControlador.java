package com.movify.backend.servicios;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cotización antes de pedir (pantalla "Confirma tu viaje"). Es pública para el modo
 * "Solo quiero mirar primero". Sin tipo_vehiculo devuelve una opción por cada vehículo permitido.
 */
@RestController
@RequestMapping("/api/tarifas")
public class TarifaControlador {

    private final TarifaServicio tarifas;

    public TarifaControlador(TarifaServicio tarifas) {
        this.tarifas = tarifas;
    }

    @GetMapping("/cotizar")
    public Map<String, Object> cotizar(
            @RequestParam(name = "tipo_servicio", defaultValue = "transporte") String tipoServicio,
            @RequestParam(name = "tipo_vehiculo", required = false) String tipoVehiculo,
            @RequestParam(name = "origen_lat") double oLat, @RequestParam(name = "origen_lng") double oLng,
            @RequestParam(name = "destino_lat") double dLat, @RequestParam(name = "destino_lng") double dLng) {
        String tipo = EstadosServicio.tipoServicio(tipoServicio);
        TarifaServicio.Ruta ruta = tarifas.ruta(oLat, oLng, dLat, dLng);
        List<Map<String, Object>> opciones = tarifas.opciones(tipo, tipoVehiculo, ruta);
        return Map.of("distancia_km", ruta.distanciaKm(), "duracion_min", ruta.duracionMin(), "opciones", opciones,
                "metodo_pago", "efectivo");
    }
}
