package com.movify.backend.Controlador;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.Resource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.movify.backend.Base_de_datos.ConductorRepositorio;
import com.movify.backend.Base_de_datos.UsuarioRepositorio;
import com.movify.backend.Comun.AccesoDenegadoException;
import com.movify.backend.Comun.RecursoNoEncontradoException;
import com.movify.backend.Comun.ReglaNegocioException;
import com.movify.backend.Comun.Roles;
import com.movify.backend.Modelo.Conductor;
import com.movify.backend.Modelo.Usuario;
import com.movify.backend.Seguridad.SesionActual;
import com.movify.backend.Servicio.DocumentoServicio;

@RestController
@RequestMapping("/api/conductor")
public class ConductorControlador {

    private final ConductorRepositorio conductores;
    private final UsuarioRepositorio usuarios;
    private final JdbcTemplate db;
    private final PasswordEncoder encoder;
    private final DocumentoServicio documentos;
    private final SesionActual sesion;

    public ConductorControlador(ConductorRepositorio conductores, UsuarioRepositorio usuarios, JdbcTemplate db,
            PasswordEncoder encoder, DocumentoServicio documentos, SesionActual sesion) {
        this.conductores = conductores;
        this.usuarios = usuarios;
        this.db = db;
        this.encoder = encoder;
        this.documentos = documentos;
        this.sesion = sesion;
    }

    /** Ubicación y disponibilidad del conductor autenticado. El correo de la URL debe ser el suyo. */
    @PutMapping("/{correo}/ubicacion")
    public ResponseEntity<Void> actualizarUbicacion(@PathVariable String correo, @RequestBody Map<String, Object> datos) {
        if (!correo.equalsIgnoreCase(sesion.usuario().correo())) {
            throw new AccesoDenegadoException("Solo puedes actualizar tu propia ubicación");
        }
        Conductor conductor = miConductor();
        if (datos.get("lat") instanceof Number lat) conductor.setLatitud(lat.doubleValue());
        if (datos.get("lon") instanceof Number lon) conductor.setLongitud(lon.doubleValue());
        if (datos.get("activo") instanceof Boolean activo) {
            if (activo && !"aprobado".equalsIgnoreCase(conductor.getEstado())) {
                throw new AccesoDenegadoException("Tu cuenta de conductor aún no está aprobada");
            }
            conductor.setEnLinea(activo);
        }
        conductores.save(conductor);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/registro")
    @Transactional
    public Map<String, Object> registrarConductor(
            @RequestParam String nombre,
            @RequestParam String correo,
            @RequestParam String telefono,
            @RequestParam String password,
            @RequestParam String placa,
            @RequestParam String modelo,
            @RequestParam MultipartFile licencia,
            @RequestParam MultipartFile soat,
            @RequestParam MultipartFile tarjetaPropiedad,
            @RequestParam MultipartFile cedula) {

        String correoNorm = correo.trim().toLowerCase();
        if (nombre.isBlank() || placa.isBlank() || modelo.isBlank() || !correoNorm.contains("@")) {
            throw new ReglaNegocioException(400, "Revisa los datos del formulario");
        }
        if (password.length() < 8 || password.length() > 72) {
            throw new ReglaNegocioException(400, "La contraseña debe tener entre 8 y 72 caracteres");
        }
        if (usuarios.findByCorreo(correoNorm).isPresent()) {
            throw new ReglaNegocioException("El correo ya está registrado");
        }

        // Primero los archivos: si alguno no es PDF/JPG/PNG no se crea nada
        String docLicencia = documentos.guardar(licencia, "licencia");
        String docSoat = documentos.guardar(soat, "soat");
        String docTarjeta = documentos.guardar(tarjetaPropiedad, "tarjeta");
        String docCedula = documentos.guardar(cedula, "cedula");

        Usuario usuario = new Usuario();
        usuario.setNombre(nombre.trim());
        usuario.setCorreo(correoNorm);
        usuario.setTelefono(telefono.trim());
        usuario.setPassword(encoder.encode(password));
        usuario.setRol(Roles.CONDUCTOR);
        usuario.setEstado("activo");
        usuarios.save(usuario);

        Conductor conductor = new Conductor();
        conductor.setUsuario(usuario);
        conductor.setPlaca(placa.trim().toUpperCase());
        conductor.setModelo(modelo.trim());
        conductor.setLicencia(docLicencia);
        conductor.setSoat(docSoat);
        conductor.setTarjetaPropiedad(docTarjeta);
        conductor.setCedula(docCedula);
        conductor.setEstado("pendiente");
        conductor.setEnLinea(false);
        conductor.setGananciasHoy(0.0);
        conductor.setGananciasSemana(0.0);
        conductor.setViajesHoy(0);
        conductor.setViajesTotal(0);
        conductores.save(conductor);

        Map<String, Object> r = new HashMap<>();
        r.put("mensaje", "Conductor registrado correctamente. Tu cuenta queda en revisión.");
        r.put("nombre", usuario.getNombre());
        r.put("correo", usuario.getCorreo());
        r.put("rol", usuario.getRol());
        return r;
    }

    /** Edita el perfil del conductor autenticado (el correo del body ya no decide a quién se edita). */
    @PutMapping("/perfil/actualizar")
    @Transactional
    public Map<String, Object> actualizarPerfil(@RequestBody Map<String, Object> datos) {
        Usuario usuario = usuarios.findById(sesion.usuarioId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario no encontrado"));
        Conductor conductor = miConductor();

        String nombre = texto(datos.get("nombre"), 120);
        String telefono = texto(datos.get("telefono"), 16);
        String foto = texto(datos.get("foto"), 500);
        String ciudad = texto(datos.get("ciudad"), 80);

        if (nombre != null && !nombre.isBlank()) usuario.setNombre(nombre);
        if (telefono != null) usuario.setTelefono(telefono);
        if (foto != null) usuario.setFoto(foto);
        usuarios.save(usuario);

        db.update("UPDATE conductores SET ciudad = ? WHERE id = ?",
                ciudad == null || ciudad.isBlank() ? "Buenaventura" : ciudad, conductor.getId());
        return Map.of("mensaje", "Perfil actualizado correctamente");
    }

    @GetMapping("/perfil/{correo:.+}")
    public Map<String, Object> obtenerPerfil(@PathVariable String correo) {
        Usuario usuario = usuarios.findByCorreo(correo.trim().toLowerCase())
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario no encontrado"));
        if (!usuario.getId().equals(sesion.usuarioId()) && !sesion.esPersonal()) {
            throw new AccesoDenegadoException("No puedes ver el perfil de otro conductor");
        }
        Conductor conductor = conductores.findByUsuarioId(usuario.getId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Perfil de conductor no encontrado para este usuario"));

        Map<String, Object> r = new HashMap<>();
        r.put("conductor_id", conductor.getId());
        r.put("ciudad", db.queryForObject("SELECT COALESCE(ciudad, 'Buenaventura') FROM conductores WHERE id = ?",
                String.class, conductor.getId()));
        r.put("nombre", usuario.getNombre());
        r.put("correo", usuario.getCorreo());
        r.put("telefono", usuario.getTelefono());
        r.put("foto", usuario.getFoto());
        r.put("placa", conductor.getPlaca());
        r.put("modelo", conductor.getModelo());
        r.put("estado", conductor.getEstado());

        Double calif = db.queryForObject("""
                SELECT COALESCE(AVG(calificacion), 0) FROM calificaciones
                WHERE servicio_id IN (SELECT id FROM servicios WHERE conductor_id = ?)
                """, Double.class, conductor.getId());
        r.put("calificacion", calif == null ? 0 : Math.round(calif * 10.0) / 10.0);

        Map<String, Object> stats = db.queryForMap("""
                SELECT COUNT(*) FILTER (WHERE estado = 'FINALIZADO') AS total,
                       COUNT(*) FILTER (WHERE estado = 'FINALIZADO' AND fecha_solicitud::date = CURRENT_DATE) AS hoy,
                       COALESCE(SUM(tarifa) FILTER (WHERE estado = 'FINALIZADO' AND fecha_solicitud::date = CURRENT_DATE), 0) AS g_hoy,
                       COALESCE(SUM(tarifa) FILTER (WHERE estado = 'FINALIZADO' AND fecha_solicitud >= date_trunc('week', now())), 0) AS g_semana
                FROM servicios WHERE conductor_id = ?
                """, conductor.getId());
        r.put("gananciasHoy", stats.get("g_hoy"));
        r.put("gananciasSemana", stats.get("g_semana"));
        r.put("viajesHoy", stats.get("hoy"));
        r.put("viajesTotal", stats.get("total"));
        r.put("historial", new ArrayList<>());
        r.put("solicitudes", new ArrayList<>());
        return r;
    }

    /**
     * Documentos de los conductores: solo admin/soporte (SecurityConfig).
     * Se entregan como descarga y con nosniff para que el navegador nunca los ejecute.
     */
    @GetMapping("/documento/{nombreArchivo:.+}")
    public ResponseEntity<Resource> obtenerDocumento(@PathVariable String nombreArchivo) {
        DocumentoServicio.Documento doc = documentos.obtener(nombreArchivo);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(doc.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(doc.nombre()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(doc.recurso());
    }

    private Conductor miConductor() {
        return conductores.findByUsuarioId(sesion.usuarioId())
                .orElseThrow(() -> new AccesoDenegadoException("Tu cuenta no tiene perfil de conductor"));
    }

    private static String texto(Object o, int max) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

}
