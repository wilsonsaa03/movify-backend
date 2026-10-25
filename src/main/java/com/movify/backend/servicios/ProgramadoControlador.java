package com.movify.backend.servicios;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.movify.backend.servicios.ServicioDtos.ProgramadoRequest;

import jakarta.validation.Valid;

/**
 * Viajes programados del cliente:
 *   POST   /api/servicios/programados        programar (moto o taxi)
 *   GET    /api/servicios/programados        los pendientes y los del último día
 *   DELETE /api/servicios/programados/{id}   cancelar uno pendiente
 */
@RestController
@RequestMapping("/api/servicios/programados")
public class ProgramadoControlador {

    private final ProgramadoServicio programados;

    public ProgramadoControlador(ProgramadoServicio programados) {
        this.programados = programados;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('servicios.solicitar')")
    public Map<String, Object> programar(@Valid @RequestBody ProgramadoRequest req) {
        return programados.programar(req);
    }

    @GetMapping
    public List<Map<String, Object>> mios() {
        return programados.mios();
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> cancelar(@PathVariable Long id) {
        programados.cancelar(id);
        return Map.of("message", "Viaje programado cancelado.");
    }
}
