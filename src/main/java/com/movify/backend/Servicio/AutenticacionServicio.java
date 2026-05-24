package com.movify.backend.Servicio;

import com.movify.backend.Base_de_datos.UsuarioRepositorio;
import com.movify.backend.Modelo.Usuario;
import com.movify.backend.Seguridad.JwtUtil;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutenticacionServicio {

    private final UsuarioRepositorio usuarioRepositorio;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final JavaMailSender mailSender;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    @Value("${spring.mail.username}")
    private String emailFrom;

    // =========================
    // LOGIN
    // =========================
    public Map<String, Object> login(String correo, String password) {
        Usuario usuario = usuarioRepositorio.findByCorreo(correo)
                .orElseThrow(() -> new RuntimeException("Credenciales inválidas"));

        if (!passwordEncoder.matches(password, usuario.getPassword())) {
            throw new RuntimeException("Credenciales inválidas");
        }

        if (!"activo".equalsIgnoreCase(usuario.getEstado())) {
            throw new RuntimeException("Cuenta suspendida o inactiva");
        }

        // Intento de envío de correo de notificación de inicio de sesión
        try {
            enviarCorreoBienvenida(usuario.getCorreo(), usuario.getNombre());
        } catch (Exception e) {
            log.error("No se pudo enviar el correo de notificación a {}", correo);
        }

        return buildRespuesta(usuario, "Login exitoso");
    }

    // =========================
    // REGISTRO
    // =========================
    @Transactional
    public Map<String, Object> registro(Map<String, String> datos) {
        if (usuarioRepositorio.existsByCorreo(datos.get("correo"))) {
            throw new RuntimeException("El correo ya está registrado");
        }

        Usuario nuevo = new Usuario();
        nuevo.setNombre(datos.get("nombre"));
        nuevo.setCorreo(datos.get("correo"));
        nuevo.setPassword(passwordEncoder.encode(datos.get("password")));
        nuevo.setRol(datos.getOrDefault("rol", "cliente").toLowerCase());
        nuevo.setTelefono(datos.get("telefono"));
        nuevo.setEstado("activo");
        nuevo.setFechaRegistro(LocalDateTime.now());

        usuarioRepositorio.save(nuevo);

        // Envío de bienvenida tras registro exitoso
        try {
            enviarCorreoBienvenida(nuevo.getCorreo(), nuevo.getNombre());
        } catch (Exception e) {
            log.error("Error al enviar bienvenida tras registro a {}", nuevo.getCorreo());
        }

        return buildRespuesta(nuevo, "Registro exitoso");
    }

    // =========================
    // LOGIN REDES SOCIALES
    // =========================
    @Transactional
    public Map<String, Object> loginSocial(Map<String, String> datos, String proveedor) {
        String correo = datos.get("correo");
        
        if (correo == null || correo.isEmpty()) {
            correo = datos.get("id") + "@social." + proveedor + ".com";
        }

        final String correoFinal = correo;
        Usuario usuario = usuarioRepositorio.findByCorreo(correoFinal)
                .orElseGet(() -> {
                    Usuario nuevo = new Usuario();
                    nuevo.setNombre(datos.get("nombre"));
                    nuevo.setCorreo(correoFinal);
                    nuevo.setFoto(datos.get("foto"));
                    nuevo.setPassword(passwordEncoder.encode("SOCIAL_AUTH_" + UUID.randomUUID()));
                    nuevo.setRol("cliente");
                    nuevo.setEstado("activo");
                    nuevo.setFechaRegistro(LocalDateTime.now());
                    return usuarioRepositorio.save(nuevo);
                });

        return buildRespuesta(usuario, "Login con " + proveedor + " exitoso");
    }

    // =========================
    // RECUPERACIÓN
    // =========================
    @Transactional
    public void solicitarRecuperacion(String correo) {
        Usuario usuario = usuarioRepositorio.findByCorreo(correo)
                .orElseThrow(() -> new RuntimeException("Si el correo existe, recibirás un enlace pronto"));

        String token = UUID.randomUUID().toString();
        usuario.setTokenRecuperacion(token);
        usuario.setTokenExpiracion(LocalDateTime.now().plusMinutes(30));
        usuarioRepositorio.save(usuario);

        String enlace = frontendUrl + "/restablecer-password?token=" + token;
        enviarCorreoTemplate(correo, "Recuperar contraseña - MoviFY", buildHtmlRecuperacion(usuario.getNombre(), enlace));
    }

    // =========================
    // MÉTODOS PRIVADOS DE APOYO
    // =========================
    
    /**
     * Implementación del método que faltaba para evitar el error de compilación.
     */
    private void enviarCorreoBienvenida(String correo, String nombre) {
        String html = """
            <div style='font-family: sans-serif; text-align: center; border: 1px solid #eee; padding: 20px;'>
                <h1 style='color: #4ade80;'>¡Bienvenido a MoviFY!</h1>
                <p>Hola <strong>%s</strong>, nos alegra que estés aquí.</p>
                <p>Tu cuenta ha sido activada exitosamente. Ya puedes empezar a viajar o realizar envíos.</p>
                <hr style='border: 0; border-top: 1px solid #eee; margin: 20px 0;'>
                <small style='color: #888;'>Si no creaste esta cuenta, por favor contáctanos.</small>
            </div>
            """.formatted(nombre);

        enviarCorreoTemplate(correo, "¡Bienvenido a MoviFY!", html);
    }

    private void enviarCorreoTemplate(String destinatario, String asunto, String html) {
        try {
            MimeMessage mensaje = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mensaje, true, "UTF-8");
            helper.setFrom(emailFrom);
            helper.setTo(destinatario);
            helper.setSubject(asunto);
            helper.setText(html, true);
            mailSender.send(mensaje);
            log.info("Correo '{}' enviado a {}", asunto, destinatario);
        } catch (MessagingException e) {
            log.error("Error crítico al enviar email: {}", e.getMessage());
        }
    }

    private Map<String, Object> buildRespuesta(Usuario usuario, String mensaje) {
        Map<String, Object> respuesta = new HashMap<>();
        respuesta.put("token", jwtUtil.generarToken(usuario.getCorreo(), usuario.getRol()));
        
        // Estructura de usuario para el frontend
        Map<String, String> userMap = new HashMap<>();
        userMap.put("nombre", usuario.getNombre());
        userMap.put("correo", usuario.getCorreo());
        userMap.put("rol", usuario.getRol());
        userMap.put("foto", usuario.getFoto() != null ? usuario.getFoto() : "");
        
        respuesta.put("user", userMap);
        respuesta.put("mensaje", mensaje);
        return respuesta;
    }

    private String buildHtmlRecuperacion(String nombre, String enlace) {
        return """
            <div style='font-family: sans-serif; text-align: center; padding: 20px;'>
                <h2>Hola %s</h2>
                <p>Recibimos una solicitud para restablecer tu contraseña.</p>
                <p>Haz clic en el botón de abajo para continuar:</p>
                <a href='%s' style='background: #4ade80; color: white; padding: 12px 25px; text-decoration: none; border-radius: 5px; display: inline-block; font-weight: bold;'>
                    Restablecer Contraseña
                </a>
                <p style='margin-top: 20px; font-size: 0.8em; color: #666;'>Este enlace expirará en 30 minutos.</p>
            </div>
            """.formatted(nombre, enlace);
    }
}