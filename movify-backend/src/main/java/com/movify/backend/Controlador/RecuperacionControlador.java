package com.movify.backend.Controlador;

import com.movify.backend.Servicio.RecuperacionServicio;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class RecuperacionControlador {

    @Autowired
    private RecuperacionServicio recuperacionServicio;

    // POST /api/auth/olvide-password
    @PostMapping("/olvide-password")
    public ResponseEntity<?> olvidePasword(@RequestBody Map<String, String> body) {
        try {
            recuperacionServicio.solicitarRecuperacion(body.get("correo"));
            return ResponseEntity.ok(Map.of("mensaje",
                "Te enviamos un enlace a tu correo"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        }
    }

    // POST /api/auth/restablecer-password
    @PostMapping("/restablecer-password")
    public ResponseEntity<?> restablecerPassword(@RequestBody Map<String, String> body) {
        try {
            recuperacionServicio.restablecerPassword(
                body.get("token"),
                body.get("password")
            );
            return ResponseEntity.ok(Map.of("mensaje",
                "Contraseña actualizada correctamente"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        }
    }
}