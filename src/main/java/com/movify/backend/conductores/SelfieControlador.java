package com.movify.backend.conductores;

import java.util.List;
import java.util.Map;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.movify.backend.comun.ArchivoServicio;
import com.movify.backend.seguridad.SesionActual;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * Selfie del conductor:
 *   GET  /api/conductor/selfie                    ¿la necesita para conectarse?
 *   POST /api/conductor/selfie                    multipart: foto (cámara frontal)
 * Panel (soporte, permiso admin_conductores.*):
 *   GET  /api/admin/selfies?estado=pendiente      lista con la foto de perfil para comparar
 *   GET  /api/admin/selfies/{id}/archivo          la selfie
 *   POST /api/admin/selfies/{id}/revisar          { aprobar: true|false, motivo }
 */
@RestController
public class SelfieControlador {

    private final SelfieServicio selfies;
    private final SesionActual sesion;

    public SelfieControlador(SelfieServicio selfies, SesionActual sesion) {
        this.selfies = selfies;
        this.sesion = sesion;
    }

    @GetMapping("/api/conductor/selfie")
    public Map<String, Object> estado() {
        return selfies.estado(sesion.conductorId());
    }

    @PostMapping("/api/conductor/selfie")
    public Map<String, Object> subir(@RequestParam("foto") MultipartFile foto) {
        return selfies.guardar(sesion.conductorId(), foto);
    }

    @GetMapping("/api/admin/selfies")
    @PreAuthorize("hasAuthority('admin_conductores.ver')")
    public List<Map<String, Object>> listar(@RequestParam(required = false) String estado) {
        return selfies.listar(estado);
    }

    @GetMapping("/api/admin/selfies/{id}/archivo")
    @PreAuthorize("hasAuthority('admin_conductores.ver')")
    public ResponseEntity<Resource> archivo(@PathVariable Long id) {
        ArchivoServicio.Archivo a = selfies.archivo(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(a.contentType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .body(a.recurso());
    }

    public record RevisarRequest(boolean aprobar, @Size(max = 200) String motivo) {
    }

    @PostMapping("/api/admin/selfies/{id}/revisar")
    @PreAuthorize("hasAuthority('admin_conductores.aprobar')")
    public Map<String, Object> revisar(@PathVariable Long id, @Valid @RequestBody RevisarRequest req) {
        return selfies.revisar(id, req.aprobar(), req.motivo(), sesion.usuarioId(), sesion.actorPersonal());
    }
}
