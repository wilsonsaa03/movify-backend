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

/**
 * Servicio encargado de gestionar la recuperación de contraseña.
 * Maneja dos pasos:
 *   1. Generar y enviar el enlace de recuperación al correo del usuario.
 *   2. Validar el token y actualizar la contraseña en la base de datos.
 */
@Service
public class RecuperacionServicio {

    // Repositorio para consultar y guardar usuarios en la BD
    @Autowired private UsuarioRepositorio usuarioRepositorio;

    // Repositorio para guardar y buscar tokens de recuperación
    @Autowired private TokenRecuperacionRepositorio tokenRepositorio;

    // Servicio que se encarga de enviar el correo electrónico
    @Autowired private EmailServicio emailServicio;

    // Encriptador de contraseñas con BCrypt
    @Autowired private PasswordEncoder passwordEncoder;

    // URL del frontend, leída desde application.properties
    // Se usa para construir el enlace de recuperación
    @Value("${app.frontend.url}")
    private String frontendUrl;

    // ── PASO 1: SOLICITAR RECUPERACIÓN ────────────────────────────────────
    /**
     * Recibe el correo del usuario, genera un token único,
     * lo guarda en la BD y envía el enlace al correo.
     * El enlace expira en 30 minutos.
     */
    public void solicitarRecuperacion(String correo) {

        // Verificar que el correo exista en la BD
        Usuario usuario = usuarioRepositorio.findByCorreo(correo)
            .orElseThrow(() -> new RuntimeException("No existe una cuenta con ese correo"));

        // Generar un token único e irrepetible
        String token = UUID.randomUUID().toString();

        // Crear el registro del token con su fecha de expiración
        TokenRecuperacion tokenRecuperacion = new TokenRecuperacion();
        tokenRecuperacion.setToken(token);
        tokenRecuperacion.setUsuario(usuario);
        tokenRecuperacion.setFechaExpiracion(LocalDateTime.now().plusMinutes(30));
        tokenRecuperacion.setUsado(false);

        // Guardar el token en la base de datos
        tokenRepositorio.save(tokenRecuperacion);

        // Construir el enlace y enviarlo al correo del usuario
        String enlace = frontendUrl + "/restablecer-password?token=" + token;
        emailServicio.enviarCorreoRecuperacion(correo, enlace);
    }

    // ── PASO 2: RESTABLECER CONTRASEÑA ────────────────────────────────────
    /**
     * Recibe el token del enlace y la nueva contraseña.
     * Valida que el token sea válido, no haya sido usado y no haya expirado.
     * Luego actualiza la contraseña del usuario en la BD.
     */
    public void restablecerPassword(String token, String nuevaPassword) {

        // Buscar el token en la BD, lanzar error si no existe
        TokenRecuperacion tokenRecuperacion = tokenRepositorio.findByToken(token)
            .orElseThrow(() -> new RuntimeException("Token inválido"));

        // Verificar que el token no haya sido usado anteriormente
        if (tokenRecuperacion.getUsado())
            throw new RuntimeException("Este enlace ya fue usado");

        // Verificar que el token no haya expirado (válido por 30 minutos)
        if (tokenRecuperacion.getFechaExpiracion().isBefore(LocalDateTime.now()))
            throw new RuntimeException("El enlace ha expirado");

        // Encriptar la nueva contraseña y actualizarla en la BD
        Usuario usuario = tokenRecuperacion.getUsuario();
        usuario.setPassword(passwordEncoder.encode(nuevaPassword));
        usuarioRepositorio.save(usuario);

        // Marcar el token como usado para que no pueda reutilizarse
        tokenRecuperacion.setUsado(true);
        tokenRepositorio.save(tokenRecuperacion);
    }
}