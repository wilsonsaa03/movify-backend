package com.movify.backend.seguridad;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Lee "Authorization: Bearer <jwt>" y deja al usuario en el SecurityContext con:
 *  - ROLE_ADMIN, ROLE_CONDUCTOR...  (uno por rol)
 *  - cada permiso "modulo.accion"    (para @PreAuthorize("hasAuthority('admin_conductores.aprobar')"))
 */
@Component
public class JwtFiltro extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    public JwtFiltro(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            UsuarioAutenticado usuario = jwtUtil.leerAcceso(header.substring(7));
            if (usuario != null) {
                List<GrantedAuthority> autoridades = new ArrayList<>();
                usuario.roles().forEach(r -> autoridades.add(new SimpleGrantedAuthority("ROLE_" + r.toUpperCase())));
                usuario.permisos().forEach(p -> autoridades.add(new SimpleGrantedAuthority(p)));
                var auth = new UsernamePasswordAuthenticationToken(usuario, null, autoridades);
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }
}
