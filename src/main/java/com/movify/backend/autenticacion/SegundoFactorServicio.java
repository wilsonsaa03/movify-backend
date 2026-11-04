package com.movify.backend.autenticacion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.movify.backend.autenticacion.sms.SmsEnviador;
import com.movify.backend.comun.AccesoDenegadoException;
import com.movify.backend.comun.Datos;
import com.movify.backend.comun.ReglaNegocioException;
import com.movify.backend.comun.Roles;
import com.movify.backend.configuracion.ContextoPeticion;
import com.movify.backend.seguridad.JwtUtil;
import com.movify.backend.usuarios.Usuario;

/**
 * Segundo factor del equipo (V19, tabla desafios_acceso). Admin y soporte manejan datos personales, documentos y
 * la plataforma entera: además de la contraseña (o Google) necesitan un código de 6 números que llega por SMS a su
 * celular (o por correo si no tienen celular registrado). Vence en 10 minutos y aguanta 5 intentos; los intentos
 * fallidos cuentan para el bloqueo temporal de la cuenta (registro_accesos).
 *
 * Se apaga con app.admin.segundo-factor=false (solo para pruebas locales).
 */
@Service
public class SegundoFactorServicio {

    private static final Logger log = LoggerFactory.getLogger(SegundoFactorServicio.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int VIGENCIA_SEG = 600;
    private static final int MAX_INTENTOS = 5;
    private static final int MAX_DESAFIOS_15_MIN = 5;
    private static final Set<String> ROLES_EQUIPO = Set.of(Roles.ADMIN, Roles.SOPORTE);

    public record Verificado(Long usuarioId, String metodo) {
    }

    private final JdbcTemplate db;
    private final JwtUtil jwt;
    private final SmsEnviador sms;
    private final JavaMailSender correo;

    @Value("${app.admin.segundo-factor:true}")
    private boolean habilitado;

    @Value("${spring.mail.username:}")
    private String remitente;

    public SegundoFactorServicio(JdbcTemplate db, JwtUtil jwt, SmsEnviador sms, JavaMailSender correo) {
        this.db = db;
        this.jwt = jwt;
        this.sms = sms;
        this.correo = correo;
    }

    /** ¿Esta persona es del equipo y por eso necesita el código? */
    public boolean requiere(Set<String> roles) {
        return habilitado && roles.stream().anyMatch(ROLES_EQUIPO::contains);
    }

    /**
     * La contraseña (o Google) ya fue correcta: se manda el código y se responde SIN tokens de sesión.
     * @param metodo password | google
     */
    @Transactional
    public Map<String, Object> iniciar(Usuario u, String metodo, ContextoPeticion ctx) {
        Integer recientes = db.queryForObject(
                "SELECT count(*) FROM desafios_acceso WHERE usuario_id = ? AND creado_en > now() - INTERVAL '15 minutes'",
                Integer.class, u.id());
        if (recientes != null && recientes >= MAX_DESAFIOS_15_MIN) {
            throw new ReglaNegocioException(429, "Pediste demasiados códigos. Espera 15 minutos e intenta de nuevo.");
        }
        String canal = u.telefono() != null && !u.telefono().isBlank() ? "sms"
                : u.email() != null && !u.email().isBlank() ? "correo" : null;
        if (canal == null) {
            throw new AccesoDenegadoException(
                    "Tu cuenta del equipo no tiene celular ni correo para recibir el código. Pide ayuda a un administrador.");
        }

        // Un código nuevo anula los anteriores sin usar
        db.update("UPDATE desafios_acceso SET usado_en = now() WHERE usuario_id = ? AND usado_en IS NULL", u.id());
        String codigo = String.format("%06d", RANDOM.nextInt(1_000_000));
        Long id = db.queryForObject("""
                INSERT INTO desafios_acceso (usuario_id, codigo_hash, canal, metodo, expira_en, ip_origen)
                VALUES (?, '-', ?, ?, now() + make_interval(secs => ?), ?) RETURNING id
                """, Long.class, u.id(), canal, metodo, VIGENCIA_SEG, ctx.ip());
        db.update("UPDATE desafios_acceso SET codigo_hash = ? WHERE id = ?", huella(id, codigo), id);

        String texto = "Tu código para entrar al panel de MoviFY es " + codigo
                + ". Vence en 10 minutos. Si no fuiste tú, cambia tu contraseña ya.";
        if ("sms".equals(canal)) {
            sms.enviar(u.telefono(), texto);
        } else {
            String destino = u.email();
            CompletableFuture.runAsync(() -> enviarCorreo(destino, texto));
            // Desarrollo (app.sms.consola=true): también en la consola, para probar sin correo configurado
            if (sms.esModoConsola()) log.info("[DESARROLLO] Código del segundo factor para el usuario {}: {}", u.id(), codigo);
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("segundoFactor", true);
        r.put("desafio", jwt.generarDesafio(u.id(), id));
        r.put("canal", canal);
        r.put("destino", "sms".equals(canal) ? Datos.ocultarTelefono(u.telefono()) : Datos.ocultarCorreo(u.email()));
        r.put("vigenciaSeg", VIGENCIA_SEG);
        // Igual que los SMS: solo en desarrollo (app.sms.consola=true) el código viaja en la respuesta
        if (sms.esModoConsola()) r.put("codigoDesarrollo", codigo);
        return r;
    }

    /**
     * Comprueba el código. Los intentos fallidos quedan guardados aunque se responda error.
     * @throws ReglaNegocioException 400 si el código no es, 429 si se acabaron los intentos.
     */
    @Transactional(noRollbackFor = ReglaNegocioException.class)
    public Verificado verificar(String desafioToken, String codigo) {
        long[] d = jwt.leerDesafio(desafioToken);
        if (codigo == null || !codigo.matches("\\d{6}")) {
            throw new ReglaNegocioException(400, "El código tiene 6 números");
        }
        List<Map<String, Object>> filas = db.queryForList("""
                SELECT codigo_hash, intentos, metodo, expira_en < now() AS vencido, usado_en IS NOT NULL AS usado
                FROM desafios_acceso WHERE id = ? AND usuario_id = ? FOR UPDATE
                """, d[1], d[0]);
        if (filas.isEmpty() || Boolean.TRUE.equals(filas.get(0).get("usado"))) {
            throw new ReglaNegocioException(401, "Ese código ya no sirve. Vuelve a iniciar sesión.");
        }
        Map<String, Object> f = filas.get(0);
        if (Boolean.TRUE.equals(f.get("vencido"))) {
            throw new ReglaNegocioException(401, "El código venció. Vuelve a iniciar sesión.");
        }
        int intentos = ((Number) f.get("intentos")).intValue();
        if (intentos >= MAX_INTENTOS) {
            throw new ReglaNegocioException(429, "Hiciste demasiados intentos. Vuelve a iniciar sesión.");
        }
        boolean igual = MessageDigest.isEqual(huella(d[1], codigo).getBytes(StandardCharsets.UTF_8),
                String.valueOf(f.get("codigo_hash")).getBytes(StandardCharsets.UTF_8));
        if (!igual) {
            db.update("UPDATE desafios_acceso SET intentos = intentos + 1 WHERE id = ?", d[1]);
            int quedan = MAX_INTENTOS - intentos - 1;
            throw new ReglaNegocioException(400, quedan > 0
                    ? "Ese código no es correcto. Te quedan " + quedan + (quedan == 1 ? " intento." : " intentos.")
                    : "Ese código no es correcto. Vuelve a iniciar sesión.");
        }
        db.update("UPDATE desafios_acceso SET usado_en = now() WHERE id = ?", d[1]);
        return new Verificado(d[0], String.valueOf(f.get("metodo")));
    }

    /** Usuario del desafío aunque el código esté mal (para anotar el intento fallido en registro_accesos). */
    public Long usuarioDe(String desafioToken) {
        try {
            return jwt.leerDesafio(desafioToken)[0];
        } catch (ReglaNegocioException e) {
            return null;
        }
    }

    private void enviarCorreo(String destino, String texto) {
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(remitente);
            msg.setTo(destino);
            msg.setSubject("MoviFY · Tu código para entrar al panel");
            msg.setText(texto + "\n\nEquipo MoviFY");
            correo.send(msg);
        } catch (Exception e) {
            log.error("No se pudo enviar el código del segundo factor por correo: {}", e.getMessage());
        }
    }

    /** HMAC-SHA256(secreto, desafio|id|código): ni leyendo la tabla se puede usar el código. */
    private String huella(long desafioId, String codigo) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jwt.secreto(), "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal(("desafio|" + desafioId + "|" + codigo).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
