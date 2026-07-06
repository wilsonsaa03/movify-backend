package com.movify.backend.Controlador;

import com.movify.backend.Base_de_datos.ConductorRepositorio;
import com.movify.backend.Base_de_datos.UsuarioRepositorio;

import com.movify.backend.Modelo.Conductor;
import com.movify.backend.Modelo.Usuario;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;

import org.springframework.web.bind.annotation.*;

import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;

@RestController
@RequestMapping("/api/conductor")
public class ConductorControlador {

        @Autowired
        private ConductorRepositorio conductorRepositorio;

        @Autowired
        private UsuarioRepositorio usuarioRepositorio;

        @Autowired
        private JdbcTemplate db;

        private static final String DIRECTORIO_DOCUMENTOS = "/var/www/ds2/movify/uploads-conductores/";

        /**
         * Guarda un archivo subido en disco con un nombre único, y devuelve
         * el nombre generado para almacenarlo en la BD.
         */
        private String guardarArchivo(MultipartFile archivo, String prefijo) {
                if (archivo == null || archivo.isEmpty()) {
                        return null;
                }
                try {
                        Path directorio = Paths.get(DIRECTORIO_DOCUMENTOS);
                        if (!Files.exists(directorio)) {
                                Files.createDirectories(directorio);
                        }

                        String nombreOriginal = archivo.getOriginalFilename();
                        String extension = "";
                        if (nombreOriginal != null && nombreOriginal.contains(".")) {
                                extension = nombreOriginal.substring(nombreOriginal.lastIndexOf("."));
                        }

                        String nombreUnico = prefijo + "_" + UUID.randomUUID().toString() + extension;
                        Path rutaDestino = directorio.resolve(nombreUnico);
                        Files.copy(archivo.getInputStream(), rutaDestino, StandardCopyOption.REPLACE_EXISTING);

                        return nombreUnico;
                } catch (IOException e) {
                        System.err.println("⚠️ Error al guardar archivo físico: " + e.getMessage());
                        return archivo.getOriginalFilename(); // fallback: guardamos al menos el nombre
                }
        }

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

                        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

                        Usuario usuario = new Usuario();

                        usuario.setNombre(nombre);
                        usuario.setCorreo(correo);
                        usuario.setTelefono(telefono);
                        usuario.setPassword(encoder.encode(password));
                        usuario.setRol("conductor");
                        usuario.setEstado("activo");

                        usuarioRepositorio.save(usuario);

                        // =========================
                        // CREAR CONDUCTOR
                        // =========================

                        Conductor conductor = new Conductor();

                        conductor.setUsuario(usuario);

                        conductor.setPlaca(placa);
                        conductor.setModelo(modelo);

                        conductor.setLicencia(
                                        guardarArchivo(licencia, "licencia"));

                        conductor.setSoat(
                                        guardarArchivo(soat, "soat"));

                        conductor.setTarjetaPropiedad(
                                        guardarArchivo(tarjetaPropiedad, "tarjeta"));

                        conductor.setCedula(
                                        guardarArchivo(cedula, "cedula"));

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
                        String correo = (datos.get("correo") != null) ? datos.get("correo").toString() : null;
                        String nombre = (datos.get("nombre") != null) ? datos.get("nombre").toString() : null;
                        String telefono = (datos.get("telefono") != null) ? datos.get("telefono").toString() : null;
                        String ciudad = (datos.get("ciudad") != null
                                        && !datos.get("ciudad").toString().trim().isEmpty())
                                                        ? datos.get("ciudad").toString()
                                                        : "Buenaventura";
                        String foto = (datos.get("foto") != null) ? datos.get("foto").toString() : null;

                        if (correo == null || correo.trim().isEmpty()) {
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
                        if (foto != null)
                                usuario.setFoto(foto);

                        usuarioRepositorio.save(usuario);

                        // 4. Buscar el conductor para obtener su ID real
                        Conductor conductor = conductorRepositorio.findByUsuarioId(usuario.getId()).orElse(null);

                        if (conductor == null) {
                                return ResponseEntity.status(404)
                                                .body(Map.of("error", "Perfil de conductor no encontrado."));
                        }

                        // 5. Actualizar Ciudad (con manejo de errores robusto)
                        if (conductor != null) {
                                try {
                                        db.update("UPDATE conductores SET ciudad = ? WHERE id = ?", ciudad,
                                                        conductor.getId());
                                } catch (Exception ex) {
                                        System.err.println("⚠️ SQL Error al actualizar ciudad: " + ex.getMessage());
                                        // No lanzamos excepción para permitir que los datos de 'Usuario' sí se guarden
                                }
                        }

                        return ResponseEntity.ok(Map.of("mensaje", "Perfil actualizado correctamente"));

                } catch (Exception e) {
                        e.printStackTrace();
                        String details = (e.getMessage() != null) ? e.getMessage() : e.toString();
                        return ResponseEntity.status(500)
                                        .body(Map.of("error", "Error al actualizar perfil", "details", details));
                }
        }

        // =========================
        // OBTENER PERFIL
        // =========================

        @GetMapping("/perfil/{correo:.+}")
        public ResponseEntity<?> obtenerPerfil(
                        @PathVariable(value = "correo") String correo) {

                try {
                        Usuario usuario = usuarioRepositorio
                                        .findByCorreo(correo)
                                        .orElse(null);

                        if (usuario == null) {
                                return ResponseEntity
                                                .status(404)
                                                .body(Map.of(
                                                                "error",
                                                                "Usuario no encontrado"));
                        }

                        Conductor conductor = conductorRepositorio
                                        .findByUsuarioId(usuario.getId())
                                        .orElse(null);

                        if (conductor == null) {
                                return ResponseEntity
                                                .status(404)
                                                .body(Map.of(
                                                                "error",
                                                                "Perfil de conductor no encontrado para este usuario"));
                        }

                        Map<String, Object> respuesta = new HashMap<>();

                        respuesta.put("conductor_id", conductor.getId());

                        // Obtener Ciudad con manejo de error si la columna no existe
                        String ciudadDefault = "Buenaventura";
                        try {
                                String ciudadDb = db.queryForObject(
                                                "SELECT COALESCE(ciudad, 'Buenaventura') FROM conductores WHERE id = ?",
                                                String.class, conductor.getId());
                                respuesta.put("ciudad", ciudadDb);
                        } catch (Exception ex) {
                                respuesta.put("ciudad", ciudadDefault);
                        }

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

                        // Calculamos las estadísticas reales directamente de la tabla servicios
                        // Esto garantiza que los datos pertenecen 100% al conductor logueado.
                        Map<String, Object> statsDb = db.queryForMap(
                                        """
                                                        SELECT
                                                            COUNT(*) FILTER (WHERE estado = 'FINALIZADO') as total,
                                                            COUNT(*) FILTER (WHERE estado = 'FINALIZADO' AND fecha_solicitud::date = CURRENT_DATE) as hoy,
                                                            COALESCE(SUM(tarifa) FILTER (WHERE estado = 'FINALIZADO' AND fecha_solicitud::date = CURRENT_DATE), 0) as g_hoy,
                                                            COALESCE(SUM(tarifa) FILTER (WHERE estado = 'FINALIZADO' AND fecha_solicitud >= date_trunc('week', now())), 0) as g_semana
                                                        FROM servicios
                                                        WHERE conductor_id = ?
                                                        """,
                                        conductor.getId());

                        respuesta.put("gananciasHoy", statsDb.get("g_hoy"));
                        respuesta.put("gananciasSemana", statsDb.get("g_semana"));
                        respuesta.put("viajesHoy", statsDb.get("hoy"));
                        respuesta.put("viajesTotal", statsDb.get("total"));

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

        // =========================
        // SERVIR DOCUMENTO (ADMIN/CONDUCTOR)
        // =========================

        @GetMapping("/documento/{nombreArchivo:.+}")
        public ResponseEntity<Resource> obtenerDocumento(@PathVariable String nombreArchivo) {
                try {
                        Path ruta = Paths.get(DIRECTORIO_DOCUMENTOS).resolve(nombreArchivo).normalize();
                        Resource recurso = new UrlResource(ruta.toUri());

                        if (!recurso.exists() || !recurso.isReadable()) {
                                return ResponseEntity.status(404).build();
                        }

                        String contentType = Files.probeContentType(ruta);
                        if (contentType == null) {
                                contentType = "application/octet-stream";
                        }

                        return ResponseEntity.ok()
                                        .contentType(MediaType.parseMediaType(contentType))
                                        .body(recurso);

                } catch (MalformedURLException | IOException e) {
                        return ResponseEntity.status(400).build();
                }
        }
}