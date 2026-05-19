package com.movify.backend.Controlador;

import com.movify.backend.Base_de_datos.ServicioRepositorio;
import com.movify.backend.Modelo.Servicio;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/servicios")
@CrossOrigin("*")
public class ServicioControlador {

    @Autowired
    private ServicioRepositorio servicioRepositorio;

    // =========================
    // HISTORIAL DEL USUARIO
    // =========================

    @GetMapping("/usuario/{usuarioId}")

    public List<Servicio> obtenerServiciosUsuario(
            @PathVariable Long usuarioId) {

        return servicioRepositorio
                .findByUsuarioId(usuarioId);

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