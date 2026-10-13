package com.movify.backend.seguridad;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.movify.backend.comun.ReglaNegocioException;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;

/**
 * Tres clases de token firmados con el mismo secreto, distinguidos por el claim "tipo":
 *  - acceso:       el de cada petición (Authorization: Bearer ...)
 *  - verificacion: prueba de que la persona escribió bien el código SMS (15 min)
 *  - restablecer:  enlace del correo para cambiar la contraseña de la web (30 min)
 */
@Component
public class JwtUtil {

    private static final String ACCESO = "acceso";
    private static final String VERIFICACION = "verificacion";
    private static final String RESTABLECER = "restablecer";

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.expiration:86400000}")
    private long expiracionAcceso;

    private Key key;

    @PostConstruct
    void iniciar() {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            // HS256 exige 256 bits; un secreto corto es adivinable
            throw new IllegalStateException("JWT_SECRET debe tener al menos 32 caracteres");
        }
        key = Keys.hmacShaKeyFor(bytes);
    }

    /** Bytes del secreto (para el HMAC de los códigos SMS). */
    public byte[] secreto() {
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    public long segundosAcceso() {
        return expiracionAcceso / 1000;
    }

    // ───────────── Acceso ─────────────

    public String generarAcceso(Long id, String correo, String rol, Collection<String> roles, Collection<String> permisos) {
        return Jwts.builder()
                .setSubject(String.valueOf(id))
                .claim("tipo", ACCESO)
                .claim("correo", correo)
                .claim("rol", rol)
                .claim("roles", List.copyOf(roles))
                .claim("permisos", List.copyOf(permisos))
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expiracionAcceso))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /** @return el usuario del token, o null si es inválido, venció o no es de acceso. */
    public UsuarioAutenticado leerAcceso(String token) {
        try {
            Claims c = parsear(token);
            if (!ACCESO.equals(c.get("tipo", String.class))) return null;
            return new UsuarioAutenticado(Long.valueOf(c.getSubject()), c.get("correo", String.class),
                    c.get("rol", String.class), conjunto(c.get("roles")), conjunto(c.get("permisos")));
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    // ───────────── Verificación del celular ─────────────

    public String generarVerificacion(String celular, String proposito, long codigoId) {
        return Jwts.builder()
                .setSubject(celular)
                .claim("tipo", VERIFICACION)
                .claim("proposito", proposito)
                .setId(String.valueOf(codigoId))
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 15 * 60 * 1000L))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /** @return el celular (+57...) verificado para ese propósito; 401 si el token no sirve. */
    public String leerVerificacion(String token, String proposito) {
        try {
            Claims c = parsear(token);
            if (VERIFICACION.equals(c.get("tipo", String.class)) && proposito.equals(c.get("proposito", String.class))) {
                return c.getSubject();
            }
        } catch (JwtException | IllegalArgumentException e) {
            // cae al error de abajo
        }
        throw new ReglaNegocioException(401, "La verificación de tu celular venció. Pide un código nuevo.");
    }

    // ───────────── Restablecer contraseña (web) ─────────────

    /** La huella del hash actual hace que el enlace deje de servir apenas se cambia la contraseña. */
    public String generarRestablecer(Long usuarioId, String huella) {
        return Jwts.builder()
                .setSubject(String.valueOf(usuarioId))
                .claim("tipo", RESTABLECER)
                .claim("huella", huella)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 30 * 60 * 1000L))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /** @return {usuarioId, huella} o null. */
    public String[] leerRestablecer(String token) {
        try {
            Claims c = parsear(token);
            if (!RESTABLECER.equals(c.get("tipo", String.class))) return null;
            return new String[] { c.getSubject(), c.get("huella", String.class) };
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    private Claims parsear(String token) {
        return Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();
    }

    private static Set<String> conjunto(Object o) {
        Set<String> s = new LinkedHashSet<>();
        if (o instanceof Collection<?> c) c.forEach(x -> s.add(String.valueOf(x)));
        return s;
    }
}
