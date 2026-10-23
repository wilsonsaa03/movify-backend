package com.movify.backend.servicios;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Encomiendas. Se piden con POST /api/servicios (tipo "encomienda") y siguen los mismos estados
 * que los demás servicios; aquí va lo propio del paquete.
 *
 *   GET /api/servicios/{id}/encomienda   remitente, destinatario, paquete y (solo para quien la pidió) el PIN
 */
@RestController
public class EncomiendaControlador {

    private final ViajeServicio viajes;
    private final EncomiendaServicio encomiendas;

    public EncomiendaControlador(ViajeServicio viajes, EncomiendaServicio encomiendas) {
        this.viajes = viajes;
        this.encomiendas = encomiendas;
    }

    @GetMapping("/api/servicios/{id}/encomienda")
    public Map<String, Object> detalle(@PathVariable Long id) {
        return encomiendas.detalle(viajes.obtener(id)); // obtener valida que pueda verlo
    }
}
