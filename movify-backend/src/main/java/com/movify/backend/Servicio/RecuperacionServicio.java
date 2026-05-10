package com.movify.backend.Servicio;

import com.movify.backend.Base_de_datos.TokenRecuperacionRepositorio;
import com.movify.backend.Base_de_datos.UsuarioRepositorio;
import com.movify.backend.Modelo.TokenRecuperacion;
import com.movify.backend.Modelo.Usuario;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class RecuperacionServicio {

    @Autowired
    private UsuarioRepositorio usuarioRepositorio;

    @Autowired
    private TokenRecuperacionRepositorio tokenRepositorio;

    @Autowired
    private EmailServicio emailServicio;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    // Paso 1: recibe el correo y envía el enlace
    public void solicitarRecuperacion(String correo) {
        Usuario usuario = usuarioRepositorio.findByCorreo(correo)
            .orElseThrow(() -> new RuntimeException("No existe una cuenta con ese correo"));

        // Generar token único
        String token = UUID.randomUUID().toString();

        // Guardar token en BD
        TokenRecuperacion tokenRecuperacion = new TokenRecuperacion();
        tokenRecuperacion.setToken(token);
        tokenRecuperacion.setUsuario(usuario);
        tokenRecuperacion.setFechaExpiracion(LocalDateTime.now().plusMinutes(30));
        tokenRecuperacion.setUsado(false);
        tokenRepositorio.save(tokenRecuperacion);

        // Enviar correo con enlace
        String enlace = frontendUrl + "/restablecer-password?token=" + token;
        emailServicio.enviarCorreoRecuperacion(correo, enlace);
    }

    // Paso 2: recibe el token y la nueva contraseña
    public void restablecerPassword(String token, String nuevaPassword) {
        TokenRecuperacion tokenRecuperacion = tokenRepositorio.findByToken(token)
            .orElseThrow(() -> new RuntimeException("Token inválido"));

        if (tokenRecuperacion.getUsado()) {
            throw new RuntimeException("Este enlace ya fue usado");
        }

        if (tokenRecuperacion.getFechaExpiracion().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("El enlace ha expirado");
        }

        // Actualizar contraseña
        Usuario usuario = tokenRecuperacion.getUsuario();
        usuario.setPassword(passwordEncoder.encode(nuevaPassword));
        usuarioRepositorio.save(usuario);

        // Marcar token como usado
        tokenRecuperacion.setUsado(true);
        tokenRepositorio.save(tokenRecuperacion);
    }
}