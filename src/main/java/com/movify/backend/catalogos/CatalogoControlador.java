package com.movify.backend.catalogos;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catálogos públicos de la base: la app los lee para pintar opciones, nombres y colores
 * en vez de tenerlos "quemados" en el código.
 */
@RestController
@RequestMapping("/api")
public class CatalogoControlador {

    private final CatalogoServicio catalogos;

    public CatalogoControlador(CatalogoServicio catalogos) {
        this.catalogos = catalogos;
    }

    @GetMapping("/catalogos")
    public Map<String, Object> catalogos() {
        return catalogos.catalogos();
    }

    @GetMapping("/catalogos/documentos-legales")
    public List<Map<String, Object>> documentosLegales() {
        return catalogos.documentosLegales();
    }

    /** Healthcheck del deploy (también comprueba que la base responda). */
    @GetMapping("/test/ping")
    public String ping() {
        return catalogos.baseResponde() ? "pong - Backend is working successfully!" : "pong";
    }
}
