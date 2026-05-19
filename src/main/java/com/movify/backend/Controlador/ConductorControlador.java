package com.movify.backend.Controlador;

import com.movify.backend.Base_de_datos.ConductorRepositorio;
import com.movify.backend.Base_de_datos.UsuarioRepositorio;

import com.movify.backend.Modelo.Conductor;
import com.movify.backend.Modelo.Usuario;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/conductor")
@CrossOrigin("*")
public class ConductorControlador {

        @Autowired
        private ConductorRepositorio conductorRepositorio;

        @Autowired
        private UsuarioRepositorio usuarioRepositorio;

        // =========================
        // REGISTRAR CONDUCTOR
        // =========================

        @PostMapping("/registro")
        public ResponseEntity<?> registrarConductor(

                        @RequestParam String nombre,
                        @RequestParam String correo,
                        @RequestParam String telefono,
                        @RequestParam String password,

                        // ← FALTABAN ESTOS
                        @RequestParam String placa,
                        @RequestParam String modelo,

                        @RequestParam MultipartFile licencia,
                        @RequestParam MultipartFile soat,
                        @RequestParam MultipartFile tarjetaPropiedad,
                        @RequestParam MultipartFile cedula

        ) {

                try {

                        // VALIDAR CORREO

                        if (usuarioRepositorio
                                        .findByCorreo(correo)
                                        .isPresent()) {

                                return ResponseEntity
                                                .badRequest()
                                                .body(Map.of(
                                                                "error",
                                                                "El correo ya está registrado"));
                        }

                        // CREAR USUARIO

                        Usuario usuario = new Usuario();

                        usuario.setNombre(nombre);
                        usuario.setCorreo(correo);
                        usuario.setTelefono(telefono);
                        usuario.setPassword(password);
                        usuario.setRol("conductor");

                        usuarioRepositorio.save(usuario);

                        // CREAR CONDUCTOR

                        Conductor conductor = new Conductor();

                        conductor.setUsuario(usuario);

                        conductor.setLicencia(
                                        licencia.getOriginalFilename());

                        conductor.setSoat(
                                        soat.getOriginalFilename());

                        conductor.setEstado("pendiente");

                        conductorRepositorio.save(conductor);

                        // RESPUESTA JSON

                        Map<String, Object> respuesta = new HashMap<>();

                        respuesta.put(
                                        "mensaje",
                                        "Conductor registrado correctamente");

                        respuesta.put(
                                        "nombre",
                                        usuario.getNombre());

                        respuesta.put(
                                        "rol",
                                        usuario.getRol());

                        respuesta.put(
                                        "token",
                                        "registro-exitoso");

                        return ResponseEntity.ok(respuesta);

                } catch (Exception e) {

                        e.printStackTrace();

                        return ResponseEntity
                                        .badRequest()
                                        .body(Map.of(
                                                        "error",
                                                        "Error: " + e.getMessage()));
                }
        }
}