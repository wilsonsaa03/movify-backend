package com.movify.backend.configuracion;

import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Datos de la petición que el DER guarda en las bitácoras (registro_accesos, códigos SMS).
 * La plataforma la manda la app en el header X-Plataforma (android | ios | web).
 */
public record ContextoPeticion(String ip, String userAgent, String plataforma) {

    public static ContextoPeticion de(HttpServletRequest req) {
        // Detrás de Apache el IP real llega en X-Forwarded-For
        String ip = req.getHeader("X-Forwarded-For");
        ip = ip == null || ip.isBlank() ? req.getRemoteAddr() : ip.split(",")[0].trim();
        if (ip != null && ip.length() > 45) ip = ip.substring(0, 45);

        String ua = req.getHeader("User-Agent");
        if (ua != null && ua.length() > 300) ua = ua.substring(0, 300);

        String p = req.getHeader("X-Plataforma");
        p = p == null ? "" : p.trim().toLowerCase(Locale.ROOT);
        if (!p.equals("android") && !p.equals("ios") && !p.equals("web")) {
            String u = ua == null ? "" : ua.toLowerCase(Locale.ROOT);
            p = u.contains("android") ? "android" : (u.contains("iphone") || u.contains("ipad")) ? "ios" : "web";
        }
        return new ContextoPeticion(ip, ua, p);
    }
}
