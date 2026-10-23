package com.movify.backend.servicios;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.movify.backend.servicios.ServicioDtos.SosRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Botón SOS y PQRS (peticiones, quejas, reclamos y sugerencias) del cliente y del conductor. */
@RestController
@RequestMapping("/api")
public class SeguridadPersonalControlador {

    private final SeguridadPersonalServicio seguridad;

    public SeguridadPersonalControlador(SeguridadPersonalServicio seguridad) {
        this.seguridad = seguridad;
    }

    // ───────────── SOS ─────────────

    @PostMapping("/sos")
    @PreAuthorize("hasAuthority('seguridad_personal.activar_sos')")
    public Map<String, Object> activarSos(@Valid @RequestBody SosRequest req) {
        Long id = seguridad.activarSos(req.servicioId(), req.lat(), req.lng());
        return Map.of("id", id, "message", "Alerta enviada. El equipo de soporte ya fue avisado. Si estás en peligro llama al 123.");
    }

    @PostMapping("/sos/{id}/falsa-alarma")
    public Map<String, Object> falsaAlarma(@PathVariable Long id) {
        seguridad.falsaAlarma(id);
        return Map.of("message", "Marcamos la alerta como falsa alarma");
    }

    // ───────────── PQRS ─────────────

    public record PqrsRequest(
            @NotBlank @Pattern(regexp = "peticion|queja|reclamo|sugerencia|objeto_olvidado|cobro_indebido|incidente_seguridad") String tipo,
            @NotBlank @Size(max = 120) String asunto,
            @NotBlank @Size(max = 2000) String descripcion,
            Long servicioId) {
    }

    @PostMapping("/pqrs")
    @PreAuthorize("hasAuthority('pqrs.crear')")
    public Map<String, Object> crearPqrs(@Valid @RequestBody PqrsRequest req) {
        Map<String, Object> r = seguridad.crearPqrs(req.tipo(), req.asunto(), req.descripcion(), req.servicioId());
        return Map.of("id", r.get("id"), "fecha_limite_respuesta", r.get("fecha_limite_respuesta"),
                "message", "Recibimos tu solicitud. Te responderemos a más tardar el " + r.get("fecha_limite_respuesta") + ".");
    }

    @GetMapping("/pqrs")
    @PreAuthorize("hasAuthority('pqrs.ver_propias')")
    public List<Map<String, Object>> misPqrs() {
        return seguridad.misPqrs();
    }

    /** Un reporte propio con el viaje al que se refiere y la otra persona del viaje. */
    @GetMapping("/pqrs/{id}")
    @PreAuthorize("hasAuthority('pqrs.ver_propias')")
    public Map<String, Object> miPqrs(@PathVariable Long id) {
        return seguridad.miPqrs(id);
    }

    /** Si la respuesta le sirvió la cierra; si no, la reabre (resuelto → en_proceso). */
    @PostMapping("/pqrs/{id}/{accion:cerrar|reabrir}")
    public Map<String, Object> responderPqrs(@PathVariable Long id, @PathVariable String accion) {
        boolean cerrar = "cerrar".equals(accion);
        seguridad.responderPqrs(id, cerrar);
        return Map.of("message", cerrar ? "PQRS cerrada" : "Reabrimos tu PQRS");
    }
}
