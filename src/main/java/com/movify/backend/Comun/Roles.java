package com.movify.backend.Comun;

import java.util.Locale;

/** Roles de la plataforma (DER v2.3). En la BD vieja el cliente aparece como "usuario". */
public final class Roles {
    public static final String CLIENTE = "cliente";
    public static final String CONDUCTOR = "conductor";
    public static final String SOPORTE = "soporte";
    public static final String ADMIN = "admin";

    private Roles() {
    }

    public static String normalizar(String rol) {
        if (rol == null || rol.isBlank()) return CLIENTE;
        String r = rol.trim().toLowerCase(Locale.ROOT);
        return "usuario".equals(r) ? CLIENTE : r;
    }
}
