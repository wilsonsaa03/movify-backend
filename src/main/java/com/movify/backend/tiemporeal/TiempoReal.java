package com.movify.backend.tiemporeal;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.movify.backend.comun.DespuesDelCommit;

/**
 * Avisos en tiempo real por WebSocket (/ws/tiempo-real) a quienes siguen un servicio:
 * el cliente y el conductor (y el personal). Lo que viaja:
 *
 *   { "tipo": "ubicacion",  "servicio": 12, "lat": 3.88, "lng": -77.02, "rumbo": 90, "velocidad": 30 }
 *   { "tipo": "mensaje",    "servicio": 12, "id": 5, "remitente_id": 7, "contenido": "Ya llegué", "creado_en": "..." }
 *   { "tipo": "estado",     "servicio": 12, "estado": "en_curso" }
 *   { "tipo": "simulacion", "servicio": 12, "estado": "pausada", "version": 3 }
 *   { "tipo": "pago",       "servicio": 12, "estado": "aprobado" }
 *   { "tipo": "evidencia",  "servicio": 12, "evidencia": "entrega" }
 *
 * Es solo un aviso rápido: la fuente de verdad sigue siendo la base de datos y la app vuelve a leerla por
 * HTTP si lo necesita. Si el WebSocket se cae, las apps siguen con el sondeo de siempre (más lento).
 *
 * Una sola instancia del backend: las suscripciones viven en memoria. Con varias instancias habría que
 * repartir los avisos (p. ej. LISTEN/NOTIFY de PostgreSQL); hoy MoviFY corre en un solo servidor.
 */
@Component
public class TiempoReal {

    private static final Logger log = LoggerFactory.getLogger(TiempoReal.class);

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    /** servicio → sesiones que lo siguen. */
    private final Map<Long, Set<WebSocketSession>> porServicio = new ConcurrentHashMap<>();

    void seguir(Long servicioId, WebSocketSession s) {
        porServicio.computeIfAbsent(servicioId, k -> ConcurrentHashMap.newKeySet()).add(s);
    }

    void dejar(Long servicioId, WebSocketSession s) {
        porServicio.computeIfPresent(servicioId, (k, set) -> {
            set.remove(s);
            return set.isEmpty() ? null : set;
        });
    }

    /** La sesión se cerró: sale de todos los servicios. */
    void olvidar(WebSocketSession s) {
        for (Long id : porServicio.keySet()) dejar(id, s);
    }

    /** ¿Alguien está siguiendo este servicio? (para no armar avisos que nadie va a leer). */
    public boolean conOyentes(Long servicioId) {
        Set<WebSocketSession> set = porServicio.get(servicioId);
        return set != null && !set.isEmpty();
    }

    /** Publica cuando la transacción ya quedó guardada (si se deshace, nadie se entera de algo que no pasó). */
    public void publicar(Long servicioId, String tipo, Map<String, ?> datos) {
        if (servicioId == null || !conOyentes(servicioId)) return;
        DespuesDelCommit.ejecutar(() -> enviarYa(servicioId, tipo, datos));
    }

    /** Sin esperar el commit: para lo que no depende de una transacción (p. ej. el reloj de la simulación). */
    public void enviarYa(Long servicioId, String tipo, Map<String, ?> datos) {
        Set<WebSocketSession> set = porServicio.get(servicioId);
        if (set == null || set.isEmpty()) return;
        Map<String, Object> evento = new LinkedHashMap<>();
        evento.put("tipo", tipo);
        evento.put("servicio", servicioId);
        if (datos != null) evento.putAll(datos);
        TextMessage msg;
        try {
            msg = new TextMessage(json.writeValueAsString(evento));
        } catch (IOException e) {
            log.warn("No se pudo armar el aviso {} del servicio {}", tipo, servicioId, e);
            return;
        }
        for (WebSocketSession s : set) {
            if (!s.isOpen()) {
                set.remove(s);
                continue;
            }
            try {
                s.sendMessage(msg);
            } catch (IOException | IllegalStateException e) {
                // Cliente lento o desconectado: se cierra y la app vuelve a conectarse sola
                log.debug("No se pudo enviar el aviso a la sesión {}: {}", s.getId(), e.getMessage());
                set.remove(s);
                cerrarSinRuido(s);
            }
        }
    }

    static void cerrarSinRuido(WebSocketSession s) {
        try {
            s.close();
        } catch (IOException ignorada) {
            // ya estaba cerrada
        }
    }
}
