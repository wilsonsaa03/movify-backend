package com.movify.backend.servicios.llamadas;

import java.util.Map;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** POST /api/servicios/{id}/llamar: llamada con número protegido entre el cliente y el conductor. */
@RestController
public class LlamadaControlador {

    private final LlamadaServicio llamadas;

    public LlamadaControlador(LlamadaServicio llamadas) {
        this.llamadas = llamadas;
    }

    @PostMapping("/api/servicios/{id}/llamar")
    public Map<String, Object> llamar(@PathVariable Long id) {
        return llamadas.llamar(id);
    }
}
