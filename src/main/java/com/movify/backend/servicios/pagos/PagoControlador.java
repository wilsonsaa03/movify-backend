package com.movify.backend.servicios.pagos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 *   GET  /api/catalogos/formas-pago            efectivo (+ nequi, daviplata si están habilitados) y datos de prueba
 *   GET  /api/servicios/{id}/pago              estado del pago (cliente, conductor o personal)
 *   GET  /api/catalogos/bancos-pse             bancos para PSE
 *   POST /api/servicios/{id}/pago              { metodo: nequi|daviplata|tarjeta|pse|bancolombia, cuenta, ... }
 *   POST /api/servicios/{id}/pago/otp          { codigo: "574829" }  (Daviplata)
 *   POST /api/servicios/{id}/pago/cancelar     el cliente paga en efectivo
 */
@RestController
public class PagoControlador {

    private final PagoServicio pagos;

    public PagoControlador(PagoServicio pagos) {
        this.pagos = pagos;
    }

    /**
     * cuenta: celular Nequi | cédula Daviplata | token de la tarjeta (tok_..., lo crea la app con Wompi) | documento PSE |
     * vacío para Bancolombia. Tarjeta: cuotas y ultimos4. PSE: tipo_persona (natural|juridica), tipo_documento (CC|NIT), banco.
     */
    public record IniciarPagoRequest(@NotBlank @Size(max = 12) String metodo, @Size(max = 130) String cuenta,
            @Size(max = 2) String cuotas, @Size(max = 4) String ultimos4,
            @Size(max = 10) @JsonProperty("tipo_persona") String tipoPersona,
            @Size(max = 4) @JsonProperty("tipo_documento") String tipoDocumento, @Size(max = 10) String banco) {

        Map<String, String> extra() {
            Map<String, String> m = new HashMap<>();
            if (cuotas != null) m.put("cuotas", cuotas);
            if (ultimos4 != null) m.put("ultimos4", ultimos4);
            if (tipoPersona != null) m.put("tipo_persona", tipoPersona);
            if (tipoDocumento != null) m.put("tipo_documento", tipoDocumento);
            if (banco != null) m.put("banco", banco);
            return m;
        }
    }

    public record OtpRequest(@NotBlank @Size(max = 6) String codigo) {
    }

    @GetMapping("/api/catalogos/formas-pago")
    public Map<String, Object> formas() {
        return pagos.formas();
    }

    @GetMapping("/api/servicios/{id}/pago")
    public Map<String, Object> estado(@PathVariable Long id) {
        return pagos.estado(id);
    }

    @PostMapping("/api/servicios/{id}/pago")
    public Map<String, Object> iniciar(@PathVariable Long id, @Valid @RequestBody IniciarPagoRequest req) {
        return pagos.iniciar(id, req.metodo(), req.cuenta(), req.extra());
    }

    /** Bancos para PSE (los de Wompi; en modo simulado, el que aprueba y el que rechaza). */
    @GetMapping("/api/catalogos/bancos-pse")
    public List<Map<String, String>> bancosPse() {
        return pagos.bancosPse();
    }

    @PostMapping("/api/servicios/{id}/pago/otp")
    public Map<String, Object> otp(@PathVariable Long id, @Valid @RequestBody OtpRequest req) {
        return pagos.otp(id, req.codigo());
    }

    @PostMapping("/api/servicios/{id}/pago/cancelar")
    public Map<String, Object> cancelar(@PathVariable Long id) {
        return pagos.cancelar(id);
    }
}
