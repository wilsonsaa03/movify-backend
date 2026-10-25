package com.movify.backend.servicios;

import java.util.List;
import java.util.Map;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.movify.backend.comun.ArchivoServicio;
import com.movify.backend.servicios.ServicioDtos.AlertaViajeRequest;
import com.movify.backend.servicios.ServicioDtos.CambioDestinoRequest;
import com.movify.backend.servicios.ServicioDtos.SubirTarifaRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * Opciones del viaje para el pasajero (V14):
 *
 *   GET  /api/servicios/cercanos?lat=&lng=&tipo_vehiculo=   conductores libres en el mapa (posición aproximada)
 *   POST /api/servicios/{id}/tarifa       subir la tarifa mientras se busca conductor
 *   POST /api/servicios/{id}/destino      cambiar el destino o la parada con el viaje andando
 *   POST /api/servicios/{id}/compartir    enlace para seguir el viaje en vivo
 *   POST /api/servicios/{id}/alertas      respuesta al "¿Todo bien?" (desvío o parada larga)
 *   PUT/DELETE /api/servicios/{id}/favorito   conductor favorito
 *   POST /api/servicios/{id}/bloquear     no volver a emparejar con ese conductor
 *   GET  /api/servicios/{id}/vehiculo/foto    foto del vehículo asignado
 */
@RestController
@RequestMapping("/api/servicios")
public class ViajeOpcionesControlador {

    private final ViajeOpcionesServicio opciones;
    private final ArchivoServicio archivos;

    public ViajeOpcionesControlador(ViajeOpcionesServicio opciones, ArchivoServicio archivos) {
        this.opciones = opciones;
        this.archivos = archivos;
    }

    @GetMapping("/cercanos")
    public List<Map<String, Object>> cercanos(@RequestParam double lat, @RequestParam double lng,
            @RequestParam(name = "tipo_vehiculo", required = false) String tipoVehiculo) {
        return opciones.cercanos(lat, lng, tipoVehiculo);
    }

    @PostMapping("/{id}/tarifa")
    public Map<String, Object> subirTarifa(@PathVariable Long id, @Valid @RequestBody SubirTarifaRequest req) {
        return opciones.subirTarifa(id, req.valor());
    }

    @PostMapping("/{id}/destino")
    public Map<String, Object> cambiarDestino(@PathVariable Long id, @Valid @RequestBody CambioDestinoRequest req) {
        return opciones.cambiarDestino(id, req);
    }

    @PostMapping("/{id}/compartir")
    public Map<String, Object> compartir(@PathVariable Long id) {
        return opciones.compartir(id);
    }

    @PostMapping("/{id}/alertas")
    public Map<String, Object> alerta(@PathVariable Long id, @Valid @RequestBody AlertaViajeRequest req) {
        return opciones.alerta(id, req);
    }

    @PutMapping("/{id}/favorito")
    public Map<String, Object> marcarFavorito(@PathVariable Long id) {
        return opciones.favorito(id, true);
    }

    @DeleteMapping("/{id}/favorito")
    public Map<String, Object> quitarFavorito(@PathVariable Long id) {
        return opciones.favorito(id, false);
    }

    public record BloqueoRequest(@Size(max = 200) String motivo) {
    }

    @PostMapping("/{id}/bloquear")
    public Map<String, Object> bloquear(@PathVariable Long id, @Valid @RequestBody(required = false) BloqueoRequest req) {
        return opciones.bloquear(id, req == null ? null : req.motivo());
    }

    @GetMapping("/{id}/vehiculo/foto")
    public ResponseEntity<Resource> fotoVehiculo(@PathVariable Long id) {
        ArchivoServicio.Archivo a = opciones.fotoVehiculo(id, archivos);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(a.contentType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(a.recurso());
    }
}
