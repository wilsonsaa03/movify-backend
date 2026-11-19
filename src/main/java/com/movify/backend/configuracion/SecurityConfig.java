package com.movify.backend.configuracion;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.movify.backend.seguridad.JwtFiltro;
import com.movify.backend.seguridad.LimitePeticionesFiltro;
import com.movify.backend.seguridad.VigilanciaSeguridad;

/**
 * Reglas gruesas por URL. Las finas van en cada método con
 * @PreAuthorize("hasAuthority('modulo.accion')"), usando los permisos del DER (rol_permisos).
 *
 * Sin CSRF a propósito: la API no usa cookies. El token viaja en el header Authorization, que un sitio
 * ajeno no puede poner en una petición del navegador de la víctima (eso es justo lo que explota CSRF).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${app.cors.allowed-origins}")
    private String origenesPermitidos;

    /** Único encoder de la aplicación: se inyecta donde haga falta. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtFiltro jwtFiltro, LimitePeticionesFiltro limite,
            VigilanciaSeguridad vigilancia) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // API sin cookies: el token va en el header
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Cabeceras de seguridad de la API (la web tiene las suyas en public/.htaccess)
                .headers(h -> h
                        .contentSecurityPolicy(c -> c.policyDirectives(
                                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER))
                        // HSTS siempre: detrás de Apache la petición llega como http, pero al navegador le llega por https
                        .httpStrictTransportSecurity(s -> s.requestMatcher(AnyRequestMatcher.INSTANCE)
                                .includeSubDomains(true).maxAgeInSeconds(31_536_000))
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                                "camera=(), microphone=(), geolocation=(), payment=()")))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> responder(res, 401, "Debes iniciar sesión"))
                        .accessDeniedHandler((req, res, ex) -> {
                            if (req.getRequestURI().startsWith("/api/admin")) {
                                vigilancia.registrar("admin_sin_permiso", ContextoPeticion.ipCliente(req),
                                        req.getMethod() + " " + req.getRequestURI(), "");
                            }
                            responder(res, 403, "No tienes permiso para esta acción");
                        }))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Públicos: entrar, registrarse y recuperar el acceso
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/login",
                                "/api/auth/login-celular",
                                "/api/auth/login-codigo",
                                "/api/auth/login-google",
                                "/api/auth/login-verificar",
                                "/api/auth/registro",
                                "/api/auth/registro-movil",
                                "/api/auth/registro-admin",
                                "/api/auth/codigo",
                                "/api/auth/codigo/verificar",
                                "/api/auth/refresh",
                                "/api/auth/logout",
                                "/api/auth/clave/restablecer",
                                "/api/auth/olvide-password",
                                "/api/auth/restablecer-password").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/test/ping", "/api/catalogos/**", "/api/tarifas/cotizar")
                            .permitAll()
                        // Seguir un viaje compartido: lo abre la familia sin cuenta (token aleatorio que vence)
                        .requestMatchers(HttpMethod.GET, "/api/publico/viajes/*").permitAll()
                        // Rastreo de una encomienda sin cuenta (token de 256 bits, datos limitados, vence solo)
                        .requestMatchers(HttpMethod.GET, "/api/publico/encomiendas/*").permitAll()
                        // WebSocket: el apretón de manos no lleva token; el JWT va en el primer mensaje (CanalTiempoReal)
                        .requestMatchers(HttpMethod.GET, "/ws/tiempo-real").permitAll()
                        // Fotos de perfil: el nombre del archivo es un UUID imposible de adivinar (van en <img src>)
                        .requestMatchers(HttpMethod.GET, "/api/usuarios/*/foto/**").permitAll()
                        // Fotos del catálogo de domicilios (comercios y productos): también van en <img src>
                        .requestMatchers(HttpMethod.GET, "/api/domicilios/fotos/*").permitAll()
                        // Panel administrativo: solo personal; el permiso exacto se revisa en cada método
                        .requestMatchers("/api/admin/**", "/api/transporte/admin/**", "/api/auth/usuarios/**")
                            .hasAnyRole("ADMIN", "SOPORTE")
                        // Cédulas, licencias y SOAT: solo personal con permiso
                        .requestMatchers("/api/conductor/documento/**").hasAuthority("admin_conductores.ver")
                        // Todo lo demás exige sesión; la propiedad del recurso se valida en cada endpoint
                        .anyRequest().authenticated())
                // Primero el límite de peticiones (después de CORS, para que el 429 llegue legible a la web)
                .addFilterBefore(limite, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFiltro, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void responder(jakarta.servlet.http.HttpServletResponse res, int status, String mensaje)
            throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"error\":\"" + mensaje + "\"}");
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Patrones: un origen exacto se compara igual que antes; en local se puede permitir la red de la casa
        // (p. ej. http://192.168.*.*:8100) para probar desde el celular por Wi-Fi.
        config.setAllowedOriginPatterns(Arrays.stream(origenesPermitidos.split(",")).map(String::trim).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Plataforma"));
        config.setExposedHeaders(List.of("Content-Disposition", "Retry-After"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
