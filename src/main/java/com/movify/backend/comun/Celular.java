package com.movify.backend.comun;

/**
 * Celulares colombianos. En la BD se guardan como +57 y 10 dígitos (+573001234567),
 * que es lo que exige el CHECK de usuarios.telefono.
 */
public final class Celular {

    private Celular() {
    }

    /**
     * Acepta "300 123 4567", "3001234567", "+57 300-123-4567"...
     * @return "+573001234567", o null si no es un celular colombiano válido.
     */
    public static String normalizar(String texto) {
        if (texto == null) return null;
        String d = texto.replaceAll("\\D", "");
        if (d.length() == 12 && d.startsWith("57")) d = d.substring(2);
        if (d.length() != 10 || d.charAt(0) != '3') return null;
        return "+57" + d;
    }

    /** Igual que normalizar, pero responde 400 si no es válido. */
    public static String exigir(String texto) {
        String c = normalizar(texto);
        if (c == null) {
            throw new ReglaNegocioException(400, "Escribe un celular colombiano de 10 dígitos que empiece por 3");
        }
        return c;
    }

    /** "+573001234567" → "+57 300 123 4567" (para mensajes). */
    public static String visible(String e164) {
        if (e164 == null || e164.length() != 13) return e164;
        return "+57 " + e164.substring(3, 6) + " " + e164.substring(6, 9) + " " + e164.substring(9);
    }

    /** Teléfono fijo o celular para contactos (+57 3XXXXXXXXX o +57 60XXXXXXXX), o null. */
    public static String normalizarContacto(String texto) {
        if (texto == null) return null;
        String d = texto.replaceAll("\\D", "");
        if (d.length() == 12 && d.startsWith("57")) d = d.substring(2);
        if (d.length() == 10 && (d.charAt(0) == '3' || d.startsWith("60"))) return "+57" + d;
        return null;
    }
}
