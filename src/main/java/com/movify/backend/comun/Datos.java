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

    // ───────────── Datos personales en listas (Ley 1581): parciales en listas, completos solo en el detalle ─────────────

    /** "+573001234567" → "300 ••• ••67". */
    public static String ocultarTelefono(String tel) {
        if (tel == null || tel.isBlank()) return null;
        String d = tel.replaceAll("\\D", "");
        if (d.startsWith("57") && d.length() == 12) d = d.substring(2);
        if (d.length() < 4) return "••••";
        return d.substring(0, Math.min(3, d.length() - 2)) + " ••• ••" + d.substring(d.length() - 2);
    }

    /** "ana.riascos@correo.com" → "an•••@correo.com". */
    public static String ocultarCorreo(String correo) {
        if (correo == null || correo.isBlank()) return null;
        int arroba = correo.indexOf('@');
        if (arroba <= 0) return "•••";
        return correo.substring(0, Math.min(2, arroba)) + "•••" + correo.substring(arroba);
    }

    /** "1.111.222.333" → "••••2333". */
    public static String ocultarDocumento(String doc) {
        if (doc == null || doc.isBlank()) return null;
        String d = doc.replaceAll("[^0-9A-Za-z]", "");
        return d.length() <= 4 ? "••••" : "••••" + d.substring(d.length() - 4);
    }

    public static Map<String, Object> sinNulos(Map<String, Object> m) {
        m.values().removeIf(v -> v == null);
        return m;
    }
}
