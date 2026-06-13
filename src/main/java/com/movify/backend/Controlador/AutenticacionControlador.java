package com.movify.backend.Controlador;

import com.movify.backend.Modelo.Usuario;
import com.movify.backend.Base_de_datos.UsuarioRepositorio;
import com.movify.backend.Servicio.AutenticacionServicio;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.List;

@RestController
@RequestMapping("/api/auth")
public class AutenticacionControlador {

    @Autowired
    private UsuarioRepositorio usuarioRepositorio;

    @Autowired
    private AutenticacionServicio autenticacionServicio;

    @Autowired
    private JdbcTemplate db;

    // =========================
    // REGISTRO DE USUARIO
    // =========================

    @PostMapping("/registro")
    public ResponseEntity<?> registrarUsuario(@RequestBody Map<String, String> data) {
        try {
            String correo = data.get("correo");
            String nombre = data.get("nombre");
            String password = data.get("password");
            String telefono = data.get("telefono");

            if (correo == null || nombre == null || password == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Nombre, correo y contraseña son obligatorios."));
            }

            if (usuarioRepositorio.existsByCorreo(correo)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "El correo ya está registrado."));
            }

            BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

            Usuario usuario = new Usuario();
            usuario.setNombre(nombre);
            usuario.setCorreo(correo);
            usuario.setPassword(encoder.encode(password));
            usuario.setTelefono(telefono);
            usuario.setRol("usuario");
            usuario.setEstado("activo");

            Usuario guardado = usuarioRepositorio.save(usuario);

            return ResponseEntity.ok(Map.of(
                    "message", "Usuario registrado correctamente",
                    "id", guardado.getId(),
                    "rol", guardado.getRol()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error al registrar usuario: " + e.getMessage()));
        }
    }

    // =========================
    // REGISTRO DE ADMIN
    // =========================

    @PostMapping("/registro-admin")
    public ResponseEntity<?> registrarAdmin(@RequestBody Map<String, String> data) {
        try {
            Usuario admin = autenticacionServicio.registrarAdmin(data);
            return ResponseEntity.ok(Map.of(
                    "message", "Administrador registrado correctamente",
                    "id", admin.getId()));
        } catch (Exception e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }

    // =========================
    // LOGIN NORMAL
    // =========================

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> data) {

        String correo = data.get("correo");
        String password = data.get("password");

        Optional<Usuario> usuarioOptional = usuarioRepositorio.findByCorreo(correo);

        if (usuarioOptional.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Correo no encontrado"));
        }

        Usuario usuario = usuarioOptional.get();

        // VALIDAR ESTADO CONDUCTOR (PENDIENTE O RECHAZADO)
        if ("conductor".equalsIgnoreCase(usuario.getRol())) {
            try {
                Map<String, Object> conductorInfo = db.queryForMap(
                        "SELECT estado, motivo_rechazo FROM conductores WHERE usuario_id = ?",
                        usuario.getId());
                String estadoConductor = String.valueOf(conductorInfo.get("estado"));

                if ("pendiente".equalsIgnoreCase(estadoConductor)) {
                    return ResponseEntity.status(403).body(Map.of("error",
                            "Tu cuenta de conductor está en proceso de revisión. Te notificaremos una vez que tus documentos sean aprobados."));
                } else if ("rechazado".equalsIgnoreCase(estadoConductor)) {
                    String motivo = conductorInfo.get("motivo_rechazo") != null
                            ? conductorInfo.get("motivo_rechazo").toString()
                            : "No especificado";
                    return ResponseEntity.status(403).body(Map.of("error",
                            "Tu solicitud de conductor fue rechazada. Motivo: " + motivo
                                    + ". Por favor, corrige la información y vuelve a enviarla."));
                }
            } catch (Exception e) {
                /* Ignorar si no existe registro */
            }
        }

        // VALIDAR ESTADO
        if (usuario.getEstado() != null && !usuario.getEstado().equalsIgnoreCase("activo")) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "Tu cuenta se encuentra " + usuario.getEstado()
                            + ". Contacta al soporte para más información."));
        }

        // VALIDAR PASSWORD
        if (!autenticacionServicio.validarPassword(password, usuario.getPassword())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Contraseña incorrecta"));
        }

        // RESPUESTA
        Map<String, Object> response = new HashMap<>();
        response.put("token", "login-exitoso");
        response.put("id", usuario.getId());
        response.put("rol", usuario.getRol());
        response.put("nombre", usuario.getNombre());
        response.put("foto", usuario.getFoto());

        return ResponseEntity.ok(response);
    }

    // =========================
    // LOGIN GOOGLE
    // =========================

    @PostMapping("/login-google")
    public ResponseEntity<?> loginGoogle(@RequestBody Map<String, String> data) {

        String correo = data.get("correo");

        Optional<Usuario> usuarioExistente = usuarioRepositorio.findByCorreo(correo);

        Usuario usuario;

        if (usuarioExistente.isPresent()) {
            usuario = usuarioExistente.get();

            if ("conductor".equalsIgnoreCase(usuario.getRol())) {
                try {
                    Map<String, Object> conductorInfo = db.queryForMap(
                            "SELECT estado, motivo_rechazo FROM conductores WHERE usuario_id = ?",
                            usuario.getId());
                    String estadoConductor = String.valueOf(conductorInfo.get("estado"));

                    if ("pendiente".equalsIgnoreCase(estadoConductor)) {
                        return ResponseEntity.status(403).body(Map.of("error",
                                "Tu cuenta de conductor está en proceso de revisión. Por favor espera la aprobación del administrador."));
                    } else if ("rechazado".equalsIgnoreCase(estadoConductor)) {
                        String motivo = conductorInfo.get("motivo_rechazo") != null
                                ? conductorInfo.get("motivo_rechazo").toString()
                                : "No especificado";
                        return ResponseEntity.status(403).body(Map.of("error",
                                "Tu solicitud de conductor fue rechazada. Motivo: " + motivo));
                    }
                } catch (Exception e) {
                    /* Ignorar */
                }
            }
        } else {
            usuario = new Usuario();
            usuario.setNombre(data.get("nombre"));
            usuario.setCorreo(correo);
            usuario.setFoto(data.get("foto"));
            usuario.setRol("usuario");
            usuario.setEstado("activo");
            // Generar una contraseña aleatoria para cumplir con la restricción @NotBlank de la BD
            String randomPass = java.util.UUID.randomUUID().toString();
            usuario.setPassword(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(randomPass));
            usuarioRepositorio.save(usuario);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("token", "google-login");
        response.put("rol", usuario.getRol());
        response.put("nombre", usuario.getNombre());
        response.put("foto", usuario.getFoto());

        return ResponseEntity.ok(response);
    }

    // =========================
    // LISTAR TODOS LOS USUARIOS
    // =========================

    @GetMapping("/usuarios")
    public ResponseEntity<?> listarUsuarios() {
        try {
            List<Usuario> usuarios = usuarioRepositorio.findAll();
            usuarios.forEach(u -> u.setPassword(null));
            return ResponseEntity.ok(usuarios);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // =========================
    // CAMBIAR ESTADO DE USUARIO
    // =========================

    @PatchMapping("/usuarios/{id}/estado")
    public ResponseEntity<?> cambiarEstadoUsuario(
            @PathVariable Long id,
            @RequestBody Map<String, String> data) {
        try {
            Usuario usuario = usuarioRepositorio.findById(id)
                    .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
            usuario.setEstado(data.get("estado"));
            usuarioRepositorio.save(usuario);
            return ResponseEntity.ok(Map.of("message", "Estado actualizado correctamente"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
