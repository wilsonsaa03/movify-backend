package com.movify.backend.autenticacion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.movify.backend.autenticacion.AuthDtos.LoginCelularRequest;
import com.movify.backend.autenticacion.AuthDtos.LoginRequest;
import com.movify.backend.autenticacion.AuthDtos.RegistroAdminRequest;
import com.movify.backend.autenticacion.AuthDtos.RegistroMovilRequest;
import com.movify.backend.autenticacion.AuthDtos.RegistroUsuarioRequest;
import com.movify.backend.comun.AccesoDenegadoException;
import com.movify.backend.comun.Celular;
import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.comun.ReglaNegocioException;
import com.movify.backend.comun.Roles;
import com.movify.backend.configuracion.ContextoPeticion;
import com.movify.backend.seguridad.GoogleTokenVerificador;
import com.movify.backend.seguridad.GoogleTokenVerificador.DatosGoogle;
import com.movify.backend.seguridad.JwtUtil;
import com.movify.backend.usuarios.Usuario;
import com.movify.backend.usuarios.UsuarioRepositorio;

/**
 * Registro, inicio de sesión y recuperación del acceso.
 *
 * App móvil (clientes y conductores):
 *   1. POST /codigo {celular, proposito:"registro"}       → llega un SMS con 6 números
 *   2. POST /codigo/verificar                              → verificacionToken
 *   3. POST /registro-movil {verificacionToken, datos, clave de 4 dígitos}
 *   Entrar: POST /login-celular {celular, clave}. Si olvidó la clave: código con proposito "login"
 *   y luego /clave/restablecer (o /login-codigo para entrar solo con el SMS).
 *
 * Web (admin, soporte y quien ya tenía cuenta con correo): POST /login {correo, password}.
 */
@Service
public class AutenticacionServicio {

    private static final Logger log = LoggerFactory.getLogger(AutenticacionServicio.class);

    private final UsuarioRepositorio usuarios;
    private final PasswordEncoder encoder;
    private final JwtUtil jwt;
    private final GoogleTokenVerificador google;
    private final JdbcTemplate db;
    private final JavaMailSender correo;
    private final RegistroAccesos accesos;
    private final SesionesServicio sesiones;

    /** Sin valor por defecto: si no está la variable de entorno, el registro de admin queda cerrado. */
    @Value("${app.admin.secret-code:}")
    private String codigoAdminSecreto;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    @Value("${spring.mail.username:}")
    private String remitente;

    public AutenticacionServicio(UsuarioRepositorio usuarios, PasswordEncoder encoder, JwtUtil jwt,
            GoogleTokenVerificador google, JdbcTemplate db, JavaMailSender correo, RegistroAccesos accesos,
            SesionesServicio sesiones) {
        this.usuarios = usuarios;
        this.encoder = encoder;
        this.jwt = jwt;
        this.google = google;
        this.db = db;
        this.correo = correo;
        this.accesos = accesos;
        this.sesiones = sesiones;
    }

    // ═════════════════════════════ Inicio de sesión ═════════════════════════════

    @Transactional
    public Map<String, Object> loginCorreo(LoginRequest req, ContextoPeticion ctx) {
        String email = req.correo().trim().toLowerCase(Locale.ROOT);
        Usuario u = usuarios.porEmail(email).filter(x -> x.passwordHash() != null).orElse(null);
        if (u == null) {
            accesos.registrar(null, email, "password", "usuario_inexistente", ctx);
            throw new ReglaNegocioException(401, "Correo o contraseña incorrectos");
        }
        validarPuedeEntrar(u, email, "password", ctx);
        if (!encoder.matches(req.password(), u.passwordHash())) {
            accesos.registrar(u.id(), email, "password", "password_invalido", ctx);
            // Mismo mensaje para correo inexistente y clave errada: no se revela qué correos existen
            throw new ReglaNegocioException(401, "Correo o contraseña incorrectos");
        }
        return entrar(u, email, "password", ctx);
    }

    @Transactional
    public Map<String, Object> loginCelular(LoginCelularRequest req, ContextoPeticion ctx) {
        String tel = Celular.exigir(req.celular());
        Usuario u = usuarios.porTelefono(tel).orElse(null);
        if (u == null) {
            accesos.registrar(null, tel, "password", "usuario_inexistente", ctx);
            throw new ReglaNegocioException(401, "Celular o clave incorrectos");
        }
        validarPuedeEntrar(u, tel, "password", ctx);
        if (u.passwordHash() == null || !encoder.matches(req.clave(), u.passwordHash())) {
            accesos.registrar(u.id(), tel, "password", "password_invalido", ctx);
            throw new ReglaNegocioException(401, "Celular o clave incorrectos");
        }
        return entrar(u, tel, "password", ctx);
    }

    /** Entrar solo con el código SMS (proposito "login"). */
    @Transactional
    public Map<String, Object> loginCodigo(String verificacionToken, ContextoPeticion ctx) {
        String tel = jwt.leerVerificacion(verificacionToken, CodigoVerificacionServicio.LOGIN);
        Usuario u = usuarios.porTelefono(tel)
                .orElseThrow(() -> new RecursoNoEncontradoException("No hay una cuenta con ese celular. Regístrate."));
        validarPuedeEntrar(u, tel, "sms", ctx);
        db.update("UPDATE usuarios SET telefono_verificado = true WHERE id = ? AND NOT telefono_verificado", u.id());
        return entrar(u, tel, "sms", ctx);
    }

    @Transactional
    public Map<String, Object> loginGoogle(String idToken, ContextoPeticion ctx) {
        DatosGoogle g = google.verificar(idToken);
        if (g == null) {
            accesos.registrar(null, "google", "google", "token_google_invalido", ctx);
            throw new ReglaNegocioException(401, "No pudimos verificar tu cuenta de Google");
        }
        Usuario u = usuarios.porGoogleSub(g.sub()).orElse(null);
        if (u == null) {
            u = usuarios.porEmail(g.correo()).orElse(null);
            if (u != null) {
                usuarios.vincularGoogle(u.id(), g.sub(), g.foto());
            } else {
                String[] nombre = partirNombre(g.nombre().isBlank() ? g.correo().split("@")[0] : g.nombre());
                Long id = usuarios.crear(nombre[0], nombre[1], null, false, g.correo(), true, g.sub(), null,
                        g.foto().isBlank() ? null : g.foto());
                usuarios.asignarRol(id, Roles.CLIENTE);
            }
            u = usuarios.porGoogleSub(g.sub()).orElseThrow();
        }
        validarPuedeEntrar(u, g.correo(), "google", ctx);
        return entrar(u, g.correo(), "google", ctx);
    }

    @Transactional
    public Map<String, Object> refrescar(String refreshToken, ContextoPeticion ctx) {
        Map<String, Object> fila = sesiones.consumir(refreshToken);
        if (fila == null) {
            throw new ReglaNegocioException(401, "Tu sesión venció. Inicia sesión de nuevo.");
        }
        Usuario u = usuarios.porId(SesionesServicio.usuario(fila)).orElseThrow(
                () -> new ReglaNegocioException(401, "Tu sesión venció. Inicia sesión de nuevo."));
        validarPuedeEntrar(u, String.valueOf(u.id()), "refresh", ctx);
        return respuestaSesion(u, String.valueOf(fila.get("metodo_acceso")), ctx);
    }

    public void cerrarSesion(String refreshToken) {
        sesiones.revocar(refreshToken);
    }

    /**
     * Antes de comparar la clave: cuenta activa, no bloqueada y sin bloqueo temporal
     * por intentos fallidos (fn_validar_acceso del DER).
     */
    private void validarPuedeEntrar(Usuario u, String identificador, String metodo, ContextoPeticion ctx) {
        String r = usuarios.validarAcceso(u.id());
        if ("exitoso".equals(r)) return;
        accesos.registrar(u.id(), identificador, metodo, r, ctx);
        switch (r) {
            case "acceso_bloqueado_temporal" -> {
                Integer min = db.queryForObject(
                        "SELECT GREATEST(1, ceil(EXTRACT(EPOCH FROM (acceso_bloqueado_hasta - now())) / 60))::int FROM usuarios WHERE id = ?",
                        Integer.class, u.id());
                throw new ReglaNegocioException(429, "Hiciste demasiados intentos. Intenta de nuevo en " + min
                        + (min != null && min == 1 ? " minuto." : " minutos."));
            }
            case "cuenta_bloqueada" -> throw new AccesoDenegadoException(
                    "Tu cuenta está bloqueada. Escríbenos a soporte para revisarla.");
            case "cuenta_eliminada" -> throw new ReglaNegocioException(401, "Esta cuenta fue eliminada.");
            default -> throw new AccesoDenegadoException("Tu cuenta no tiene permisos para entrar.");
        }
    }

    private Map<String, Object> entrar(Usuario u, String identificador, String metodo, ContextoPeticion ctx) {
        accesos.registrarExito(u.id(), identificador, metodo, ctx);
        return respuestaSesion(u, metodo, ctx);
    }

    /** Lo que recibe la app al entrar: tokens + datos para pintar la pantalla de inicio. */
    Map<String, Object> respuestaSesion(Usuario u, String metodo, ContextoPeticion ctx) {
        Set<String> roles = usuarios.roles(u.id());
        Set<String> permisos = usuarios.permisos(u.id());
        String rol = Roles.principal(roles);

        Map<String, Object> r = new HashMap<>();
        r.put("token", jwt.generarAcceso(u.id(), u.email(), rol, roles, permisos));
        r.put("refreshToken", sesiones.crear(u.id(), metodo, ctx));
        r.put("expiraEnSeg", jwt.segundosAcceso());
        r.put("id", u.id());
        r.put("rol", rol);
        r.put("roles", roles);
        r.put("permisos", permisos);
        r.put("nombre", u.nombreCompleto());
        r.put("nombres", u.nombres());
        r.put("apellidos", u.apellidos());
        r.put("correo", u.email());
        r.put("telefono", u.telefono());
        r.put("telefonoVerificado", u.telefonoVerificado());
        r.put("foto", u.fotoUrl());
        List<Map<String, Object>> c = db.queryForList(
                "SELECT estado, motivo_estado AS motivo FROM conductores WHERE usuario_id = ?", u.id());
        r.put("conductor", c.isEmpty() ? null : c.get(0));
        return r;
    }

    // ═════════════════════════════ Registro ═════════════════════════════

    /** Registro desde la app: el celular ya viene verificado por SMS. */
    @Transactional
    public Map<String, Object> registrarMovil(RegistroMovilRequest req, ContextoPeticion ctx) {
        String tel = jwt.leerVerificacion(req.verificacionToken(), CodigoVerificacionServicio.REGISTRO);
        if (usuarios.existeTelefono(tel)) {
            throw new ReglaNegocioException("Ya existe una cuenta con ese celular. Inicia sesión.");
        }
        String email = req.correo() == null || req.correo().isBlank() ? null : req.correo().trim().toLowerCase(Locale.ROOT);
        if (email != null && usuarios.existeEmail(email)) {
            throw new ReglaNegocioException("Ese correo ya está registrado.");
        }
        exigirClaveSegura(req.clave());

        Long id = usuarios.crear(capitalizar(req.nombres()), capitalizar(req.apellidos()), tel, true, email, false,
                null, encoder.encode(req.clave()), null);
        // Toda cuenta nace como cliente. El rol conductor lo pone la BD (trigger) cuando
        // la persona crea su perfil de conductor con identificación y licencia.
        usuarios.asignarRol(id, Roles.CLIENTE);
        // Prueba de la autorización de tratamiento de datos (Ley 1581): una fila por documento vigente
        db.update("""
                INSERT INTO aceptaciones_legales (usuario_id, documento_id, ip_origen, plataforma)
                SELECT ?, id, ?, ? FROM documentos_legales WHERE activo AND obligatorio
                ON CONFLICT DO NOTHING
                """, id, ctx.ip(), ctx.plataforma());
        log.info("Cuenta nueva {} por la app ({})", id, req.rol());

        Usuario u = usuarios.porId(id).orElseThrow();
        accesos.registrarExito(id, tel, "sms", ctx);
        Map<String, Object> r = respuestaSesion(u, "sms", ctx);
        r.put("siguientePaso", "conductor".equals(req.rol()) ? "documentos_conductor" : "inicio");
        return r;
    }

    /** Registro de la web (correo y contraseña). El celular queda sin verificar. */
    @Transactional
    public Map<String, Object> registrarWeb(RegistroUsuarioRequest req) {
        String email = req.correo().trim().toLowerCase(Locale.ROOT);
        if (usuarios.existeEmail(email)) {
            throw new ReglaNegocioException("El correo ya está registrado.");
        }
        String tel = Celular.normalizar(req.telefono());
        if (tel != null && usuarios.existeTelefono(tel)) {
            throw new ReglaNegocioException("Ya existe una cuenta con ese celular.");
        }
        String[] nombre = partirNombre(req.nombre());
        Long id = usuarios.crear(nombre[0], nombre[1], tel, false, email, false, null, encoder.encode(req.password()),
                null);
        usuarios.asignarRol(id, Roles.CLIENTE);
        return Map.of("message", "Usuario registrado correctamente", "id", id, "rol", Roles.CLIENTE);
    }

    @Transactional
    public Map<String, Object> registrarAdmin(RegistroAdminRequest req) {
        if (codigoAdminSecreto == null || codigoAdminSecreto.isBlank() || !MessageDigest.isEqual(
                codigoAdminSecreto.getBytes(StandardCharsets.UTF_8),
                req.codigoAdmin().getBytes(StandardCharsets.UTF_8))) {
            log.warn("Intento de registro de admin con código inválido para {}", req.correo());
            throw new AccesoDenegadoException("El código de administrador no es válido. Acceso denegado.");
        }
        String email = req.correo().trim().toLowerCase(Locale.ROOT);
        if (usuarios.existeEmail(email)) {
            throw new ReglaNegocioException("El correo electrónico ya se encuentra registrado.");
        }
        String[] nombre = partirNombre(req.nombre());
        Long id = usuarios.crear(nombre[0], nombre[1], null, false, email, false, null, encoder.encode(req.password()),
                null);
        usuarios.asignarRol(id, Roles.ADMIN);
        return Map.of("message", "Administrador registrado correctamente", "id", id);
    }

    // ═════════════════════════════ Recuperar el acceso ═════════════════════════════

    /** App: con el código SMS (proposito "login") se crea una clave nueva y se entra. */
    @Transactional
    public Map<String, Object> restablecerClave(String verificacionToken, String clave, ContextoPeticion ctx) {
        String tel = jwt.leerVerificacion(verificacionToken, CodigoVerificacionServicio.LOGIN);
        Usuario u = usuarios.porTelefono(tel)
                .orElseThrow(() -> new RecursoNoEncontradoException("No hay una cuenta con ese celular."));
        validarPuedeEntrar(u, tel, "sms", ctx);
        exigirClaveSegura(clave);
        usuarios.actualizarPassword(u.id(), encoder.encode(clave));
        sesiones.revocarTodas(u.id()); // se cierran las sesiones abiertas con la clave anterior
        return entrar(usuarios.porId(u.id()).orElseThrow(), tel, "sms", ctx);
    }

    /** Web: enlace por correo. Siempre responde lo mismo para no revelar si el correo existe. */
    public void solicitarRecuperacion(String correoIngresado) {
        Usuario u = usuarios.porEmail(correoIngresado.trim()).filter(x -> x.passwordHash() != null).orElse(null);
        if (u == null) return;
        String token = jwt.generarRestablecer(u.id(), huella(u.passwordHash()));
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(remitente);
            msg.setTo(u.email());
            msg.setSubject("MoviFY · Restablecer contraseña");
            msg.setText("Hola " + u.nombres() + ",\n\n"
                    + "Para crear una nueva contraseña entra a este enlace (vence en 30 minutos):\n"
                    + frontendUrl + "/restablecer-password?token=" + token + "\n\n"
                    + "Si no lo pediste, ignora este correo.\n\nEquipo MoviFY");
            correo.send(msg);
        } catch (Exception e) {
            log.error("No se pudo enviar el correo de recuperación a {}", u.email(), e);
        }
    }

    @Transactional
    public void restablecerPassword(String token, String nuevaPassword) {
        String[] datos = jwt.leerRestablecer(token);
        Usuario u = datos == null ? null : usuarios.porId(Long.valueOf(datos[0])).orElse(null);
        // La huella cambia al cambiar la contraseña: el enlace sirve una sola vez
        if (u == null || u.passwordHash() == null || !huella(u.passwordHash()).equals(datos[1])) {
            throw new ReglaNegocioException(400, "El enlace no es válido o ya venció");
        }
        usuarios.actualizarPassword(u.id(), encoder.encode(nuevaPassword));
        sesiones.revocarTodas(u.id());
    }

    // ═════════════════════════════ Utilidades ═════════════════════════════

    /** Claves fáciles de adivinar: 1234, 4321, 0000, 1111, 2580... */
    public static boolean claveDebil(String clave) {
        if (clave == null || !clave.matches("\\d{4}")) return true;
        if (clave.chars().distinct().count() == 1) return true;
        int[] n = clave.chars().map(c -> c - '0').toArray();
        boolean sube = true, baja = true;
        for (int i = 1; i < n.length; i++) {
            sube &= n[i] == n[i - 1] + 1;
            baja &= n[i] == n[i - 1] - 1;
        }
        return sube || baja || List.of("2580", "0852", "1212", "6969", "1004").contains(clave);
    }

    private static void exigirClaveSegura(String clave) {
        if (claveDebil(clave)) {
            throw new ReglaNegocioException(400, "Esa clave es muy fácil de adivinar. Prueba con otra.");
        }
    }

    /** "ana maría riascos" → {"Ana María", "Riascos"}; 4 palabras o más → 2 nombres + apellidos. */
    static String[] partirNombre(String completo) {
        String limpio = capitalizar(completo);
        String[] p = limpio.split(" ");
        if (p.length == 1) return new String[] { limpio, null };
        int corte = p.length >= 4 ? 2 : 1;
        return new String[] { String.join(" ", java.util.Arrays.copyOfRange(p, 0, corte)),
                String.join(" ", java.util.Arrays.copyOfRange(p, corte, p.length)) };
    }

    static String capitalizar(String t) {
        if (t == null) return null;
        StringBuilder sb = new StringBuilder();
        for (String palabra : t.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (palabra.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(palabra.charAt(0))).append(palabra.substring(1));
        }
        String r = sb.toString();
        return r.length() > 80 ? r.substring(0, 80) : r;
    }

    private static String huella(String hash) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(hash.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
