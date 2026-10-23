package com.movify.backend.conductores;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.movify.backend.comun.AccesoDenegadoException;
import com.movify.backend.comun.ArchivoServicio;
import com.movify.backend.comun.Datos;
import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.conductores.ConductorDtos.DisponibilidadRequest;
import com.movify.backend.conductores.ConductorDtos.IdentidadRequest;
import com.movify.backend.conductores.ConductorDtos.LicenciaRequest;
import com.movify.backend.conductores.ConductorDtos.PerfilConductorRequest;
import com.movify.backend.conductores.ConductorDtos.SolicitudRequest;
import com.movify.backend.configuracion.ContextoPeticion;
import jakarta.servlet.http.HttpServletRequest;
import com.movify.backend.conductores.ConductorDtos.UbicacionRequest;
import com.movify.backend.conductores.ConductorDtos.VehiculoRequest;
import com.movify.backend.seguridad.SesionActual;

import jakarta.validation.Valid;

/**
 * Registro y operación del conductor (moto o taxi).
 *
 * Flujo de la app: cuenta (registro-movil, sin roles) → /identidad → /licencia → /vehiculos → /documentos
 * → /solicitud → el admin aprueba y la BD le asigna el rol conductor (V6) → /api/auth/refresh.
 *
 * Los pasos del registro solo piden tener perfil en conductores (aspirante). Operar (conectarse,
 * enviar ubicación, recibir viajes) sí exige el rol conductor: sesion.conductorId().
 */
@RestController
@RequestMapping("/api/conductor")
public class ConductorControlador {

    private final ConductorServicio conductores;
    private final SesionActual sesion;
    private final ArchivoServicio archivos;

    public ConductorControlador(ConductorServicio conductores, SesionActual sesion, ArchivoServicio archivos) {
        this.conductores = conductores;
        this.sesion = sesion;
        this.archivos = archivos;
    }

    // ───────────── Registro del conductor (app) ─────────────

    /** Aspirante o conductor: basta con haber creado el perfil (paso de identidad), no hace falta el rol. */
    private Long perfilPropio() {
        Long id = sesion.usuarioId();
        if (!conductores.tienePerfil(id)) {
            throw new AccesoDenegadoException("Tu cuenta no tiene perfil de conductor");
        }
        return id;
    }

    /** Cualquier usuario con sesión puede crear su perfil de conductor (por eso no exige el rol). */
    @PostMapping("/perfil")
    public Map<String, Object> guardarPerfil(@Valid @RequestBody PerfilConductorRequest req) {
        return conductores.guardarPerfil(sesion.usuarioId(), req);
    }

    /** Paso 1 de la app: tipo y número de documento + fecha de nacimiento. Crea el perfil si no existe. */
    @PostMapping("/identidad")
    public Map<String, Object> identidad(@Valid @RequestBody IdentidadRequest req) {
        return conductores.guardarIdentidad(sesion.usuarioId(), req);
    }

    /** Paso 2 de la app: categoría y número de licencia. */
    @PostMapping("/licencia")
    public Map<String, Object> licencia(@Valid @RequestBody LicenciaRequest req) {
        return conductores.guardarLicencia(perfilPropio(), req);
    }

    /** Paso 5 de la app: "Enviar solicitud". */
    @PostMapping("/solicitud")
    public Map<String, Object> enviarSolicitud(@RequestBody SolicitudRequest req, HttpServletRequest http) {
        ContextoPeticion ctx = ContextoPeticion.de(http);
        return conductores.enviarSolicitud(perfilPropio(), req.aceptaCondiciones(), ctx.ip(), ctx.plataforma());
    }

    /** Paso en el que va el registro del conductor. ?tipo=moto|taxi si todavía no registra el vehículo. */
    @GetMapping("/registro")
    public Map<String, Object> progresoRegistro(@RequestParam(required = false) String tipo) {
        return conductores.progresoRegistro(sesion.usuarioId(), tipo);
    }

    /** El conductor ve su propio documento (la app lo muestra como vista previa). */
    @GetMapping("/mis-documentos/{id}/archivo")
    public ResponseEntity<Resource> miDocumento(@PathVariable Long id) {
        ArchivoServicio.Archivo doc = archivos.obtener(conductores.archivoPropio(perfilPropio(), id));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(doc.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(doc.nombre()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(doc.recurso());
    }

    @GetMapping("/mi-perfil")
    public Map<String, Object> miPerfil() {
        return conductores.miPerfil(sesion.usuarioId());
    }

    @GetMapping("/vehiculos")
    public Map<String, Object> vehiculo() {
        Map<String, Object> v = conductores.vehiculoActivo(perfilPropio());
        if (v == null) throw new RecursoNoEncontradoException("Todavía no registras tu vehículo");
        return v;
    }

    @PostMapping("/vehiculos")
    public Map<String, Object> registrarVehiculo(@Valid @RequestBody VehiculoRequest req) {
        return conductores.registrarVehiculo(perfilPropio(), req);
    }

    @GetMapping("/documentos")
    public Map<String, Object> documentos() {
        Long id = perfilPropio();
        return Map.of("documentos", conductores.documentos(id), "faltantes", conductores.faltantes(id));
    }

    /** multipart: tipo (soat, licencia_conduccion...), archivo y fechaVencimiento (yyyy-MM-dd) si aplica. */
    @PostMapping("/documentos")
    public Map<String, Object> subirDocumento(@RequestParam String tipo, @RequestParam MultipartFile archivo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaVencimiento) {
        return conductores.subirDocumento(perfilPropio(), tipo, archivo, fechaVencimiento);
    }

    @PostMapping("/revision")
    public Map<String, Object> reenviarRevision() {
        return conductores.reenviarRevision(perfilPropio());
    }

    @PostMapping("/disponibilidad")
    public Map<String, Object> disponibilidad(@Valid @RequestBody DisponibilidadRequest req) {
        return conductores.disponibilidad(sesion.conductorId(), req.activo(), req.lat(), req.lng());
    }

    @PostMapping("/ubicacion")
    public Map<String, Object> ubicacion(@Valid @RequestBody UbicacionRequest req) {
        conductores.ubicacion(sesion.conductorId(), req);
        return Map.of("message", "Ubicación actualizada");
    }

    /** "Zonas con más pedidos" del mapa del conductor (últimos 7 días). */
    @GetMapping("/zonas-demanda")
    public List<Map<String, Object>> zonasDemanda() {
        sesion.conductorId(); // solo conductores
        return conductores.zonasDemanda();
    }

    // ───────────── Rutas que usa la web actual ─────────────

    /** El registro de conductores con placa y 4 PDFs de la web vieja ya no existe: ahora es por la app. */
    @PostMapping("/registro")
    public ResponseEntity<Map<String, Object>> registroWebRetirado() {
        return ResponseEntity.status(410).body(Map.of("error",
                "El registro de conductores ahora se hace desde la app móvil de MoviFY (moto o taxi)."));
    }

    /** Ubicación y disponibilidad del conductor autenticado. El correo de la URL debe ser el suyo. */
    @PutMapping("/{correo}/ubicacion")
    public ResponseEntity<Void> actualizarUbicacionWeb(@PathVariable String correo, @RequestBody Map<String, Object> datos) {
        if (!correo.equalsIgnoreCase(String.valueOf(sesion.usuario().correo()))) {
            throw new AccesoDenegadoException("Solo puedes actualizar tu propia ubicación");
        }
        Long id = sesion.conductorId();
        Double lat = Datos.decimal(datos.get("lat"));
        Double lng = datos.containsKey("lon") ? Datos.decimal(datos.get("lon")) : Datos.decimal(datos.get("lng"));
        if (datos.get("activo") instanceof Boolean activo) {
            conductores.disponibilidad(id, activo, lat, lng);
        } else if (lat != null && lng != null) {
            conductores.ubicacion(id, new UbicacionRequest(lat, lng, null, null, null));
        }
        return ResponseEntity.ok().build();
    }

    /** Edita nombre y foto del conductor (la ciudad es siempre Buenaventura). */
    @PutMapping("/perfil/actualizar")
    public Map<String, Object> actualizarPerfilWeb(@RequestBody Map<String, Object> datos) {
        return conductores.actualizarPerfilWeb(sesion.conductorId(), datos);
    }

    /** Perfil con estadísticas (lo usa la web y el panel del conductor). */
    @GetMapping("/perfil/{correo:.+}")
    public Map<String, Object> obtenerPerfil(@PathVariable String correo) {
        return conductores.perfilPorCorreo(correo, sesion.usuarioId(), sesion.esPersonal());
    }

    /**
     * Documentos de los conductores: solo personal con permiso (SecurityConfig).
     * Se entregan como descarga y con nosniff para que el navegador nunca los ejecute.
     */
    @GetMapping("/documento/{nombreArchivo:.+}")
    public ResponseEntity<Resource> obtenerDocumento(@PathVariable String nombreArchivo) {
        ArchivoServicio.Archivo doc = archivos.obtener(conductores.documentoRegistrado(nombreArchivo));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(doc.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(doc.nombre()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(doc.recurso());
    }
}
