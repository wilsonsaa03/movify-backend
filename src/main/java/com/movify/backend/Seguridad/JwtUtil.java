package com.movify.backend.Seguridad;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.movify.backend.Comun.Roles;
import com.movify.backend.Modelo.Usuario;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;

@Component
public class JwtUtil {

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.expiration:86400000}")
    private long expiration;

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

    public String generarToken(Usuario usuario) {
        return Jwts.builder()
                .setSubject(String.valueOf(usuario.getId()))
                .claim("correo", usuario.getCorreo())
                .claim("rol", Roles.normalizar(usuario.getRol()))
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /** @return el usuario del token, o null si el token es inválido o venció. */
    public UsuarioAutenticado leer(String token) {
        try {
            Claims c = Jwts.parserBuilder().setSigningKey(key).build()
                    .parseClaimsJws(token).getBody();
            return new UsuarioAutenticado(
                    Long.valueOf(c.getSubject()),
                    c.get("correo", String.class),
                    c.get("rol", String.class));
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
