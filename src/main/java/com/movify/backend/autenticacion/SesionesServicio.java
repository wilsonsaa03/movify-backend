package com.movify.backend.autenticacion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.movify.backend.comun.Datos;
import com.movify.backend.configuracion.ContextoPeticion;

/**
 * Refresh tokens (tabla sesiones). El access token (JWT) dura poco; con el refresh la app
 * pide uno nuevo sin volver a escribir la clave. Se guarda solo el SHA-256 del refresh.
 */
@Service
public class SesionesServicio {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate db;

    public SesionesServicio(JdbcTemplate db) {
        this.db = db;
    }

    public record Refresh(String token, long usuarioId) {
    }

    /** @param metodo sms | google | password */
    public String crear(Long usuarioId, String metodo, ContextoPeticion ctx) {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        db.update("""
                INSERT INTO sesiones (usuario_id, refresh_hash, metodo_acceso, ip_origen, expira_en)
                VALUES (?, ?, ?, ?, now() + make_interval(days => fn_config_num('dias_vida_refresh_token', 30)::int))
                """, usuarioId, sha256(token), metodo, ctx.ip());
        return token;
    }

    /**
     * Revoca el refresh usado y devuelve el usuario y el método, o null si no sirve.
     * (Rotación: cada refresh se usa una sola vez.)
     */
    public Map<String, Object> consumir(String token) {
        if (token == null || token.isBlank()) return null;
        List<Map<String, Object>> f = db.queryForList("""
                UPDATE sesiones SET revocada_en = now()
                WHERE refresh_hash = ? AND revocada_en IS NULL AND expira_en > now()
                RETURNING usuario_id, metodo_acceso
                """, sha256(token));
        return f.isEmpty() ? null : f.get(0);
    }

    public void revocar(String token) {
        if (token == null || token.isBlank()) return;
        db.update("UPDATE sesiones SET revocada_en = now() WHERE refresh_hash = ? AND revocada_en IS NULL", sha256(token));
    }

    public void revocarTodas(Long usuarioId) {
        db.update("UPDATE sesiones SET revocada_en = now() WHERE usuario_id = ? AND revocada_en IS NULL", usuarioId);
    }

    static Long usuario(Map<String, Object> fila) {
        return Datos.aLong(fila.get("usuario_id"));
    }

    static String sha256(String texto) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
