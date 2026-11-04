package com.movify.backend.seguridad;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Actividad sospechosa en memoria (las últimas {@value #MAXIMO}): límites de peticiones superados,
 * intentos de entrar al panel admin sin permiso, tokens revocados que se siguen usando.
 * Cada evento también va al log con el prefijo [SEGURIDAD] (journalctl -u movify | grep SEGURIDAD).
 * Los intentos de inicio de sesión fallidos ya quedan en la tabla registro_accesos.
 */
@Component
public class VigilanciaSeguridad {

    private static final Logger log = LoggerFactory.getLogger(VigilanciaSeguridad.class);
    static final int MAXIMO = 300;

    public record Evento(Instant fecha, String tipo, String ip, String ruta, String detalle) {
    }

    private final Deque<Evento> eventos = new ArrayDeque<>();

    /** @param tipo limite_superado | admin_sin_permiso | token_revocado */
    public void registrar(String tipo, String ip, String ruta, String detalle) {
        String r = ruta == null ? "" : ruta.length() > 120 ? ruta.substring(0, 120) : ruta;
        // El log nunca recibe saltos de línea del cliente (no se pueden inventar líneas falsas)
        log.warn("[SEGURIDAD] {} ip={} ruta={} {}", tipo, limpiar(ip), limpiar(r), limpiar(detalle));
        synchronized (eventos) {
            eventos.addFirst(new Evento(Instant.now(), tipo, ip, r, detalle));
            while (eventos.size() > MAXIMO) eventos.removeLast();
        }
    }

    public List<Evento> recientes() {
        synchronized (eventos) {
            return new ArrayList<>(eventos);
        }
    }

    /** Conteo por IP de los eventos recientes (las que más se repiten primero). */
    public Map<String, Long> porIp() {
        Map<String, Long> m = new LinkedHashMap<>();
        for (Evento e : recientes()) m.merge(String.valueOf(e.ip()), 1L, Long::sum);
        return m;
    }

    private static String limpiar(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n\\t]", "_");
    }
}
