package com.movify.backend.comun;

import java.util.Map;

/** Utilidades pequeñas para leer filas de JdbcTemplate (Map<String, Object>). */
public final class Datos {

    private Datos() {
    }

    public static Long aLong(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    public static double num(Object o) {
        return o == null ? 0 : ((Number) o).doubleValue();
    }

    /** Número de un JSON libre (Map) o null; evita el NPE de mezclar double y null en un ternario. */
    public static Double decimal(Object o) {
        return o instanceof Number n ? Double.valueOf(n.doubleValue()) : null;
    }

    public static String texto(Object o) {
        return o == null ? null : o.toString();
    }

    public static String nvl(String s) {
        return s == null ? "" : s;
    }

    public static String vacioANull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Recorta un texto libre al largo de la columna. */
    public static String recortar(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    public static Map<String, Object> sinNulos(Map<String, Object> m) {
        m.values().removeIf(v -> v == null);
        return m;
    }
}
