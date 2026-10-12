package com.movify.backend.autenticacion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.movify.backend.autenticacion.sms.SmsEnviador;
import com.movify.backend.comun.Datos;
import com.movify.backend.comun.ReglaNegocioException;
import com.movify.backend.seguridad.JwtUtil;

/**
 * Códigos SMS de un solo uso (tabla codigos_verificacion).
 *
 *  - Se guarda un HMAC del código, nunca el código: si alguien lee la tabla no puede usarlo.
 *  - Vigencia, intentos y envíos por hora salen de configuracion_sistema
 *    (otp_vigencia_seg, otp_max_intentos, otp_max_envios_hora).
 *  - Al verificar bien se entrega un "token de verificación" (15 min) que prueba que
 *    esa persona controla ese celular; con él se registra, entra o cambia su clave.
 */
@Service
public class CodigoVerificacionServicio {

    public static final String REGISTRO = "registro";
    public static final String LOGIN = "login";
    public static final String CAMBIO_TELEFONO = "cambio_telefono";
    private static final Set<String> PROPOSITOS = Set.of(REGISTRO, LOGIN, CAMBIO_TELEFONO);

    /** Tope adicional por IP para que nadie use MoviFY para mandar SMS masivos. */
    private static final int MAX_ENVIOS_POR_IP_HORA = 15;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate db;
    private final SmsEnviador sms;
    private final JwtUtil jwt;

    public CodigoVerificacionServicio(JdbcTemplate db, SmsEnviador sms, JwtUtil jwt) {
        this.db = db;
        this.sms = sms;
        this.jwt = jwt;
    }

    public record Envio(int vigenciaSeg, String codigoDesarrollo) {
    }

    public static String validarProposito(String proposito) {
        if (proposito == null || !PROPOSITOS.contains(proposito)) {
            throw new ReglaNegocioException(400, "Propósito del código no válido");
        }
        return proposito;
    }

    /** Genera, guarda y envía el código. Si el SMS falla, no queda nada guardado (rollback). */
    @Transactional
    public Envio enviar(String celular, String proposito, String ip) {
        validarProposito(proposito);
        Map<String, Object> cfg = db.queryForMap("""
                SELECT fn_config_num('otp_vigencia_seg', 300)::int    AS vigencia,
                       fn_config_num('otp_max_envios_hora', 5)::int  AS max_hora,
                       (SELECT count(*) FROM codigos_verificacion
                         WHERE telefono = ? AND creado_en > now() - INTERVAL '1 hour')::int AS por_telefono,
                       (SELECT count(*) FROM codigos_verificacion
                         WHERE ip_origen = ? AND creado_en > now() - INTERVAL '1 hour')::int AS por_ip
                """, celular, ip);
        int vigencia = ((Number) cfg.get("vigencia")).intValue();
        if (((Number) cfg.get("por_telefono")).intValue() >= ((Number) cfg.get("max_hora")).intValue()
                || ((Number) cfg.get("por_ip")).intValue() >= MAX_ENVIOS_POR_IP_HORA) {
            throw new ReglaNegocioException(429, "Pediste demasiados códigos. Espera un rato e intenta de nuevo.");
        }

        // Un código nuevo anula los anteriores sin usar para ese mismo propósito
        db.update("""
                UPDATE codigos_verificacion SET usado_en = now()
                WHERE telefono = ? AND proposito = ? AND usado_en IS NULL
                """, celular, proposito);

        String codigo = String.format("%06d", RANDOM.nextInt(1_000_000));
        db.update("""
                INSERT INTO codigos_verificacion (telefono, codigo_hash, proposito, expira_en, ip_origen)
                VALUES (?, ?, ?, now() + make_interval(secs => ?), ?)
                """, celular, huella(celular, proposito, codigo), proposito, vigencia, ip);

        sms.enviar(celular, "Tu código de MoviFY es " + codigo + ". Vence en " + Math.max(1, vigencia / 60)
                + " minutos. No lo compartas con nadie: MoviFY nunca te lo pedirá por llamada.");
        return new Envio(vigencia, sms.esModoConsola() ? codigo : null);
    }

    /**
     * @return token de verificación (15 min) si el código es correcto.
     * Los intentos fallidos se guardan aunque se responda error (noRollbackFor).
     */
    @Transactional(noRollbackFor = ReglaNegocioException.class)
    public String verificar(String celular, String proposito, String codigo) {
        validarProposito(proposito);
        if (codigo == null || !codigo.matches("\\d{6}")) {
            throw new ReglaNegocioException(400, "El código tiene 6 números");
        }
        List<Map<String, Object>> filas = db.queryForList("""
                SELECT id, codigo_hash, intentos, expira_en < now() AS vencido,
                       fn_config_num('otp_max_intentos', 5)::int AS max_intentos
                FROM codigos_verificacion
                WHERE telefono = ? AND proposito = ? AND usado_en IS NULL
                ORDER BY creado_en DESC
                LIMIT 1
                FOR UPDATE
                """, celular, proposito);
        if (filas.isEmpty()) {
            throw new ReglaNegocioException(400, "No hay un código pendiente para ese número. Pide uno nuevo.");
        }
        Map<String, Object> f = filas.get(0);
        long id = Datos.aLong(f.get("id"));
        if (Boolean.TRUE.equals(f.get("vencido"))) {
            throw new ReglaNegocioException(400, "El código venció. Pide uno nuevo.");
        }
        int intentos = ((Number) f.get("intentos")).intValue();
        int max = ((Number) f.get("max_intentos")).intValue();
        if (intentos >= max) {
            throw new ReglaNegocioException(429, "Hiciste demasiados intentos con este código. Pide uno nuevo.");
        }

        boolean igual = MessageDigest.isEqual(
                huella(celular, proposito, codigo).getBytes(StandardCharsets.UTF_8),
                String.valueOf(f.get("codigo_hash")).getBytes(StandardCharsets.UTF_8));
        if (!igual) {
            db.update("UPDATE codigos_verificacion SET intentos = intentos + 1 WHERE id = ?", id);
            int quedan = max - intentos - 1;
            throw new ReglaNegocioException(400, quedan > 0
                    ? "Ese código no es correcto. Te quedan " + quedan + (quedan == 1 ? " intento." : " intentos.")
                    : "Ese código no es correcto. Pide uno nuevo.");
        }

        db.update("UPDATE codigos_verificacion SET usado_en = now() WHERE id = ?", id);
        return jwt.generarVerificacion(celular, proposito, id);
    }

    /** HMAC-SHA256(secreto, celular|propósito|código). */
    String huella(String celular, String proposito, String codigo) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jwt.secreto(), "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal((celular + "|" + proposito + "|" + codigo).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
