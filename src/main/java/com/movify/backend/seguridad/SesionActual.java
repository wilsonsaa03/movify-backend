package com.movify.backend.seguridad;

import java.util.Objects;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.movify.backend.comun.AccesoDenegadoException;
import com.movify.backend.comun.Roles;

/**
 * Quién hace la petición, sacado del JWT. Los controladores nunca confían en
 * usuario_id / conductor_id que lleguen en el body o en la URL.
 *
 * En el modelo v2.3 el conductor ES el usuario: conductores.usuario_id es su llave,
 * así que "conductorId" y "usuarioId" son el mismo número.
 */
@Component
public class SesionActual {

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

    /** Admin o soporte. */
    public boolean esPersonal() {
        UsuarioAutenticado u = usuario();
        return u.tieneRol(Roles.ADMIN) || u.tieneRol(Roles.SOPORTE);
    }

    public boolean esAdmin() {
        return usuario().tieneRol(Roles.ADMIN);
    }

    /** Actor para la BD cuando actúa el personal (soporte o admin). */
    public String actorPersonal() {
        return esAdmin() ? Roles.ADMIN : Roles.SOPORTE;
    }

    /** El usuario autenticado debe tener perfil de conductor (rol conductor). */
    public Long conductorId() {
        UsuarioAutenticado u = usuario();
        if (!u.tieneRol(Roles.CONDUCTOR)) {
            throw new AccesoDenegadoException("Tu cuenta no tiene perfil de conductor");
        }
        return u.id();
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
