package com.movify.backend.notificaciones;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.seguridad.SesionActual;

/**
 * Las notificaciones de quien está en sesión (lo mismo que le llegó por push, guardado en la tabla notificaciones):
 *
 *   GET  /api/notificaciones?pagina=0&tamano=30   las más recientes primero
 *   GET  /api/notificaciones/no-leidas            cuántas faltan por leer (el número de la campana)
 *   POST /api/notificaciones/{id}/leida           marcar una
 *   POST /api/notificaciones/leer-todas           marcar todas
 *
 * Cada quien solo ve y marca las suyas (el usuario sale del token, nunca de la URL).
 */
@RestController
@RequestMapping("/api/notificaciones")
public class NotificacionControlador {

    private final JdbcTemplate db;
    private final SesionActual sesion;

    public NotificacionControlador(JdbcTemplate db, SesionActual sesion) {
        this.db = db;
        this.sesion = sesion;
    }

    @GetMapping
    public Map<String, Object> mias(@RequestParam(defaultValue = "0") int pagina, @RequestParam(defaultValue = "30") int tamano) {
        int t = Math.min(Math.max(tamano, 5), 50);
        int p = Math.max(pagina, 0);
        Long yo = sesion.usuarioId();
        List<Map<String, Object>> items = db.queryForList("""
                SELECT n.id, n.tipo, n.titulo, n.cuerpo, n.servicio_id, n.datos->>'evento' AS evento,
                       n.leida_en, n.creado_en, s.tipo_servicio, s.tipo_vehiculo
                FROM notificaciones n LEFT JOIN servicios s ON s.id = n.servicio_id
                WHERE n.usuario_id = ?
                ORDER BY n.creado_en DESC, n.id DESC
                LIMIT ? OFFSET ?
                """, yo, t + 1, p * t);
        boolean hayMas = items.size() > t;
        return Map.of("items", hayMas ? items.subList(0, t) : items, "hay_mas", hayMas, "no_leidas", noLeidas(yo));
    }

    @GetMapping("/no-leidas")
    public Map<String, Object> contar() {
        return Map.of("no_leidas", noLeidas(sesion.usuarioId()));
    }

    @PostMapping("/{id}/leida")
    public Map<String, Object> leida(@PathVariable Long id) {
        Long yo = sesion.usuarioId();
        int n = db.update("UPDATE notificaciones SET leida_en = COALESCE(leida_en, now()) WHERE id = ? AND usuario_id = ?", id, yo);
        if (n == 0) throw new RecursoNoEncontradoException("Notificación no encontrada");
        return Map.of("no_leidas", noLeidas(yo));
    }

    @PostMapping("/leer-todas")
    public Map<String, Object> leerTodas() {
        Long yo = sesion.usuarioId();
        int n = db.update("UPDATE notificaciones SET leida_en = now() WHERE usuario_id = ? AND leida_en IS NULL", yo);
        return Map.of("marcadas", n, "no_leidas", 0);
    }

    private long noLeidas(Long usuarioId) {
        Long n = db.queryForObject("SELECT count(*) FROM notificaciones WHERE usuario_id = ? AND leida_en IS NULL", Long.class, usuarioId);
        return n == null ? 0 : n;
    }
}
