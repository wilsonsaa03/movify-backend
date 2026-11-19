package com.movify.backend.servicios;

import java.util.List;
import java.util.Map;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.movify.backend.comun.ArchivoServicio;

/**
 *   POST /api/servicios/{id}/evidencias                 multipart: foto (JPG/PNG/WEBP) y tipo (entrega | recogida | factura_compra)
 *   GET  /api/servicios/{id}/evidencias                 lista (cliente, conductor, personal)
 *   GET  /api/servicios/{id}/evidencias/{eid}/archivo   la foto
 */
@RestController
@RequestMapping("/api/servicios/{id}/evidencias")
public class EvidenciaControlador {

    private final EvidenciaServicio evidencias;

    public EvidenciaControlador(EvidenciaServicio evidencias) {
        this.evidencias = evidencias;
    }

    @PostMapping
    public Map<String, Object> subir(@PathVariable Long id, @RequestParam("foto") MultipartFile foto,
            @RequestParam(defaultValue = EvidenciaServicio.ENTREGA) String tipo) {
        return evidencias.guardar(id, tipo, foto);
    }

    @GetMapping
    public List<Map<String, Object>> listar(@PathVariable Long id) {
        return evidencias.listar(id);
    }

    @GetMapping("/{eid}/archivo")
    public ResponseEntity<Resource> archivo(@PathVariable Long id, @PathVariable Long eid) {
        ArchivoServicio.Archivo a = evidencias.archivo(id, eid);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(a.contentType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .body(a.recurso());
    }
}
