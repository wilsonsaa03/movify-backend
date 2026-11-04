package com.movify.backend.configuracion;

import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Datos de la petición que el DER guarda en las bitácoras (registro_accesos, códigos SMS).
 * La plataforma la manda la app en el header X-Plataforma (android | ios | web).
 */
public record ContextoPeticion(String ip, String userAgent, String plataforma) {

    public static ContextoPeticion de(HttpServletRequest req) {
        String ip = ipCliente(req);

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

    /**
     * IP real del cliente. Detrás de Apache llega en X-Forwarded-For, pero el cliente puede mandar ese
     * header inventado y Apache solo AGREGA la IP real al final: por eso se toma el último valor, y solo
     * si la petición viene del propio servidor (el proxy). Si no, cualquiera falsificaría su IP en la
     * bitácora y en el límite de peticiones.
     */
    public static String ipCliente(HttpServletRequest req) {
        String remota = req.getRemoteAddr();
        String ip = remota;
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank() && esProxyLocal(remota)) {
            String[] partes = xff.split(",");
            ip = partes[partes.length - 1].trim();
        }
        if (ip != null && ip.length() > 45) ip = ip.substring(0, 45);
        return ip;
    }

    private static boolean esProxyLocal(String ip) {
        return ip != null && (ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1"));
    }
}
