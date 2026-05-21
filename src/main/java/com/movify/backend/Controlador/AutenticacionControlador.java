package com.movify.backend.Controlador;

import com.movify.backend.Modelo.Usuario;
import com.movify.backend.Base_de_datos.UsuarioRepositorio;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "http://localhost:4200")
public class AutenticacionControlador {

    @Autowired
    private UsuarioRepositorio usuarioRepositorio;

    // =========================
    // LOGIN NORMAL
    // =========================

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @RequestBody Map<String, String> data) {

        String correo = data.get("correo");
        String password = data.get("password");

        Optional<Usuario> usuarioOptional = usuarioRepositorio.findByCorreo(correo);

        // VALIDAR USUARIO

        if (usuarioOptional.isEmpty()) {

            return ResponseEntity
                    .badRequest()
                    .body(Map.of(
                            "error",
                            "Correo no encontrado"));
        }

        Usuario usuario = usuarioOptional.get();

        // VALIDAR PASSWORD

        if (!usuario.getPassword().equals(password)) {

            return ResponseEntity
                    .badRequest()
                    .body(Map.of(
                            "error",
                            "Contraseña incorrecta"));
        }

        // RESPUESTA

        Map<String, Object> response = new HashMap<>();

        response.put("token", "login-exitoso");
        response.put("rol", usuario.getRol());
        response.put("nombre", usuario.getNombre());
        response.put("foto", usuario.getFoto());

        return ResponseEntity.ok(response);
    }

    // =========================
    // LOGIN GOOGLE
    // =========================

    @PostMapping("/login-google")
    public ResponseEntity<?> loginGoogle(
            @RequestBody Map<String, String> data) {

        String correo = data.get("correo");

        Optional<Usuario> usuarioExistente = usuarioRepositorio.findByCorreo(correo);

        Usuario usuario;

        if (usuarioExistente.isPresent()) {

            usuario = usuarioExistente.get();

        } else {

            usuario = new Usuario();

            usuario.setNombre(data.get("nombre"));
            usuario.setCorreo(correo);
            usuario.setFoto(data.get("foto"));
            usuario.setRol("usuario");

            usuarioRepositorio.save(usuario);
        }

        Map<String, Object> response = new HashMap<>();

        response.put("token", "google-login");
        response.put("rol", usuario.getRol());
        response.put("nombre", usuario.getNombre());
        response.put("foto", usuario.getFoto());

        return ResponseEntity.ok(response);
    }
}