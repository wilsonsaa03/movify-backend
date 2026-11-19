package com.movify.backend.tiemporeal;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket nativo (sin STOMP: la app usa el WebSocket del navegador, sin librerías extra).
 * Mismos orígenes que el CORS de la API (app.cors.allowed-origins): la app instalada, la web y, en local,
 * la red de la casa.
 */
@Configuration
@EnableWebSocket
public class TiempoRealConfig implements WebSocketConfigurer {

    private final CanalTiempoReal canal;
    private final String[] origenes;

    public TiempoRealConfig(CanalTiempoReal canal, @Value("${app.cors.allowed-origins}") String origenes) {
        this.canal = canal;
        this.origenes = Arrays.stream(origenes.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registro) {
        // Los límites de tamaño se ponen por sesión (CanalTiempoReal): un bean del contenedor rompe las pruebas sin servidor real
        registro.addHandler(canal, "/ws/tiempo-real").setAllowedOriginPatterns(origenes);
    }
}
