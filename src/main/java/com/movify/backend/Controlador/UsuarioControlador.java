package com.movify.backend.Controlador;

import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.movify.backend.Base_de_datos.UsuarioRepositorio;
import com.movify.backend.Comun.RecursoNoEncontradoException;
import com.movify.backend.Comun.Roles;
import com.movify.backend.Comun.ReglaNegocioException;
import com.movify.backend.Dto.AuthDtos.ActualizarPerfilRequest;
import com.movify.backend.Dto.AuthDtos.CambiarPasswordRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.movify.backend.Modelo.Usuario;
import com.movify.backend.Seguridad.SesionActual;

import jakarta.validation.Valid;

/** Perfil del usuario autenticado. El id sale del token, nunca de la URL. */
@RestController
@RequestMapping("/api/usuarios")
public class UsuarioControlador {

    private final UsuarioRepositorio usuarios;
    private final SesionActual sesion;
    private final JdbcTemplate db;
    private final PasswordEncoder encoder;

    public UsuarioControlador(UsuarioRepositorio usuarios, SesionActual sesion, JdbcTemplate db,
            PasswordEncoder encoder) {
        this.usuarios = usuarios;
        this.sesion = sesion;
        this.db = db;
        this.encoder = encoder;
    }

    /** Cambio de contraseña del usuario autenticado (cliente, conductor o admin). */
    @PutMapping("/password")
    public Map<String, Object> cambiarPassword(@Valid @RequestBody CambiarPasswordRequest req) {
        Usuario u = actual();
        if (!encoder.matches(req.actual(), u.getPassword())) {
            throw new ReglaNegocioException(400, "La contraseña actual no es correcta");
        }
        u.setPassword(encoder.encode(req.nueva()));
        usuarios.save(u);
        return Map.of("message", "Contraseña actualizada correctamente");
    }

    @GetMapping("/perfil")
    public Map<String, Object> perfil() {
        Usuario u = actual();
        Map<String, Object> r = datos(u);
        Map<String, Object> stats = db.queryForMap("""
                SELECT COUNT(*) FILTER (WHERE estado = 'FINALIZADO') AS viajes_finalizados,
                       COALESCE(SUM(tarifa) FILTER (WHERE estado = 'FINALIZADO'), 0) AS total_gastado
                FROM servicios WHERE usuario_id = ?
                """, u.getId());
        r.putAll(stats);
        return r;
    }

    @PutMapping("/perfil")
    public Map<String, Object> actualizar(@Valid @RequestBody ActualizarPerfilRequest req) {
        Usuario u = actual();
        if (req.nombre() != null) u.setNombre(req.nombre().trim());
        if (req.telefono() != null) u.setTelefono(req.telefono().trim());
        if (req.foto() != null) u.setFoto(req.foto().trim());
        usuarios.save(u);
        return datos(u);
    }

    private Usuario actual() {
        return usuarios.findById(sesion.usuarioId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario no encontrado"));
    }

    private static Map<String, Object> datos(Usuario u) {
        Map<String, Object> r = new HashMap<>();
        r.put("id", u.getId());
        r.put("nombre", u.getNombre());
        r.put("correo", u.getCorreo());
        r.put("telefono", u.getTelefono());
        r.put("foto", u.getFoto());
        r.put("rol", Roles.normalizar(u.getRol()));
        r.put("fecha_registro", u.getFechaRegistro());
        return r;
    }
}
