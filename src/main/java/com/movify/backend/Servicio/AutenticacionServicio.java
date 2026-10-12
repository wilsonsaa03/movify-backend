package com.movify.backend.Servicio;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.movify.backend.Base_de_datos.UsuarioRepositorio;
import com.movify.backend.Comun.AccesoDenegadoException;
import com.movify.backend.Comun.ReglaNegocioException;
import com.movify.backend.Comun.Roles;
import com.movify.backend.Dto.AuthDtos.LoginRequest;
import com.movify.backend.Dto.AuthDtos.RegistroAdminRequest;
import com.movify.backend.Dto.AuthDtos.RegistroUsuarioRequest;
import com.movify.backend.Modelo.Usuario;
import com.movify.backend.Seguridad.GoogleTokenVerificador;
import com.movify.backend.Seguridad.GoogleTokenVerificador.DatosGoogle;
import com.movify.backend.Seguridad.JwtUtil;

@Service
public class AutenticacionServicio {

    private static final Logger log = LoggerFactory.getLogger(AutenticacionServicio.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UsuarioRepositorio usuarios;
    private final PasswordEncoder encoder;
    private final JwtUtil jwt;
    private final GoogleTokenVerificador google;
    private final JdbcTemplate db;
    private final JavaMailSender correo;

    /** Sin valor por defecto: si no está la variable de entorno, el registro de admin queda cerrado. */
    @Value("${app.admin.secret-code:}")
    private String codigoAdminSecreto;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    @Value("${spring.mail.username:}")
    private String remitente;

    public AutenticacionServicio(UsuarioRepositorio usuarios, PasswordEncoder encoder, JwtUtil jwt,
            GoogleTokenVerificador google, JdbcTemplate db, JavaMailSender correo) {
        this.usuarios = usuarios;
        this.encoder = encoder;
        this.jwt = jwt;
        this.google = google;
        this.db = db;
        this.correo = correo;
    }

    // ───────────── Login ─────────────

    public Map<String, Object> login(LoginRequest req) {
        Usuario u = usuarios.findByCorreo(req.correo().trim().toLowerCase())
                .filter(x -> encoder.matches(req.password(), x.getPassword()))
                // Mismo mensaje para correo inexistente y clave errada: no se revela qué correos existen
                .orElseThrow(() -> new ReglaNegocioException(401, "Correo o contraseña incorrectos"));
        validarPuedeEntrar(u);
        return respuestaSesion(u);
    }

    @Transactional
    public Map<String, Object> loginGoogle(String idToken) {
        DatosGoogle g = google.verificar(idToken);
        if (g == null) {
            throw new ReglaNegocioException(401, "No pudimos verificar tu cuenta de Google");
        }
        Usuario u = usuarios.findByCorreo(g.correo()).orElseGet(() -> {
            Usuario nuevo = new Usuario();
            nuevo.setNombre(g.nombre().isBlank() ? g.correo() : g.nombre());
            nuevo.setCorreo(g.correo());
            nuevo.setFoto(g.foto());
            nuevo.setRol(Roles.CLIENTE);
            nuevo.setEstado("activo");
            // La columna exige contraseña; se guarda una aleatoria que nadie conoce
            nuevo.setPassword(encoder.encode(UUID.randomUUID().toString()));
            return usuarios.save(nuevo);
        });
        validarPuedeEntrar(u);
        return respuestaSesion(u);
    }

    private void validarPuedeEntrar(Usuario u) {
        if (u.getEstado() != null && !"activo".equalsIgnoreCase(u.getEstado())) {
            throw new AccesoDenegadoException("Tu cuenta se encuentra " + u.getEstado()
                    + ". Contacta al soporte para más información.");
        }
        if (Roles.CONDUCTOR.equals(Roles.normalizar(u.getRol()))) {
            List<Map<String, Object>> c = db.queryForList(
                    "SELECT estado, motivo_rechazo FROM conductores WHERE usuario_id = ?", u.getId());
            if (!c.isEmpty()) {
                String estado = String.valueOf(c.get(0).get("estado"));
                if ("pendiente".equalsIgnoreCase(estado)) {
                    throw new AccesoDenegadoException("Tu cuenta de conductor está en proceso de revisión. "
                            + "Te notificaremos una vez que tus documentos sean aprobados.");
                }
                if ("rechazado".equalsIgnoreCase(estado)) {
                    Object motivo = c.get(0).get("motivo_rechazo");
                    throw new AccesoDenegadoException("Tu solicitud de conductor fue rechazada. Motivo: "
                            + (motivo == null ? "No especificado" : motivo)
                            + ". Por favor, corrige la información y vuelve a enviarla.");
                }
            }
        }
    }

    private Map<String, Object> respuestaSesion(Usuario u) {
        Map<String, Object> r = new HashMap<>();
        r.put("token", jwt.generarToken(u));
        r.put("id", u.getId());
        r.put("rol", Roles.normalizar(u.getRol()));
        r.put("nombre", u.getNombre());
        r.put("correo", u.getCorreo());
        r.put("telefono", u.getTelefono());
        r.put("foto", u.getFoto());
        return r;
    }

    // ───────────── Registro ─────────────

    public Usuario registrarCliente(RegistroUsuarioRequest req) {
        String correoNorm = req.correo().trim().toLowerCase();
        if (usuarios.existsByCorreo(correoNorm)) {
            throw new ReglaNegocioException("El correo ya está registrado.");
        }
        Usuario u = new Usuario();
        u.setNombre(req.nombre().trim());
        u.setCorreo(correoNorm);
        u.setPassword(encoder.encode(req.password()));
        u.setTelefono(req.telefono());
        u.setRol(Roles.CLIENTE);
        u.setEstado("activo");
        return usuarios.save(u);
    }

    public Usuario registrarAdmin(RegistroAdminRequest req) {
        if (codigoAdminSecreto == null || codigoAdminSecreto.isBlank() || !MessageDigest.isEqual(
                codigoAdminSecreto.getBytes(StandardCharsets.UTF_8),
                req.codigoAdmin().getBytes(StandardCharsets.UTF_8))) {
            log.warn("Intento de registro de admin con código inválido para {}", req.correo());
            throw new AccesoDenegadoException("El código de administrador no es válido. Acceso denegado.");
        }
        String correoNorm = req.correo().trim().toLowerCase();
        if (usuarios.existsByCorreo(correoNorm)) {
            throw new ReglaNegocioException("El correo electrónico ya se encuentra registrado.");
        }
        Usuario admin = new Usuario();
        admin.setNombre(req.nombre().trim());
        admin.setCorreo(correoNorm);
        admin.setPassword(encoder.encode(req.password()));
        admin.setRol(Roles.ADMIN);
        admin.setEstado("activo");
        return usuarios.save(admin);
    }

    // ───────────── Recuperar contraseña ─────────────

    /** Siempre responde lo mismo para no revelar si el correo existe. */
    @Transactional
    public void solicitarRecuperacion(String correoIngresado) {
        Optional<Usuario> opt = usuarios.findByCorreo(correoIngresado.trim().toLowerCase());
        if (opt.isEmpty()) return;
        Usuario u = opt.get();

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        // En la BD solo queda el hash: si alguien lee la tabla no puede usar el enlace
        u.setTokenRecuperacion(sha256(token));
        u.setTokenExpiracion(LocalDateTime.now().plusMinutes(30));
        usuarios.save(u);

        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(remitente);
            msg.setTo(u.getCorreo());
            msg.setSubject("MoviFY · Restablecer contraseña");
            msg.setText("Hola " + u.getNombre() + ",\n\n"
                    + "Para crear una nueva contraseña entra a este enlace (vence en 30 minutos):\n"
                    + frontendUrl + "/restablecer-password?token=" + token + "\n\n"
                    + "Si no lo pediste, ignora este correo.\n\nEquipo MoviFY");
            correo.send(msg);
        } catch (Exception e) {
            log.error("No se pudo enviar el correo de recuperación a {}", u.getCorreo(), e);
        }
    }

    @Transactional
    public void restablecer(String token, String nuevaPassword) {
        Usuario u = usuarios.findByTokenRecuperacion(sha256(token))
                .filter(x -> x.getTokenExpiracion() != null && x.getTokenExpiracion().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> new ReglaNegocioException(400, "El enlace no es válido o ya venció"));
        u.setPassword(encoder.encode(nuevaPassword));
        u.setTokenRecuperacion(null);
        u.setTokenExpiracion(null);
        usuarios.save(u);
    }

    private static String sha256(String texto) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
