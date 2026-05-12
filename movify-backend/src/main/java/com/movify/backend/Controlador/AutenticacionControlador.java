package com.movify.backend.Controlador;

import com.movify.backend.Servicio.AutenticacionServicio;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AutenticacionControlador {

    @Autowired
    private AutenticacionServicio autenticacionServicio;

    @GetMapping("/ping")
    public ResponseEntity<?> ping() {
        return ResponseEntity.ok(Map.of("estado", "MoviFY Backend activo"));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credenciales) {
        try {
            return ResponseEntity.ok(autenticacionServicio.login(
                credenciales.get("correo"),
                credenciales.get("password")
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/registro")
    public ResponseEntity<?> registro(@RequestBody Map<String, String> datos) {
        try {
            return ResponseEntity.ok(autenticacionServicio.registro(datos));
        } catch (RuntimeException e) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/login-google")
    public ResponseEntity<?> loginGoogle(@RequestBody Map<String, String> datos) {
        try {
            return ResponseEntity.ok(autenticacionServicio.loginGoogle(datos));
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/login-facebook")
    public ResponseEntity<?> loginFacebook(@RequestBody Map<String, String> datos) {
        try {
            return ResponseEntity.ok(autenticacionServicio.loginFacebook(datos));
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }
}