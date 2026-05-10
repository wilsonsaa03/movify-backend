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
}