package com.movify.backend.Controlador;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.movify.backend.Seguridad.SesionActual;

/** Historiales simples. Cada consulta valida que el id de la URL sea el del token. */
@RestController
@RequestMapping("/api/servicios")
public class ServicioControlador {

    private final JdbcTemplate db;
    private final SesionActual sesion;

    public ServicioControlador(JdbcTemplate db, SesionActual sesion) {
        this.db = db;
        this.sesion = sesion;
    }

    @GetMapping("/usuario/{usuarioId}")
    public List<Map<String, Object>> serviciosUsuario(@PathVariable Long usuarioId) {
        sesion.exigirUsuario(usuarioId);
        return db.queryForList("SELECT * FROM servicios WHERE usuario_id = ? ORDER BY fecha_solicitud DESC",
                usuarioId);
    }

    @GetMapping("/usuario/{usuarioId}/mes-actual")
    public List<Map<String, Object>> serviciosUsuarioMes(@PathVariable Long usuarioId) {
        sesion.exigirUsuario(usuarioId);
        return db.queryForList("""
                SELECT * FROM servicios
                WHERE usuario_id = ? AND fecha_solicitud >= DATE_TRUNC('month', CURRENT_DATE)
                ORDER BY fecha_solicitud DESC
                """, usuarioId);
    }

    @GetMapping("/conductor/{conductorId}")
    public List<Map<String, Object>> serviciosConductor(@PathVariable Long conductorId) {
        sesion.exigirConductor(conductorId);
        return db.queryForList("SELECT * FROM servicios WHERE conductor_id = ? ORDER BY fecha_solicitud DESC",
                conductorId);
    }
}
