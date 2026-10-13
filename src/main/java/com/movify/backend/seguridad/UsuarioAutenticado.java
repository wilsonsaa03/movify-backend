package com.movify.backend.seguridad;

import java.util.Set;

/**
 * Lo que viaja dentro del JWT. Es la única fuente de verdad sobre quién hace la petición.
 *
 * @param rol      rol principal (decide a qué pantalla entra: admin > soporte > conductor > cliente)
 * @param roles    todos sus roles (usuario_roles)
 * @param permisos permisos efectivos "modulo.accion" (v_permisos_efectivos)
 */
public record UsuarioAutenticado(Long id, String correo, String rol, Set<String> roles, Set<String> permisos) {

    public boolean tieneRol(String r) {
        return roles.contains(r);
    }

    public boolean tienePermiso(String p) {
        return permisos.contains(p);
    }
}
