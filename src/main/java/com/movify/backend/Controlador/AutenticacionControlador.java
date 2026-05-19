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

    @PostMapping("/login-google")
    public ResponseEntity<?> loginGoogle(@RequestBody Map<String, String> data) {

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
