package com.movify.backend.servicios;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.movify.backend.servicios.encomiendas.Bultos;
import com.movify.backend.servicios.encomiendas.EncomiendaCotizador;
import com.movify.backend.servicios.encomiendas.EncomiendaDtos.CotizarRequest;
import com.movify.backend.servicios.encomiendas.EncomiendaDtos.NovedadRequest;

import jakarta.validation.Valid;

/**
 * Encomiendas. Se piden con POST /api/servicios (tipo "encomienda") y siguen los mismos estados
 * que los demás servicios; aquí va lo propio del paquete.
 *
 *   POST /api/encomiendas/cotizar                  precio con desglose por vehículo (moto, taxi) y si cabe
 *   GET  /api/servicios/{id}/encomienda            bultos, guía, cotización, novedades; PIN y enlace solo para quien la pidió
 *   POST /api/servicios/{id}/encomienda/novedades  el conductor asignado reporta algo de la recogida o la entrega
 *   GET  /api/publico/encomiendas/{token}          rastreo sin cuenta (datos limitados, vence solo)
 */
@RestController
public class EncomiendaControlador {

    private final ViajeServicio viajes;
    private final EncomiendaServicio encomiendas;
    private final EncomiendaCotizador cotizador;
    private final TarifaServicio tarifas;

    public EncomiendaControlador(ViajeServicio viajes, EncomiendaServicio encomiendas, EncomiendaCotizador cotizador,
            TarifaServicio tarifas) {
        this.viajes = viajes;
        this.encomiendas = encomiendas;
        this.cotizador = cotizador;
        this.tarifas = tarifas;
    }

    @PostMapping("/api/encomiendas/cotizar")
    public Map<String, Object> cotizar(@Valid @RequestBody CotizarRequest req) {
        TarifaServicio.Ruta ruta = tarifas.ruta(req.origenLat(), req.origenLng(), req.destinoLat(), req.destinoLng());
        List<EncomiendaCotizador.Bulto> bultos = Bultos.normalizar(req.paquetes(), null, null, null, null, null, null);
        EncomiendaCotizador.Parametros p = cotizador.parametros();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("distancia_km", ruta.distanciaKm());
        r.put("duracion_min", ruta.duracionMin());
        r.put("opciones", cotizador.opciones(bultos, ruta).stream().map(EncomiendaCotizador.Resultado::comoMapa).toList());
        // Lo que la app explica en pantalla ("5 kg incluidos", "hasta 10 paquetes")
        r.put("kg_incluidos", p.kgIncluidos());
        r.put("factor_volumetrico", p.factorVolumetrico());
        r.put("max_bultos", p.maxBultos());
        r.put("max_valor_declarado", p.maxValorDeclarado());
        return r;
    }

    @GetMapping("/api/servicios/{id}/encomienda")
    public Map<String, Object> detalle(@PathVariable Long id) {
        return encomiendas.detalle(viajes.obtener(id)); // obtener valida que pueda verlo
    }

    @PostMapping("/api/servicios/{id}/encomienda/novedades")
    public Map<String, Object> novedad(@PathVariable Long id, @Valid @RequestBody NovedadRequest req) {
        return encomiendas.registrarNovedad(id, req);
    }

    @GetMapping("/api/publico/encomiendas/{token}")
    public ResponseEntity<Map<String, Object>> rastreo(@PathVariable String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Robots-Tag", "noindex").body(encomiendas.rastreoPublico(token));
    }
}
