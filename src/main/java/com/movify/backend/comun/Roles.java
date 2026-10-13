package com.movify.backend.comun;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Roles de la tabla roles (DER v2.3). Un usuario puede tener varios (usuario_roles). */
public final class Roles {
    public static final String CLIENTE = "cliente";
    public static final String CONDUCTOR = "conductor";
    public static final String SOPORTE = "soporte";
    public static final String ADMIN = "admin";

    /** Orden para elegir el rol "principal" que decide a qué pantalla entra la persona. */
    private static final List<String> PRIORIDAD = List.of(ADMIN, SOPORTE, CONDUCTOR, CLIENTE);

    private Roles() {
    }

    public static String normalizar(String rol) {
        if (rol == null || rol.isBlank()) return CLIENTE;
        String r = rol.trim().toLowerCase(Locale.ROOT);
        return "usuario".equals(r) ? CLIENTE : r;
    }

    public static String principal(Collection<String> roles) {
        for (String r : PRIORIDAD) {
            if (roles.contains(r)) return r;
        }
        return CLIENTE;
    }
}
