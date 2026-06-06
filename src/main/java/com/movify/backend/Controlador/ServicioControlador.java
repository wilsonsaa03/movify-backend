package com.movify.backend.Controlador;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.movify.backend.Base_de_datos.ServicioRepositorio;
import com.movify.backend.Modelo.Servicio;

@RestController
@RequestMapping("/api/servicios")
public class ServicioControlador {

    @Autowired
    private ServicioRepositorio servicioRepositorio;

    @Autowired
    private JdbcTemplate db;

    // =========================
    // HISTORIAL DEL USUARIO
    // =========================

    @GetMapping("/usuario/{usuarioId}")

    public List<Servicio> obtenerServiciosUsuario(
            @PathVariable Long usuarioId) {

        return servicioRepositorio
                .findByUsuarioId(usuarioId);

    }

    // ==========================================
    // NUEVO: VIAJES DEL MES ACTUAL (USUARIO)
    // ==========================================
    @GetMapping("/usuario/{usuarioId}/mes-actual")
    public List<Servicio> obtenerServiciosUsuarioMes(@PathVariable Long usuarioId) {
        String sql = """
            SELECT * FROM servicios 
            WHERE usuario_id = ? 
            AND EXTRACT(MONTH FROM fecha_solicitud) = EXTRACT(MONTH FROM CURRENT_DATE)
            AND EXTRACT(YEAR FROM fecha_solicitud) = EXTRACT(YEAR FROM CURRENT_DATE)
            ORDER BY fecha_solicitud DESC
        """;
        // Nota: Si usas ServicioRepositorio con @Query también funcionaría, 
        // pero aquí usamos JdbcTemplate por consistencia con tus otros controladores.
        return db.query(sql, new org.springframework.jdbc.core.BeanPropertyRowMapper<>(Servicio.class), usuarioId);
    }

    // =========================
    // HISTORIAL DEL CONDUCTOR
    // =========================

    @GetMapping("/conductor/{conductorId}")

    public List<Servicio> obtenerServiciosConductor(
            @PathVariable Long conductorId) {

        return servicioRepositorio
                .findByConductorId(conductorId);

    }

}