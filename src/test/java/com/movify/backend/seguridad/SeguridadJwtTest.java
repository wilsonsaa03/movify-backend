package com.movify.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.movify.backend.configuracion.SecurityConfig;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

/**
 * Criterios de la HU 1.3 "Login con OAuth 2.0 y JWT" que no necesitan base de datos:
 *  - el JWT lleva id (sub), rol y fecha de expiración, firmado (HS256);
 *  - un endpoint protegido responde 401 sin token, con token vencido o con firma falsa;
 *  - BCrypt es un único bean (PasswordEncoder) inyectado.
 * El login con celular y clave incorrecta → 401 sin token lo cubre FlujoCompletoTest.loginConCelularYClave.
 * Y de la HU 2.3 "Roles y control de acceso": con token válido de otro rol o sin el permiso → 403.
 */
@WebMvcTest(controllers = SeguridadJwtTest.Protegido.class, properties = {
        "app.jwt.secret=" + SeguridadJwtTest.SECRETO,
        "app.jwt.expiration=3600000",
        "app.cors.allowed-origins=http://localhost:8100" })
@Import({ SecurityConfig.class, JwtFiltro.class, JwtUtil.class, SeguridadJwtTest.Protegido.class })
class SeguridadJwtTest {

    static final String SECRETO = "secreto-de-pruebas-de-seguridad-movify-0123456789";

    /** Un endpoint cualquiera que no está en la lista de públicos de SecurityConfig. */
    @RestController
    static class Protegido {
        @GetMapping("/api/servicios/mios-prueba")
        Map<String, Object> mios() {
            return Map.of("ok", true);
        }

        /** Bajo /api/admin/**: SecurityConfig exige rol ADMIN o SOPORTE. */
        @GetMapping("/api/admin/panel-prueba")
        Map<String, Object> panel() {
            return Map.of("ok", true);
        }

        /** Regla fina por permiso, como en los controladores reales. */
        @GetMapping("/api/servicios/solicitar-prueba")
        @PreAuthorize("hasAuthority('servicios.solicitar')")
        Map<String, Object> soloConPermiso() {
            return Map.of("ok", true);
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtUtil jwt;
    @Autowired
    ApplicationContext contexto;

    @Test
    void elJwtLlevaIdRolYExpiracionYEstaFirmado() {
        String token = jwt.generarAcceso(42L, "ana@movify.co", "cliente", List.of("cliente"), List.of("servicios.solicitar"));

        Claims c = Jwts.parserBuilder().setSigningKey(Keys.hmacShaKeyFor(SECRETO.getBytes(StandardCharsets.UTF_8)))
                .build().parseClaimsJws(token).getBody(); // lanza excepción si la firma no coincide
        assertThat(c.getSubject()).isEqualTo("42");
        assertThat(c.get("rol", String.class)).isEqualTo("cliente");
        assertThat(c.getExpiration()).isAfter(new Date());
        assertThat(c.getExpiration().getTime() - c.getIssuedAt().getTime()).isEqualTo(3_600_000L);
        assertThat(token.split("\\.")).hasSize(3); // header.payload.firma
    }

    @Test
    void sinTokenResponde401() throws Exception {
        mvc.perform(get("/api/servicios/mios-prueba"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Debes iniciar sesión"));
    }

    @Test
    void conTokenVencidoResponde401() throws Exception {
        String vencido = Jwts.builder().setSubject("42").claim("tipo", "acceso").claim("rol", "cliente")
                .setIssuedAt(new Date(System.currentTimeMillis() - 7_200_000L))
                .setExpiration(new Date(System.currentTimeMillis() - 3_600_000L)) // venció hace una hora
                .signWith(Keys.hmacShaKeyFor(SECRETO.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256)
                .compact();
        mvc.perform(get("/api/servicios/mios-prueba").header("Authorization", "Bearer " + vencido))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void conFirmaFalsaResponde401() throws Exception {
        String falso = Jwts.builder().setSubject("1").claim("tipo", "acceso").claim("rol", "admin")
                .setExpiration(new Date(System.currentTimeMillis() + 3_600_000L))
                .signWith(Keys.hmacShaKeyFor("otro-secreto-que-no-es-el-de-movify-0123456789".getBytes(StandardCharsets.UTF_8)),
                        SignatureAlgorithm.HS256)
                .compact();
        mvc.perform(get("/api/servicios/mios-prueba").header("Authorization", "Bearer " + falso))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void conTokenValidoEntra() throws Exception {
        String token = jwt.generarAcceso(42L, "ana@movify.co", "cliente", List.of("cliente"), List.of());
        mvc.perform(get("/api/servicios/mios-prueba").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    // ───────────── HU 2.3: token válido pero de otro rol → 403 ─────────────

    @Test
    void clienteNoEntraAlPanelAdminResponde403() throws Exception {
        String cliente = jwt.generarAcceso(42L, "ana@movify.co", "cliente", List.of("cliente"), List.of("servicios.solicitar"));
        mvc.perform(get("/api/admin/panel-prueba").header("Authorization", "Bearer " + cliente))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("No tienes permiso para esta acción"));
    }

    @Test
    void adminSiEntraAlPanel() throws Exception {
        String admin = jwt.generarAcceso(1L, "admin@movify.co", "admin", List.of("admin"), List.of());
        mvc.perform(get("/api/admin/panel-prueba").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
    }

    @Test
    void sinElPermisoDelEndpointResponde403() throws Exception {
        String conductor = jwt.generarAcceso(7L, "luis@movify.co", "conductor", List.of("conductor"), List.of("servicios.ver_disponibles"));
        mvc.perform(get("/api/servicios/solicitar-prueba").header("Authorization", "Bearer " + conductor))
                .andExpect(status().isForbidden());

        String cliente = jwt.generarAcceso(42L, "ana@movify.co", "cliente", List.of("cliente"), List.of("servicios.solicitar"));
        mvc.perform(get("/api/servicios/solicitar-prueba").header("Authorization", "Bearer " + cliente))
                .andExpect(status().isOk());
    }

    @Test
    void bcryptEsUnUnicoBeanInyectado() {
        Map<String, PasswordEncoder> encoders = contexto.getBeansOfType(PasswordEncoder.class);
        assertThat(encoders).hasSize(1);
        assertThat(encoders.values().iterator().next()).isInstanceOf(BCryptPasswordEncoder.class);
    }
}
