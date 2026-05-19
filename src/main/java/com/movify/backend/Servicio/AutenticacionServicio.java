package com.movify.backend.Servicio;

import com.movify.backend.Base_de_datos.UsuarioRepositorio;
import com.movify.backend.Modelo.Usuario;
import com.movify.backend.Seguridad.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import jakarta.mail.internet.MimeMessage;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AutenticacionServicio {

    @Autowired
    private UsuarioRepositorio usuarioRepositorio;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private JavaMailSender mailSender;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    // =========================
    // LOGIN NORMAL
    // =========================
    public Map<String, Object> login(String correo, String password) {
        Usuario usuario = usuarioRepositorio.findByCorreo(correo)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        if (!passwordEncoder.matches(password, usuario.getPassword()))
            throw new RuntimeException("Contraseña incorrecta");

        if (!usuario.getEstado().equals("activo"))
            throw new RuntimeException("Usuario inactivo");

        enviarCorreoBienvenida(usuario.getCorreo(), usuario.getNombre());
        return buildRespuesta(usuario, "Login exitoso");
    }

    // =========================
    // REGISTRO
    // =========================
    public Map<String, Object> registro(Map<String, String> datos) {
        if (usuarioRepositorio.existsByCorreo(datos.get("correo")))
            throw new RuntimeException("El correo ya está registrado");

        Usuario nuevo = new Usuario();
        nuevo.setNombre(datos.get("nombre"));
        nuevo.setCorreo(datos.get("correo"));
        nuevo.setPassword(passwordEncoder.encode(datos.get("password")));
        nuevo.setRol(datos.getOrDefault("rol", "cliente"));
        nuevo.setTelefono(datos.get("telefono"));
        nuevo.setEstado("activo");
        usuarioRepositorio.save(nuevo);

        return buildRespuesta(nuevo, "Registro exitoso");
    }

    // =========================
    // LOGIN GOOGLE
    // =========================
    public Map<String, Object> loginGoogle(Map<String, String> datos) {
        Usuario usuario = usuarioRepositorio.findByCorreo(datos.get("correo"))
                .orElseGet(() -> {
                    Usuario nuevo = new Usuario();
                    nuevo.setNombre(datos.get("nombre"));
                    nuevo.setCorreo(datos.get("correo"));
                    nuevo.setFoto(datos.get("foto"));
                    nuevo.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
                    nuevo.setRol("cliente");
                    nuevo.setEstado("activo");
                    return usuarioRepositorio.save(nuevo);
                });

        enviarCorreoBienvenida(usuario.getCorreo(), usuario.getNombre());
        return buildRespuesta(usuario, "Login con Google exitoso");
    }

    // =========================
    // LOGIN FACEBOOK
    // =========================
    public Map<String, Object> loginFacebook(Map<String, String> datos) {
        String correo = datos.get("correo");
        if (correo == null || correo.isEmpty())
            correo = datos.get("facebookId") + "@facebook.movify";

        final String correoFinal = correo;
        Usuario usuario = usuarioRepositorio.findByCorreo(correoFinal)
                .orElseGet(() -> {
                    Usuario nuevo = new Usuario();
                    nuevo.setNombre(datos.get("nombre"));
                    nuevo.setCorreo(correoFinal);
                    nuevo.setFoto(datos.get("foto"));
                    nuevo.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
                    nuevo.setRol("cliente");
                    nuevo.setEstado("activo");
                    return usuarioRepositorio.save(nuevo);
                });

        enviarCorreoBienvenida(usuario.getCorreo(), usuario.getNombre());
        return buildRespuesta(usuario, "Login con Facebook exitoso");
    }

    // =========================
    // RECUPERAR CONTRASEÑA
    // =========================
    public void solicitarRecuperacion(String correo) {
        Usuario usuario = usuarioRepositorio.findByCorreo(correo)
                .orElseThrow(() -> new RuntimeException("No existe una cuenta con ese correo"));

        String token = UUID.randomUUID().toString();
        usuario.setTokenRecuperacion(token);
        usuario.setTokenExpiracion(LocalDateTime.now().plusMinutes(30));
        usuarioRepositorio.save(usuario);

        String enlace = frontendUrl + "/restablecer-password?token=" + token;
        enviarCorreoRecuperacion(correo, usuario.getNombre(), enlace);
    }

    public void restablecerPassword(String token, String nuevaPassword) {
        Usuario usuario = usuarioRepositorio.findByTokenRecuperacion(token)
                .orElseThrow(() -> new RuntimeException("Token inválido o expirado"));

        if (usuario.getTokenExpiracion().isBefore(LocalDateTime.now()))
            throw new RuntimeException("El enlace ha expirado");

        usuario.setPassword(passwordEncoder.encode(nuevaPassword));
        usuario.setTokenRecuperacion(null);
        usuario.setTokenExpiracion(null);
        usuarioRepositorio.save(usuario);
    }

    // =========================
    // CORREO BIENVENIDA
    // =========================
    private void enviarCorreoBienvenida(String destinatario, String nombre) {
        try {
            MimeMessage mensaje = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mensaje, true, "UTF-8");
            helper.setFrom("movify2.app@gmail.com");
            helper.setTo(destinatario);
            helper.setSubject("¡Bienvenido a MoviFY, " + nombre + "!");
            helper.setText(
                    "<div style='font-family:Segoe UI,sans-serif;max-width:520px;margin:auto;border-radius:16px;overflow:hidden;'>"
                            +
                            "<div style='background:linear-gradient(135deg,#1a1a2e,#0f3460);padding:32px;text-align:center;'>"
                            +
                            "<h1 style='color:#4ade80;margin:0;font-size:36px;'>MoviFY</h1>" +
                            "<p style='color:#94a3b8;margin:8px 0 0;'>Tu aliado en movilidad</p></div>" +
                            "<div style='background:#f8fafc;padding:36px 32px;'>" +
                            "<h2 style='color:#1a1a2e;'>¡Hola, " + nombre + "! 👋</h2>" +
                            "<p style='color:#475569;'>Has iniciado sesión exitosamente en <strong>MoviFY</strong>.</p>"
                            +
                            "<div style='background:white;border-radius:12px;padding:24px;margin:20px 0;border:1px solid #e2e8f0;'>"
                            +
                            "<p style='font-weight:700;color:#1a1a2e;margin:0 0 16px;'>Nuestros servicios:</p>" +
                            "<p style='margin:8px 0;'>🏍️ <strong>Transporte</strong> — Viaja rápido en moto</p>" +
                            "<p style='margin:8px 0;'>🛵 <strong>Domicilios</strong> — Recibe en tu puerta</p>" +
                            "<p style='margin:8px 0;'>📦 <strong>Encomiendas</strong> — Envía paquetes</p></div>" +
                            "</div></div>",
                    true);
            mailSender.send(mensaje);
        } catch (Exception e) {
            System.err.println("Error enviando correo bienvenida: " + e.getMessage());
        }
    }

    // =========================
    // CORREO RECUPERACIÓN
    // =========================
    private void enviarCorreoRecuperacion(String destinatario, String nombre, String enlace) {
        try {
            MimeMessage mensaje = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mensaje, true, "UTF-8");
            helper.setFrom("movify2.app@gmail.com");
            helper.setTo(destinatario);
            helper.setSubject("Recuperar contraseña - MoviFY");
            helper.setText(
                    "<div style='font-family:Segoe UI,sans-serif;max-width:500px;margin:auto;'>" +
                            "<div style='background:#1a1a2e;padding:20px;text-align:center;border-radius:12px 12px 0 0;'>"
                            +
                            "<h1 style='color:#4ade80;margin:0;'>MoviFY</h1></div>" +
                            "<div style='background:#f9f9f9;padding:30px;border-radius:0 0 12px 12px;'>" +
                            "<h2 style='color:#1a1a2e;'>Hola, " + nombre + "</h2>" +
                            "<p style='color:#555;'>Recibimos una solicitud para restablecer tu contraseña.</p>" +
                            "<a href='" + enlace + "' style='display:inline-block;margin:20px 0;" +
                            "background:#4ade80;color:#1a1a2e;padding:14px 28px;" +
                            "border-radius:10px;text-decoration:none;font-weight:bold;'>Restablecer contraseña</a>" +
                            "<p style='color:#999;font-size:13px;'>Este enlace expira en 30 minutos.</p>" +
                            "<p style='color:#999;font-size:13px;'>Si no solicitaste esto, ignora este correo.</p>" +
                            "</div></div>",
                    true);
            mailSender.send(mensaje);
        } catch (Exception e) {
            throw new RuntimeException("Error al enviar correo de recuperación");
        }
    }

    // =========================
    // HELPER
    // =========================
    private Map<String, Object> buildRespuesta(Usuario usuario, String mensaje) {
        String token = jwtUtil.generarToken(usuario.getCorreo(), usuario.getRol());
        Map<String, Object> respuesta = new HashMap<>();
        respuesta.put("token", token);
        respuesta.put("correo", usuario.getCorreo());
        respuesta.put("rol", usuario.getRol());
        respuesta.put("nombre", usuario.getNombre());
        respuesta.put("foto", usuario.getFoto() != null ? usuario.getFoto() : "");
        respuesta.put("mensaje", mensaje);
        return respuesta;
    }
}