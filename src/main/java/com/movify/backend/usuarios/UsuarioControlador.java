package com.movify.backend.usuarios;

import java.util.List;
import java.util.Map;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.movify.backend.comun.ArchivoServicio;
import com.movify.backend.configuracion.ContextoPeticion;
import com.movify.backend.usuarios.UsuarioDtos.ActualizarPerfilRequest;
import com.movify.backend.usuarios.UsuarioDtos.CambiarPasswordRequest;
import com.movify.backend.usuarios.UsuarioDtos.CodigoTelefonoRequest;
import com.movify.backend.usuarios.UsuarioDtos.ContactoRequest;
import com.movify.backend.usuarios.UsuarioDtos.DireccionRequest;
import com.movify.backend.usuarios.UsuarioDtos.DispositivoRequest;
import com.movify.backend.usuarios.UsuarioDtos.VerificarTelefonoRequest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/** Perfil del usuario autenticado. El id sale del token, nunca de la URL. */
@RestController
@RequestMapping("/api/usuarios")
public class UsuarioControlador {

    private final UsuarioServicio usuarios;

    public UsuarioControlador(UsuarioServicio usuarios) {
        this.usuarios = usuarios;
    }

    // ───────────── Perfil ─────────────

    @GetMapping("/perfil")
    public Map<String, Object> perfil() {
        return usuarios.perfil();
    }

    @PutMapping("/perfil")
    public Map<String, Object> actualizar(@Valid @RequestBody ActualizarPerfilRequest req) {
        return usuarios.actualizar(req);
    }

    /** Web: contraseña (8+ caracteres). App: clave de 4 números. */
    @PutMapping("/password")
    public Map<String, Object> cambiarPassword(@Valid @RequestBody CambiarPasswordRequest req) {
        usuarios.cambiarPassword(req);
        return Map.of("message", "Contraseña actualizada correctamente");
    }

    // ───────────── Foto ─────────────

    @PostMapping("/perfil/foto")
    public Map<String, Object> subirFoto(@RequestParam("foto") MultipartFile foto) {
        return Map.of("foto", usuarios.subirFoto(foto));
    }

    /** Fotos de perfil: cualquier usuario con sesión (el cliente ve la del conductor y al revés). */
    @GetMapping("/{id}/foto/{nombre:.+}")
    public ResponseEntity<Resource> verFoto(@PathVariable Long id, @PathVariable String nombre) {
        ArchivoServicio.Archivo a = usuarios.foto(id, nombre);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(a.contentType()))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(7)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff").body(a.recurso());
    }

    // ───────────── Verificar o cambiar el celular (web y app) ─────────────

    @PostMapping("/telefono/codigo")
    public Map<String, Object> codigoTelefono(@Valid @RequestBody CodigoTelefonoRequest req, HttpServletRequest http) {
        return usuarios.codigoTelefono(req.celular(), ContextoPeticion.de(http).ip());
    }

    /** Después de /api/auth/codigo/verificar con proposito "cambio_telefono". */
    @PostMapping("/telefono/verificar")
    public Map<String, Object> verificarTelefono(@Valid @RequestBody VerificarTelefonoRequest req) {
        return Map.of("message", "Celular verificado", "telefono", usuarios.verificarTelefono(req.verificacionToken()));
    }

    // ───────────── Direcciones favoritas ─────────────

    @GetMapping("/direcciones")
    public List<Map<String, Object>> direcciones() {
        return usuarios.direcciones();
    }

    @PostMapping("/direcciones")
    public Map<String, Object> guardarDireccion(@Valid @RequestBody DireccionRequest d) {
        return Map.of("id", usuarios.guardarDireccion(d), "message", "Dirección guardada");
    }

    @DeleteMapping("/direcciones/{id}")
    public Map<String, Object> borrarDireccion(@PathVariable Long id) {
        usuarios.borrarDireccion(id);
        return Map.of("message", "Dirección eliminada");
    }

    // ───────────── Contactos de emergencia (SOS) ─────────────

    @GetMapping("/contactos-emergencia")
    public List<Map<String, Object>> contactos() {
        return usuarios.contactos();
    }

    @PostMapping("/contactos-emergencia")
    public Map<String, Object> agregarContacto(@Valid @RequestBody ContactoRequest c) {
        return Map.of("id", usuarios.agregarContacto(c), "message", "Contacto agregado");
    }

    @DeleteMapping("/contactos-emergencia/{id}")
    public Map<String, Object> borrarContacto(@PathVariable Long id) {
        usuarios.borrarContacto(id);
        return Map.of("message", "Contacto eliminado");
    }

    // ───────────── Dispositivo (notificaciones push) ─────────────

    @PostMapping("/dispositivos")
    public Map<String, Object> registrarDispositivo(@Valid @RequestBody DispositivoRequest d) {
        return Map.of("id", usuarios.registrarDispositivo(d));
    }

    // ───────────── Eliminar la cuenta (Ley 1581 y Google Play) ─────────────

    @DeleteMapping("/cuenta")
    @PreAuthorize("hasAuthority('perfil.eliminar_cuenta')")
    public Map<String, Object> eliminarCuenta() {
        usuarios.eliminarCuenta();
        return Map.of("message", "Tu cuenta fue eliminada");
    }
}
