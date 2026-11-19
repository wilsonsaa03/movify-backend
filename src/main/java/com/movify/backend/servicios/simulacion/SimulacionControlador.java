package com.movify.backend.servicios.simulacion;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.seguridad.SesionActual;

/**
 * Viaje simulado de un servicio (la app del conductor y la del cliente leen lo mismo).
 *
 *   GET  /api/servicios/{id}/simulacion?version=N     estado actual (+ la ruta si la app tiene otra versión)
 *                                                     204 = este servicio no se simula
 *   POST /api/servicios/{id}/simulacion/{accion}      iniciar | pausar | reanudar | reiniciar | finalizar | velocidad
 *                                                     body opcional { "velocidad_kmh": 40 }  (solo el conductor)
 */
@RestController
@RequestMapping("/api/servicios/{id}/simulacion")
public class SimulacionControlador {

    private final SimulacionViajes simulacion;
    private final SesionActual sesion;

    public SimulacionControlador(SimulacionViajes simulacion, SesionActual sesion) {
        this.simulacion = simulacion;
        this.sesion = sesion;
    }

    public record ControlRequest(Double velocidad_kmh) {
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> consultar(@PathVariable Long id, @RequestParam(required = false) Integer version) {
        if (!simulacion.habilitada()) return ResponseEntity.noContent().build();
        Map<String, Object> r = simulacion.consultar(id, sesion.usuarioId(), sesion.esPersonal(), version);
        return r == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(r);
    }

    @PostMapping("/{accion}")
    public Map<String, Object> controlar(@PathVariable Long id, @PathVariable String accion,
            @RequestBody(required = false) ControlRequest body) {
        if (!simulacion.habilitada()) throw new RecursoNoEncontradoException("La simulación de viajes está apagada");
        return simulacion.controlar(id, sesion.usuarioId(), sesion.esPersonal(), accion,
                body == null ? null : body.velocidad_kmh());
    }
}
