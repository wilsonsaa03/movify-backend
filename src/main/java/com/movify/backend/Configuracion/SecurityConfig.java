package com.movify.backend.Configuracion;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.movify.backend.Seguridad.JwtFiltro;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.cors.allowed-origins}")
    private String origenesPermitidos;

    /** Único encoder de la aplicación: se inyecta donde haga falta. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtFiltro jwtFiltro) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // API sin cookies: el token va en el header
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> responder(res, 401, "Debes iniciar sesión"))
                        .accessDeniedHandler((req, res, ex) -> responder(res, 403, "No tienes permiso para esta acción")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Públicos
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/login",
                                "/api/auth/login-google",
                                "/api/auth/registro",
                                "/api/auth/registro-admin",
                                "/api/auth/olvide-password",
                                "/api/auth/restablecer-password",
                                "/api/conductor/registro").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/test/ping").permitAll()
                        // Administración: soporte consulta, solo admin modifica
                        .requestMatchers(HttpMethod.GET, "/api/transporte/admin/**", "/api/auth/usuarios")
                            .hasAnyRole("ADMIN", "SOPORTE")
                        .requestMatchers("/api/transporte/admin/**", "/api/auth/usuarios/**").hasRole("ADMIN")
                        // Cédulas, licencias y SOAT: solo personal interno
                        .requestMatchers("/api/conductor/documento/**").hasAnyRole("ADMIN", "SOPORTE")
                        // Todo lo demás exige sesión; la propiedad del recurso se valida en cada endpoint
                        .anyRequest().authenticated())
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
        config.setAllowedOrigins(Arrays.stream(origenesPermitidos.split(",")).map(String::trim).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setExposedHeaders(List.of("Content-Disposition"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
