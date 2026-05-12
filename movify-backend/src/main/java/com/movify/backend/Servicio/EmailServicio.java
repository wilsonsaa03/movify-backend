package com.movify.backend.Servicio;
 
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import jakarta.mail.internet.MimeMessage;
 
@Service
public class EmailServicio {
 
    @Autowired
    private JavaMailSender mailSender;
 
    // =========================
    // CORREO RECUPERACIÓN
    // =========================
 
    public void enviarCorreoRecuperacion(String destinatario, String enlace) {
        try {
            MimeMessage mensaje = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mensaje, true, "UTF-8");
 
            helper.setFrom("movify2.app@gmail.com");
            helper.setTo(destinatario);
            helper.setSubject("Recuperar contraseña - MoviFY");
            helper.setText(
                "<div style='font-family:Segoe UI,sans-serif;max-width:500px;margin:auto;'>" +
                "<div style='background:#4CAF50;padding:20px;text-align:center;border-radius:12px 12px 0 0;'>" +
                "<h1 style='color:white;margin:0;'>MoviFY</h1>" +
                "</div>" +
                "<div style='background:#f9f9f9;padding:30px;border-radius:0 0 12px 12px;'>" +
                "<h2 style='color:#1a1a2e;'>Recuperar contraseña</h2>" +
                "<p style='color:#555;'>Recibimos una solicitud para restablecer tu contraseña.</p>" +
                "<p style='color:#555;'>Haz clic en el botón para crear una nueva contraseña:</p>" +
                "<a href='" + enlace + "' style='display:inline-block;margin:20px 0;" +
                "background:#4CAF50;color:white;padding:14px 28px;" +
                "border-radius:10px;text-decoration:none;font-weight:bold;font-size:16px;'>" +
                "Restablecer contraseña</a>" +
                "<p style='color:#999;font-size:13px;'>Este enlace expira en 30 minutos.</p>" +
                "<p style='color:#999;font-size:13px;'>Si no solicitaste esto, ignora este correo.</p>" +
                "</div></div>",
                true
            );
 
            mailSender.send(mensaje);
        } catch (Exception e) {
            throw new RuntimeException("Error al enviar correo: " + e.getMessage());
        }
    }
 
    // =========================
    // CORREO BIENVENIDA / LOGIN
    // =========================
 
    public void enviarCorreoBienvenida(String destinatario, String nombre) {
        try {
            MimeMessage mensaje = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mensaje, true, "UTF-8");
 
            helper.setFrom("movify2.app@gmail.com");
            helper.setTo(destinatario);
            helper.setSubject("¡Bienvenido a MoviFY, " + nombre + "!");
            helper.setText(
                "<div style='font-family:Segoe UI,sans-serif;max-width:520px;margin:auto;border-radius:16px;overflow:hidden;box-shadow:0 4px 20px rgba(0,0,0,0.1);'>" +
 
                // Header
                "<div style='background:linear-gradient(135deg,#1a1a2e,#0f3460);padding:32px;text-align:center;'>" +
                "<h1 style='color:#4ade80;margin:0;font-size:36px;letter-spacing:2px;'>MoviFY</h1>" +
                "<p style='color:#94a3b8;margin:8px 0 0;font-size:14px;'>Tu aliado en movilidad</p>" +
                "</div>" +
 
                // Body
                "<div style='background:#f8fafc;padding:36px 32px;'>" +
                "<h2 style='color:#1a1a2e;font-size:22px;margin:0 0 12px;'>¡Hola, " + nombre + "! 👋</h2>" +
                "<p style='color:#475569;font-size:15px;line-height:1.7;margin:0 0 24px;'>" +
                "Has iniciado sesión exitosamente en <strong style='color:#1a1a2e;'>MoviFY</strong>. " +
                "Estamos listos para llevarte a donde necesites." +
                "</p>" +
 
                // Servicios
                "<div style='background:white;border-radius:12px;padding:24px;margin-bottom:24px;border:1px solid #e2e8f0;'>" +
                "<p style='color:#1a1a2e;font-weight:700;margin:0 0 16px;font-size:15px;'>Nuestros servicios:</p>" +
 
                "<div style='display:flex;align-items:center;gap:12px;margin-bottom:14px;'>" +
                "<span style='font-size:28px;'>🏍️</span>" +
                "<div><p style='margin:0;font-weight:600;color:#1a1a2e;'>Transporte</p>" +
                "<p style='margin:0;color:#64748b;font-size:13px;'>Viaja rápido y seguro en moto</p></div>" +
                "</div>" +
 
                "<div style='display:flex;align-items:center;gap:12px;margin-bottom:14px;'>" +
                "<span style='font-size:28px;'>🛵</span>" +
                "<div><p style='margin:0;font-weight:600;color:#1a1a2e;'>Domicilios</p>" +
                "<p style='margin:0;color:#64748b;font-size:13px;'>Recibe lo que necesitas en tu puerta</p></div>" +
                "</div>" +
 
                "<div style='display:flex;align-items:center;gap:12px;'>" +
                "<span style='font-size:28px;'>📦</span>" +
                "<div><p style='margin:0;font-weight:600;color:#1a1a2e;'>Encomiendas</p>" +
                "<p style='margin:0;color:#64748b;font-size:13px;'>Envía paquetes de forma rápida</p></div>" +
                "</div>" +
                "</div>" +
 
                "<a href='http://localhost:4200/home-usuario' style='display:block;text-align:center;" +
                "background:#4ade80;color:#1a1a2e;padding:14px 28px;" +
                "border-radius:10px;text-decoration:none;font-weight:700;font-size:16px;'>" +
                "Ir a la app →</a>" +
                "</div>" +
 
                // Footer
                "<div style='background:#1a1a2e;padding:20px;text-align:center;'>" +
                "<p style='color:#475569;font-size:12px;margin:0;'>" +
                "© 2025 MoviFY · Si no iniciaste sesión, ignora este correo." +
                "</p>" +
                "</div>" +
 
                "</div>",
                true
            );
 
            mailSender.send(mensaje);
        } catch (Exception e) {
            // No lanzamos excepción para que el login no falle si el correo falla
            System.err.println("Advertencia: no se pudo enviar correo de bienvenida: " + e.getMessage());
        }
    }
}