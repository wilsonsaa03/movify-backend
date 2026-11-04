package com.movify.backend.notificaciones;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movify.backend.comun.DespuesDelCommit;
import com.movify.backend.comun.Datos;

import jakarta.annotation.PreDestroy;

/**
 * Avisos al celular: "Carlos va por ti", "Tu moto llegó", "Nueva solicitud"…
 * Cada aviso se guarda en la tabla notificaciones (historial de la persona) y, cuando la transacción
 * queda confirmada, se manda como push a sus dispositivos activos sin frenar la respuesta de la API.
 */
@Service
public class NotificacionServicio {

    /** Tipos permitidos por la tabla notificaciones (CHECK de la BD). */
    public static final String ESTADO = "estado_servicio";
    public static final String SOLICITUD = "nueva_solicitud";
    public static final String MENSAJE = "mensaje";
    public static final String SOPORTE = "soporte";

    private static final Logger log = LoggerFactory.getLogger(NotificacionServicio.class);

    private final JdbcTemplate db;
    private final PushEnviador push;
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService envios = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "push");
        t.setDaemon(true);
        return t;
    });

    public NotificacionServicio(JdbcTemplate db, PushEnviador push) {
        this.db = db;
        this.push = push;
    }

    @PreDestroy
    void cerrar() {
        envios.shutdown();
    }

    /** Un aviso para una persona sobre un servicio. `evento` le dice a la app qué pantalla abrir. */
    public void avisar(Long usuarioId, Long servicioId, String tipo, String evento, String titulo, String cuerpo) {
        if (usuarioId == null) return;
        avisarVarios(List.of(usuarioId), servicioId, tipo, evento, titulo, cuerpo);
    }

    public void avisarVarios(Collection<Long> usuarios, Long servicioId, String tipo, String evento, String titulo,
            String cuerpo) {
        if (usuarios.isEmpty()) return;
        Map<String, String> datos = new HashMap<>();
        datos.put("evento", evento);
        if (servicioId != null) datos.put("servicio_id", String.valueOf(servicioId));
        String datosJson = aJson(datos);
        String t = Datos.recortar(titulo, 100);
        String c = Datos.recortar(cuerpo, 300);
        for (Long u : usuarios) {
            Long id = db.queryForObject("""
                    INSERT INTO notificaciones (usuario_id, servicio_id, tipo, titulo, cuerpo, datos)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb) RETURNING id
                    """, Long.class, u, servicioId, tipo, t, c, datosJson);
            DespuesDelCommit.ejecutar(() -> envios.execute(() -> mandar(id, u, t, c, datos)));
        }
    }

    /**
     * Comunicado masivo del admin: una fila de notificaciones (tipo 'sistema') por cada cuenta activa de la
     * audiencia en un solo INSERT, y los push en segundo plano cuando la transacción queda confirmada.
     * @param audiencia todos | clientes | conductores
     * @return a cuántas personas les llegó
     */
    public int difundir(String audiencia, String titulo, String cuerpo, Long comunicadoId) {
        Map<String, String> datos = new HashMap<>();
        datos.put("evento", "comunicado");
        datos.put("comunicado_id", String.valueOf(comunicadoId));
        String t = Datos.recortar(titulo, 100);
        String c = Datos.recortar(cuerpo, 300);
        List<Map<String, Object>> filas = db.queryForList("""
                INSERT INTO notificaciones (usuario_id, tipo, titulo, cuerpo, datos)
                SELECT u.id, 'sistema', ?, ?, ?::jsonb FROM usuarios u
                WHERE u.estado = 'activo' AND EXISTS (
                    SELECT 1 FROM usuario_roles r WHERE r.usuario_id = u.id AND r.rol = ANY (?::text[]))
                RETURNING id, usuario_id
                """, t, c, aJson(datos), "{" + String.join(",", rolesDe(audiencia)) + "}");
        DespuesDelCommit.ejecutar(() -> envios.execute(() -> filas.forEach(f ->
                mandar(Datos.aLong(f.get("id")), Datos.aLong(f.get("usuario_id")), t, c, datos))));
        return filas.size();
    }

    /** Cuántas cuentas activas recibirían un comunicado (para que el admin lo vea antes de enviar). */
    public int alcance(String audiencia) {
        Integer n = db.queryForObject("""
                SELECT count(*) FROM usuarios u
                WHERE u.estado = 'activo' AND EXISTS (
                    SELECT 1 FROM usuario_roles r WHERE r.usuario_id = u.id AND r.rol = ANY (?::text[]))
                """, Integer.class, "{" + String.join(",", rolesDe(audiencia)) + "}");
        return n == null ? 0 : n;
    }

    private static List<String> rolesDe(String audiencia) {
        return switch (audiencia) {
            case "clientes" -> List.of("cliente");
            case "conductores" -> List.of("conductor");
            default -> List.of("cliente", "conductor");
        };
    }

    private void mandar(Long notificacionId, Long usuarioId, String titulo, String cuerpo, Map<String, String> datos) {
        try {
            List<String> tokens = db.queryForList(
                    "SELECT token_push FROM dispositivos WHERE usuario_id = ? AND activo AND plataforma IN ('android', 'ios')",
                    String.class, usuarioId);
            boolean alguno = false;
            for (String token : tokens) {
                if (push.enviar(token, titulo, cuerpo, datos)) {
                    alguno = true;
                } else {
                    db.update("UPDATE dispositivos SET activo = false WHERE token_push = ?", token);
                }
            }
            if (alguno) db.update("UPDATE notificaciones SET enviada_push = true WHERE id = ?", notificacionId);
        } catch (RuntimeException e) {
            log.warn("No se pudo mandar la notificación {}: {}", notificacionId, e.getMessage());
        }
    }

    private String aJson(Map<String, String> datos) {
        try {
            return json.writeValueAsString(datos);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
