package com.movify.backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.movify.backend.configuracion.ContextoPeticion;
import com.movify.backend.configuracion.SecurityConfig;

/**
 * Protecciones de la revisión de seguridad que se prueban sin base de datos:
 * cabeceras de seguridad, límite de peticiones (429), CORS cerrado, IP real detrás del proxy
 * y bitácora de intentos de entrar al panel admin sin permiso.
 */
@WebMvcTest(controllers = ProteccionesTest.Rutas.class, properties = {
        "app.jwt.secret=secreto-de-pruebas-de-protecciones-movify-0123456789",
        "app.cors.allowed-origins=https://movify.ds2.eleueleo.com" })
@Import({ SecurityConfig.class, JwtFiltro.class, JwtUtil.class, LimitePeticionesFiltro.class, VigilanciaSeguridad.class,
        ProteccionesTest.Rutas.class })
class ProteccionesTest {

    @RestController
    static class Rutas {
        @PostMapping("/api/auth/login-celular")
        Map<String, Object> login() {
            return Map.of("ok", true);
        }

        @PostMapping("/api/auth/codigo")
        Map<String, Object> codigo() {
            return Map.of("ok", true);
        }

        @GetMapping("/api/test/ping")
        String ping() {
            return "pong";
        }

        @GetMapping("/api/admin/usuarios-prueba")
        Map<String, Object> admin() {
            return Map.of("ok", true);
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtUtil jwt;
    @Autowired
    VigilanciaSeguridad vigilancia;

    private static RequestPostProcessor desde(String ip) {
        return r -> {
            r.setRemoteAddr(ip);
            return r;
        };
    }

    @Test
    void respondeConCabecerasDeSeguridad() throws Exception {
        mvc.perform(get("/api/test/ping").with(desde("10.1.0.1")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"))
                .andExpect(header().string("Content-Security-Policy",
                        "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
                .andExpect(header().exists("Permissions-Policy"));
    }

    @Test
    void loginSeFrenaAlPasarElLimiteDeLaIp() throws Exception {
        for (int i = 0; i < LimitePeticionesFiltro.LOGIN.maximo(); i++) {
            mvc.perform(post("/api/auth/login-celular").with(desde("10.2.0.1"))).andExpect(status().isOk());
        }
        mvc.perform(post("/api/auth/login-celular").with(desde("10.2.0.1")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        // Otra IP no se ve afectada
        mvc.perform(post("/api/auth/login-celular").with(desde("10.2.0.2"))).andExpect(status().isOk());
        assertThat(vigilancia.recientes()).anyMatch(e -> e.tipo().equals("limite_superado") && e.ip().equals("10.2.0.1"));
    }

    @Test
    void codigosSmsMaximoCincoPorIp() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/codigo").with(desde("10.3.0.1"))).andExpect(status().isOk());
        }
        mvc.perform(post("/api/auth/codigo").with(desde("10.3.0.1"))).andExpect(status().isTooManyRequests());
    }

    @Test
    void xForwardedForInventadoNoCambiaLaIp() throws Exception {
        // Directo desde internet: el header se ignora
        MockHttpServletRequest directo = new MockHttpServletRequest();
        directo.setRemoteAddr("203.0.113.9");
        directo.addHeader("X-Forwarded-For", "1.2.3.4");
        assertThat(ContextoPeticion.ipCliente(directo)).isEqualTo("203.0.113.9");

        // Por Apache (127.0.0.1): vale la IP que agregó Apache al final, no la que inventó el cliente
        MockHttpServletRequest proxy = new MockHttpServletRequest();
        proxy.setRemoteAddr("127.0.0.1");
        proxy.addHeader("X-Forwarded-For", "1.2.3.4, 198.51.100.7");
        assertThat(ContextoPeticion.ipCliente(proxy)).isEqualTo("198.51.100.7");
    }

    @Test
    void falsificarLaIpNoSaltaElLimite() throws Exception {
        for (int i = 0; i < 5; i++) {
            final int n = i;
            mvc.perform(post("/api/auth/codigo").with(desde("10.4.0.1")).header("X-Forwarded-For", "9.9.9." + n))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/auth/codigo").with(desde("10.4.0.1")).header("X-Forwarded-For", "9.9.9.99"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void corsSoloParaElDominioPermitido() throws Exception {
        mvc.perform(get("/api/test/ping").with(desde("10.5.0.1")).header("Origin", "https://sitio-malo.com"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/test/ping").with(desde("10.5.0.1")).header("Origin", "https://movify.ds2.eleueleo.com"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://movify.ds2.eleueleo.com"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test
    void clienteQueIntentaEntrarAlPanelQuedaRegistrado() throws Exception {
        String cliente = jwt.generarAcceso(42L, "ana@movify.co", "cliente", List.of("cliente"), List.of());
        mvc.perform(get("/api/admin/usuarios-prueba").with(desde("10.6.0.1")).header("Authorization", "Bearer " + cliente)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        assertThat(vigilancia.recientes()).anyMatch(e -> e.tipo().equals("admin_sin_permiso") && e.ip().equals("10.6.0.1"));
    }
}
