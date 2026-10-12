package com.movify.backend.Controlador;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.movify.backend.Base_de_datos.UsuarioRepositorio;
import com.movify.backend.Comun.RecursoNoEncontradoException;
import com.movify.backend.Comun.Roles;
import com.movify.backend.Dto.AuthDtos.CambiarEstadoUsuarioRequest;
import com.movify.backend.Dto.AuthDtos.GoogleLoginRequest;
import com.movify.backend.Dto.AuthDtos.LoginRequest;
import com.movify.backend.Dto.AuthDtos.OlvidePasswordRequest;
import com.movify.backend.Dto.AuthDtos.RegistroAdminRequest;
import com.movify.backend.Dto.AuthDtos.RegistroUsuarioRequest;
import com.movify.backend.Dto.AuthDtos.RestablecerPasswordRequest;
import com.movify.backend.Modelo.Usuario;
import com.movify.backend.Servicio.AutenticacionServicio;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AutenticacionControlador {

    private final AutenticacionServicio auth;
    private final UsuarioRepositorio usuarios;

    public AutenticacionControlador(AutenticacionServicio auth, UsuarioRepositorio usuarios) {
        this.auth = auth;
        this.usuarios = usuarios;
    }

    @PostMapping("/registro")
    public Map<String, Object> registrar(@Valid @RequestBody RegistroUsuarioRequest req) {
        Usuario u = auth.registrarCliente(req);
        return Map.of("message", "Usuario registrado correctamente", "id", u.getId(), "rol", u.getRol());
    }

    @PostMapping("/registro-admin")
    public Map<String, Object> registrarAdmin(@Valid @RequestBody RegistroAdminRequest req) {
        Usuario admin = auth.registrarAdmin(req);
        return Map.of("message", "Administrador registrado correctamente", "id", admin.getId());
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest req) {
        return auth.login(req);
    }

    /** El navegador manda solo el id_token de Google; nombre y correo se leen del token verificado. */
    @PostMapping("/login-google")
    public Map<String, Object> loginGoogle(@Valid @RequestBody GoogleLoginRequest req) {
        return auth.loginGoogle(req.token());
    }

    @PostMapping("/olvide-password")
    public Map<String, Object> olvidePassword(@Valid @RequestBody OlvidePasswordRequest req) {
        auth.solicitarRecuperacion(req.correo());
        return Map.of("message", "Si el correo está registrado, te enviamos un enlace para restablecer la contraseña.");
    }

    @PostMapping("/restablecer-password")
    public Map<String, Object> restablecer(@Valid @RequestBody RestablecerPasswordRequest req) {
        auth.restablecer(req.token(), req.password());
        return Map.of("message", "Contraseña actualizada. Ya puedes iniciar sesión.");
    }

    // ───────────── Administración de usuarios (reglas en SecurityConfig) ─────────────

    @GetMapping("/usuarios")
    public List<Map<String, Object>> listarUsuarios() {
        return usuarios.findAll().stream().map(u -> Map.<String, Object>of(
                "id", u.getId(),
                "nombre", nvl(u.getNombre()),
                "correo", nvl(u.getCorreo()),
                "telefono", nvl(u.getTelefono()),
                "rol", Roles.normalizar(u.getRol()),
                "estado", nvl(u.getEstado()),
                "fechaRegistro", u.getFechaRegistro() == null ? "" : u.getFechaRegistro().toString()))
                .toList();
    }

    @PatchMapping("/usuarios/{id}/estado")
    public ResponseEntity<Map<String, Object>> cambiarEstadoUsuario(@PathVariable Long id,
            @Valid @RequestBody CambiarEstadoUsuarioRequest req) {
        Usuario u = usuarios.findById(id).orElseThrow(() -> new RecursoNoEncontradoException("Usuario no encontrado"));
        u.setEstado(req.estado());
        usuarios.save(u);
        return ResponseEntity.ok(Map.of("message", "Estado actualizado correctamente"));
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
