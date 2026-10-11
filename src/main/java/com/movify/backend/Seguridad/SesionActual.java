package com.movify.backend.Seguridad;

import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.movify.backend.Comun.AccesoDenegadoException;
import com.movify.backend.Comun.Roles;

/**
 * Quién hace la petición, sacado del JWT. Los controladores ya no confían en
 * usuario_id / conductor_id que lleguen en el body o en la URL.
 */
@Component
public class SesionActual {

    private final JdbcTemplate db;

    public SesionActual(JdbcTemplate db) {
        this.db = db;
    }

    public UsuarioAutenticado usuario() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UsuarioAutenticado u)) {
            throw new AccesoDenegadoException("Debes iniciar sesión");
        }
        return u;
    }

    public Long usuarioId() {
        return usuario().id();
    }

    public boolean esAdmin() {
        return Roles.ADMIN.equals(usuario().rol());
    }

    /** Admin o soporte. */
    public boolean esPersonal() {
        String rol = usuario().rol();
        return Roles.ADMIN.equals(rol) || Roles.SOPORTE.equals(rol);
    }

    /** id en la tabla conductores del usuario autenticado; 403 si no es conductor. */
    public Long conductorId() {
        List<Long> ids = db.queryForList(
                "SELECT id FROM conductores WHERE usuario_id = ? ORDER BY id LIMIT 1", Long.class, usuarioId());
        if (ids.isEmpty()) {
            throw new AccesoDenegadoException("Tu cuenta no tiene perfil de conductor");
        }
        return ids.get(0);
    }

    /** El conductorId de la URL debe ser el del token (el personal puede ver cualquiera). */
    public void exigirConductor(Long conductorId) {
        if (esPersonal()) return;
        if (!Objects.equals(conductorId, conductorId())) {
            throw new AccesoDenegadoException("No puedes consultar datos de otro conductor");
        }
    }

    /** El usuarioId de la URL debe ser el del token (el personal puede ver cualquiera). */
    public void exigirUsuario(Long usuarioId) {
        if (esPersonal()) return;
        if (!Objects.equals(usuarioId, usuarioId())) {
            throw new AccesoDenegadoException("No puedes consultar datos de otro usuario");
        }
    }
}
