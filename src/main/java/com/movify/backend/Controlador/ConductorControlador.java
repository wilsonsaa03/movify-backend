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

        // ==========================================
        // NUEVO: ACTUALIZAR UBICACIÓN Y ESTADO
        // ==========================================

        @PutMapping("/{correo}/ubicacion")
        public ResponseEntity<?> actualizarUbicacion(
                @PathVariable String correo, 
                @RequestBody Map<String, Object> datos) {
            
            try {
                Usuario usuario = usuarioRepositorio.findByCorreo(correo)
                        .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

                Conductor conductor = conductorRepositorio.findByUsuarioId(usuario.getId())
                        .orElseThrow(() -> new RuntimeException("Conductor no encontrado"));

                if (datos.get("lat") != null) {
                    conductor.setLatitud(((Number) datos.get("lat")).doubleValue());
                }
                if (datos.get("lon") != null) {
                    conductor.setLongitud(((Number) datos.get("lon")).doubleValue());
                }
                if (datos.get("activo") != null) {
                    conductor.setEnLinea((Boolean) datos.get("activo"));
                }

                conductorRepositorio.save(conductor);
                return ResponseEntity.ok().build();

            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            }
        }

        // =========================
        // REGISTRAR CONDUCTOR
        // =========================

        @PostMapping("/registro")
        public ResponseEntity<?> registrarConductor(

                        @RequestParam String nombre,
                        @RequestParam String correo,
                        @RequestParam String telefono,
                        @RequestParam String password,

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

                        // =========================
                        // CREAR USUARIO
                        // =========================

                        Usuario usuario = new Usuario();

                        usuario.setNombre(nombre);
                        usuario.setCorreo(correo);
                        usuario.setTelefono(telefono);
                        usuario.setPassword(password);
                        usuario.setRol("conductor");

                        usuarioRepositorio.save(usuario);

                        // =========================
                        // CREAR CONDUCTOR
                        // =========================

                        Conductor conductor = new Conductor();

                        conductor.setUsuario(usuario);

                        conductor.setPlaca(placa);
                        conductor.setModelo(modelo);

                        conductor.setLicencia(
                                        licencia.getOriginalFilename());

                        conductor.setSoat(
                                        soat.getOriginalFilename());

                        conductor.setTarjetaPropiedad(
                                        tarjetaPropiedad.getOriginalFilename());

                        conductor.setCedula(
                                        cedula.getOriginalFilename());

                        conductor.setEstado("pendiente");

                        // =========================
                        // ESTADISTICAS INICIALES
                        // =========================

                        conductor.setGananciasHoy(0.0);

                        conductor.setGananciasSemana(0.0);

                        conductor.setViajesHoy(0);

                        conductor.setViajesTotal(0);

                        conductorRepositorio.save(conductor);

                        // =========================
                        // RESPUESTA
                        // =========================

                        Map<String, Object> respuesta = new HashMap<>();

                        respuesta.put(
                                        "mensaje",
                                        "Conductor registrado correctamente");

                        respuesta.put(
                                        "nombre",
                                        usuario.getNombre());

                        respuesta.put(
                                        "correo",
                                        usuario.getCorreo());

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

        // =========================
        // OBTENER PERFIL
        // =========================

        @GetMapping("/perfil/{correo}")
        public ResponseEntity<?> obtenerPerfil(
                        @PathVariable String correo) {

                try {

                        Usuario usuario = usuarioRepositorio
                                        .findByCorreo(correo)
                                        .orElse(null);

                        if (usuario == null) {

                                return ResponseEntity
                                                .badRequest()
                                                .body(Map.of(
                                                                "error",
                                                                "Usuario no encontrado"));
                        }

                        Conductor conductor = conductorRepositorio
                                        .findByUsuarioId(usuario.getId())
                                        .orElse(null);

                        if (conductor == null) {

                                return ResponseEntity
                                                .badRequest()
                                                .body(Map.of(
                                                                "error",
                                                                "Conductor no encontrado"));
                        }

                        Map<String, Object> respuesta = new HashMap<>();

                        // =========================
                        // DATOS PERSONALES
                        // =========================

                        respuesta.put(
                                        "nombre",
                                        usuario.getNombre());

                        respuesta.put(
                                        "correo",
                                        usuario.getCorreo());

                        respuesta.put(
                                        "telefono",
                                        usuario.getTelefono());

                        respuesta.put(
                                        "foto",
                                        usuario.getFoto());

                        // =========================
                        // VEHICULO
                        // =========================

                        respuesta.put(
                                        "placa",
                                        conductor.getPlaca());

                        respuesta.put(
                                        "modelo",
                                        conductor.getModelo());

                        // =========================
                        // ESTADO
                        // =========================

                        respuesta.put(
                                        "estado",
                                        conductor.getEstado());

                        // =========================
                        // ESTADISTICAS
                        // =========================

                        respuesta.put(
                                        "gananciasHoy",
                                        conductor.getGananciasHoy());

                        respuesta.put(
                                        "gananciasSemana",
                                        conductor.getGananciasSemana());

                        respuesta.put(
                                        "viajesHoy",
                                        conductor.getViajesHoy());

                        respuesta.put(
                                        "viajesTotal",
                                        conductor.getViajesTotal());

                        // =========================
                        // HISTORIAL
                        // =========================

                        respuesta.put(
                                        "historial",
                                        new Object[] {

                                                        Map.of(
                                                                        "destino",
                                                                        "Centro Comercial Chipichape",

                                                                        "fecha",
                                                                        "20 may, 9:30 AM",

                                                                        "precio",
                                                                        7800),

                                                        Map.of(
                                                                        "destino",
                                                                        "Restaurante La Hacienda",

                                                                        "fecha",
                                                                        "20 may, 11:15 AM",

                                                                        "precio",
                                                                        5500),

                                                        Map.of(
                                                                        "destino",
                                                                        "Clínica Valle del Lili",

                                                                        "fecha",
                                                                        "19 may, 3:20 PM",

                                                                        "precio",
                                                                        8200)

                                        });

                        // =========================
                        // SOLICITUDES
                        // =========================

                        respuesta.put(
                                        "solicitudes",
                                        new Object[] {

                                                        Map.of(
                                                                        "id",
                                                                        1,

                                                                        "tipo",
                                                                        "Transporte",

                                                                        "destino",
                                                                        "Centro Comercial Unicentro",

                                                                        "precio",
                                                                        8500),

                                                        Map.of(
                                                                        "id",
                                                                        2,

                                                                        "tipo",
                                                                        "Domicilio",

                                                                        "destino",
                                                                        "Restaurante El Punto",

                                                                        "precio",
                                                                        6200),

                                                        Map.of(
                                                                        "id",
                                                                        3,

                                                                        "tipo",
                                                                        "Encomienda",

                                                                        "destino",
                                                                        "Universidad del Valle",

                                                                        "precio",
                                                                        9000)

                                        });

                        return ResponseEntity.ok(respuesta);

                } catch (Exception e) {

                        e.printStackTrace();

                        return ResponseEntity
                                        .badRequest()
                                        .body(Map.of(
                                                                "error",
                                                                e.getMessage()));
                }
        }
}