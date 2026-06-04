package com.movify.backend.Controlador;

import com.movify.backend.Base_de_datos.ConductorRepositorio;
import com.movify.backend.Base_de_datos.UsuarioRepositorio;

import com.movify.backend.Modelo.Conductor;
import com.movify.backend.Modelo.Usuario;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.web.bind.annotation.*;

import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;

@RestController
@RequestMapping("/api/conductor")
@CrossOrigin("*")

public class ConductorControlador {

        @Autowired
        private ConductorRepositorio conductorRepositorio;

        @Autowired
        private UsuarioRepositorio usuarioRepositorio;

        @Autowired
        private JdbcTemplate db;

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
        // ACTUALIZAR PERFIL
        // =========================

        @PutMapping("/perfil/actualizar")
        @Transactional
        public ResponseEntity<?> actualizarPerfil(@RequestBody Map<String, Object> datos) {
                try {
                        // 1. Extracción segura de datos para evitar NullPointerException
                        String correo = datos.get("correo") != null ? datos.get("correo").toString() : null;
                        String nombre = datos.get("nombre") != null ? datos.get("nombre").toString() : null;
                        String telefono = datos.get("telefono") != null ? datos.get("telefono").toString() : null;
                        String ciudad = datos.get("ciudad") != null ? datos.get("ciudad").toString() : "Buenaventura";

                        if (correo == null) {
                                return ResponseEntity.badRequest().body(Map.of("error", "El correo es obligatorio"));
                        }

                        // 2. Buscar el usuario por correo
                        Usuario usuario = usuarioRepositorio.findByCorreo(correo)
                                        .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

                        // 3. Actualizar campos
                        if (nombre != null)
                                usuario.setNombre(nombre);
                        if (telefono != null)
                                usuario.setTelefono(telefono);

                        usuarioRepositorio.save(usuario);

                        // 4. Actualizar Ciudad en la tabla conductores
                        db.update("UPDATE conductores SET ciudad = ? WHERE usuario_id = ?", ciudad, usuario.getId());

                        return ResponseEntity.ok(Map.of("mensaje", "Perfil actualizado correctamente"));

                } catch (Exception e) {
                        e.printStackTrace(); // Esto te permitirá ver el error real en la consola de Java
                                             // (IntelliJ/Eclipse)
                        return ResponseEntity.badRequest()
                                        .body(Map.of("error", "Error al actualizar: " + e.getMessage()));
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

                        respuesta.put("conductor_id", conductor.getId());

                        // Ciudad con valor por defecto
                        String ciudad = db.queryForObject(
                                        "SELECT COALESCE(ciudad, 'Buenaventura') FROM conductores WHERE id = ?",
                                        String.class, conductor.getId());
                        respuesta.put("ciudad", ciudad);

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

                        // Calificación real desde la DB
                        Double calif = db.queryForObject(
                                        "SELECT COALESCE(AVG(calificacion), 0) FROM calificaciones WHERE servicio_id IN (SELECT id FROM servicios WHERE conductor_id = ?)",
                                        Double.class, conductor.getId());
                        respuesta.put("calificacion", Math.round(calif * 10.0) / 10.0);

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
                        respuesta.put("historial", new ArrayList<>());

                        // =========================
                        // SOLICITUDES
                        // =========================
                        respuesta.put("solicitudes", new ArrayList<>());

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