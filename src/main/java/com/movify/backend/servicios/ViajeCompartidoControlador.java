package com.movify.backend.servicios;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Público (sin sesión): lo abre la familia desde el enlace que comparte el pasajero.
 *   GET /api/publico/viajes/{token}
 * El token es aleatorio de 256 bits, vence solo y no muestra celulares ni apellidos.
 */
@RestController
@RequestMapping("/api/publico/viajes")
public class ViajeCompartidoControlador {

    private final ViajeOpcionesServicio opciones;

    public ViajeCompartidoControlador(ViajeOpcionesServicio opciones) {
        this.opciones = opciones;
    }

    @GetMapping("/{token}")
    public ResponseEntity<Map<String, Object>> ver(@PathVariable String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Robots-Tag", "noindex").body(opciones.verCompartido(token));
    }
}
