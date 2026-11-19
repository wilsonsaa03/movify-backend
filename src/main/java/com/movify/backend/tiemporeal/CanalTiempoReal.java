package com.movify.backend.tiemporeal;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movify.backend.comun.Roles;
import com.movify.backend.seguridad.JwtUtil;
import com.movify.backend.seguridad.UsuarioAutenticado;
import com.movify.backend.seguridad.VigenciaTokens;

import jakarta.annotation.PreDestroy;

/**
 * Canal WebSocket /ws/tiempo-real. Protocolo (JSON de texto):
 *
 *   app → { "tipo": "auth", "token": "<JWT de acceso>" }        primero, antes de 10 s
 *   app ← { "tipo": "listo", "usuario": 7 }
 *   app → { "tipo": "seguir", "servicio": 12 }                   solo si es su cliente, su conductor o personal
 *   app ← { "tipo": "siguiendo", "servicio": 12 }
 *   app → { "tipo": "dejar", "servicio": 12 } · { "tipo": "ping" } → { "tipo": "pong" }
 *
 * El token NO va en la URL (quedaría en los registros del servidor y del proxy): va en el primer mensaje.
 * Un token revocado (cuenta bloqueada, roles cambiados) no entra, igual que en JwtFiltro.
 */
@Component
public class CanalTiempoReal extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CanalTiempoReal.class);
    private static final int SEGUNDOS_PARA_AUTENTICARSE = 10;
    private static final int MAX_SERVICIOS_POR_SESION = 5;
    private static final int MAX_SESIONES_POR_USUARIO = 6;
    private static final String USUARIO = "movify.usuario";
    private static final String SERVICIOS = "movify.servicios";

    private final TiempoReal tiempoReal;
    private final JwtUtil jwt;
    private final ObjectProvider<VigenciaTokens> vigencia;
    private final JdbcTemplate db;
    private final ObjectMapper json = new ObjectMapper();
    private final ScheduledExecutorService reloj = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "movify-ws-auth");
        t.setDaemon(true);
        return t;
    });
    /** Sesión original → la envoltura que permite enviar desde varios hilos a la vez. */
    private final Map<String, WebSocketSession> envueltas = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> sesionesPorUsuario = new ConcurrentHashMap<>();

    public CanalTiempoReal(TiempoReal tiempoReal, JwtUtil jwt, ObjectProvider<VigenciaTokens> vigencia, JdbcTemplate db) {
        this.tiempoReal = tiempoReal;
        this.jwt = jwt;
        this.vigencia = vigencia;
        this.db = db;
    }

    @PreDestroy
    void apagar() {
        reloj.shutdownNow();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession sesion) {
        // Los mensajes de la app son pequeños (auth, seguir, ping)
        sesion.setTextMessageSizeLimit(8 * 1024);
        sesion.setBinaryMessageSizeLimit(1024);
        // Envíos de hasta 5 s y 64 KB en cola: un celular con mala señal no frena a los demás
        WebSocketSession segura = new ConcurrentWebSocketSessionDecorator(sesion, 5000, 64 * 1024);
        envueltas.put(sesion.getId(), segura);
        reloj.schedule(() -> {
            if (sesion.isOpen() && sesion.getAttributes().get(USUARIO) == null) {
                cerrar(sesion, CloseStatus.POLICY_VIOLATION.withReason("Sin autenticar"));
            }
        }, SEGUNDOS_PARA_AUTENTICARSE, TimeUnit.SECONDS);
    }

    @Override
    protected void handleTextMessage(WebSocketSession sesion, TextMessage mensaje) throws IOException {
        JsonNode m;
        try {
            m = json.readTree(mensaje.getPayload());
        } catch (IOException e) {
            responder(sesion, Map.of("tipo", "error", "mensaje", "Mensaje no válido"));
            return;
        }
        String tipo = m.path("tipo").asText("");
        UsuarioAutenticado yo = (UsuarioAutenticado) sesion.getAttributes().get(USUARIO);
        if ("auth".equals(tipo)) {
            autenticar(sesion, m.path("token").asText(""));
            return;
        }
        if (yo == null) {
            cerrar(sesion, CloseStatus.POLICY_VIOLATION.withReason("Primero autentícate"));
            return;
        }
        switch (tipo) {
            case "seguir" -> seguir(sesion, yo, m.path("servicio").asLong(0));
            case "dejar" -> {
                long id = m.path("servicio").asLong(0);
                servicios(sesion).remove(id);
                tiempoReal.dejar(id, envuelta(sesion));
            }
            case "ping" -> responder(sesion, Map.of("tipo", "pong"));
            default -> responder(sesion, Map.of("tipo", "error", "mensaje", "Tipo de mensaje desconocido"));
        }
    }

    private void autenticar(WebSocketSession sesion, String token) throws IOException {
        UsuarioAutenticado u = token.isBlank() ? null : jwt.leerAcceso(token);
        VigenciaTokens v = vigencia.getIfAvailable();
        if (u == null || (v != null && !v.vigente(u))) {
            cerrar(sesion, CloseStatus.POLICY_VIOLATION.withReason("Token no válido"));
            return;
        }
        Set<String> mias = sesionesPorUsuario.computeIfAbsent(u.id(), k -> ConcurrentHashMap.newKeySet());
        if (mias.size() >= MAX_SESIONES_POR_USUARIO && !mias.contains(sesion.getId())) {
            cerrar(sesion, CloseStatus.POLICY_VIOLATION.withReason("Demasiadas conexiones abiertas"));
            return;
        }
        mias.add(sesion.getId());
        sesion.getAttributes().put(USUARIO, u);
        responder(sesion, Map.of("tipo", "listo", "usuario", u.id()));
    }

    private void seguir(WebSocketSession sesion, UsuarioAutenticado yo, long servicioId) throws IOException {
        Set<Long> mios = servicios(sesion);
        if (servicioId <= 0) {
            responder(sesion, Map.of("tipo", "error", "mensaje", "Falta el servicio"));
            return;
        }
        if (!mios.contains(servicioId) && mios.size() >= MAX_SERVICIOS_POR_SESION) {
            responder(sesion, Map.of("tipo", "error", "mensaje", "Sigues demasiados servicios a la vez"));
            return;
        }
        VigenciaTokens v = vigencia.getIfAvailable();
        if (v != null && !v.vigente(yo)) {
            cerrar(sesion, CloseStatus.POLICY_VIOLATION.withReason("Sesión revocada"));
            return;
        }
        if (!puedeSeguir(yo, servicioId)) {
            responder(sesion, Map.of("tipo", "error", "servicio", servicioId, "mensaje", "No puedes seguir este servicio"));
            return;
        }
        mios.add(servicioId);
        tiempoReal.seguir(servicioId, envuelta(sesion));
        responder(sesion, Map.of("tipo", "siguiendo", "servicio", servicioId));
    }

    /** Las mismas personas que pueden leer el servicio por HTTP cuando ya tiene conductor. */
    private boolean puedeSeguir(UsuarioAutenticado yo, long servicioId) {
        if (yo.tieneRol(Roles.ADMIN) || yo.tieneRol(Roles.SOPORTE)) {
            return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS (SELECT 1 FROM servicios WHERE id = ?)", Boolean.class,
                    servicioId));
        }
        return Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM servicios WHERE id = ? AND (cliente_id = ? OR conductor_id = ?))", Boolean.class,
                servicioId, yo.id(), yo.id()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession sesion, CloseStatus estado) {
        WebSocketSession e = envueltas.remove(sesion.getId());
        if (e != null) tiempoReal.olvidar(e);
        UsuarioAutenticado u = (UsuarioAutenticado) sesion.getAttributes().get(USUARIO);
        if (u != null) {
            sesionesPorUsuario.computeIfPresent(u.id(), (k, set) -> {
                set.remove(sesion.getId());
                return set.isEmpty() ? null : set;
            });
        }
    }

    @Override
    public void handleTransportError(WebSocketSession sesion, Throwable error) {
        log.debug("WebSocket {}: {}", sesion.getId(), error.getMessage());
    }

    @SuppressWarnings("unchecked")
    private static Set<Long> servicios(WebSocketSession s) {
        return (Set<Long>) s.getAttributes().computeIfAbsent(SERVICIOS, k -> ConcurrentHashMap.newKeySet());
    }

    private WebSocketSession envuelta(WebSocketSession s) {
        return envueltas.getOrDefault(s.getId(), s);
    }

    private void responder(WebSocketSession s, Map<String, ?> datos) throws IOException {
        envuelta(s).sendMessage(new TextMessage(json.writeValueAsString(datos)));
    }

    private void cerrar(WebSocketSession s, CloseStatus estado) {
        try {
            s.close(estado);
        } catch (IOException e) {
            // ya estaba cerrada
        }
    }
}
