package com.movify.backend.administracion;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Panel administrativo (V16). Cada endpoint exige su permiso del catálogo (rol_permisos → JWT), además de que
 * SecurityConfig solo deja entrar a /api/admin/** a admin y soporte. Que un botón no se vea en Angular no protege
 * nada: la regla está aquí.
 *
 *   GET  /api/admin/resumen-dia                      resumen operativo de hoy
 *   GET  /api/admin/servicios/buscar                 solicitudes con filtros y paginación
 *   POST /api/admin/servicios/{id}/reasignar         volver a buscar conductor (con motivo)
 *   GET  /api/admin/usuarios/buscar  · /usuarios/{id}  clientes y usuarios (datos parciales en la lista)
 *   GET  /api/admin/conductores/buscar · /conductores/{id}/expediente   checklist por tipo de vehículo
 *   GET  /api/admin/reportes · /reportes/servicios.csv
 *   GET  /api/admin/calificaciones?maximo=2
 *   POST /api/admin/equipo                           dar rol de soporte o admin a una cuenta existente
 *   GET/PUT /api/admin/permisos                      matriz de permisos (solo soporte es editable)
 *   GET  /api/admin/catalogos/operacion · GET/POST/DELETE /api/admin/catalogos/contenido-prohibido
 */
@RestController
@RequestMapping("/api/admin")
public class AdminPanelControlador {

    private final AdminPanelServicio panel;

    public AdminPanelControlador(AdminPanelServicio panel) {
        this.panel = panel;
    }

    @GetMapping("/resumen-dia")
    @PreAuthorize("hasAuthority('reportes.ver')")
    public Map<String, Object> resumenDia() {
        return panel.resumenDelDia();
    }

    // ───────────── Solicitudes ─────────────

    @GetMapping("/servicios/buscar")
    @PreAuthorize("hasAuthority('admin_servicios.ver')")
    public Map<String, Object> servicios(@RequestParam(name = "tipo_servicio", required = false) String tipoServicio,
            @RequestParam(name = "tipo_vehiculo", required = false) String tipoVehiculo,
            @RequestParam(required = false) String estado, @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta, @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int pagina, @RequestParam(defaultValue = "20") int tamano) {
        return panel.buscarServicios(tipoServicio, tipoVehiculo, estado, desde, hasta, q, pagina, tamano);
    }

    public record MotivoRequest(@NotBlank @Size(min = 5, max = 300) String motivo) {
    }

    @PostMapping("/servicios/{id}/reasignar")
    @PreAuthorize("hasAuthority('admin_servicios.cancelar')")
    public Map<String, Object> reasignar(@PathVariable Long id, @Valid @RequestBody MotivoRequest req) {
        return panel.reasignar(id, req.motivo());
    }

    // ───────────── Usuarios y conductores ─────────────

    @GetMapping("/usuarios/buscar")
    @PreAuthorize("hasAuthority('admin_usuarios.ver')")
    public Map<String, Object> usuarios(@RequestParam(required = false) String q, @RequestParam(required = false) String rol,
            @RequestParam(required = false) String estado, @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "20") int tamano) {
        return panel.buscarUsuarios(q, rol, estado, pagina, tamano);
    }

    @GetMapping("/usuarios/{id}")
    @PreAuthorize("hasAuthority('admin_usuarios.ver')")
    public Map<String, Object> usuario(@PathVariable Long id) {
        return panel.detalleUsuario(id);
    }

    @GetMapping("/conductores/buscar")
    @PreAuthorize("hasAuthority('admin_conductores.ver')")
    public Map<String, Object> conductores(@RequestParam(required = false) String estado,
            @RequestParam(name = "tipo_vehiculo", required = false) String tipoVehiculo,
            @RequestParam(required = false) Boolean conectado, @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int pagina, @RequestParam(defaultValue = "20") int tamano) {
        return panel.buscarConductores(estado, tipoVehiculo, conectado, q, pagina, tamano);
    }

    @GetMapping("/conductores/{id}/expediente")
    @PreAuthorize("hasAuthority('admin_conductores.ver')")
    public Map<String, Object> expediente(@PathVariable Long id) {
        return panel.expedienteConductor(id);
    }

    // ───────────── Reportes y calificaciones ─────────────

    @GetMapping("/reportes")
    @PreAuthorize("hasAuthority('reportes.ver')")
    public Map<String, Object> reportes(@RequestParam(required = false) String desde, @RequestParam(required = false) String hasta) {
        return panel.reportes(desde, hasta);
    }

    @GetMapping("/reportes/servicios.csv")
    @PreAuthorize("hasAuthority('reportes.exportar')")
    public ResponseEntity<byte[]> csv(@RequestParam(required = false) String desde, @RequestParam(required = false) String hasta) {
        byte[] datos = panel.csvServicios(desde, hasta).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"movify-servicios.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).body(datos);
    }

    @GetMapping("/calificaciones")
    @PreAuthorize("hasAuthority('admin_usuarios.ver')")
    public Map<String, Object> calificaciones(@RequestParam(defaultValue = "2") int maximo,
            @RequestParam(required = false) String a, @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "20") int tamano) {
        return panel.calificaciones(maximo, a, pagina, tamano);
    }

    // ───────────── Equipo y permisos ─────────────

    public record EquipoRequest(@NotBlank @Size(max = 120) String identificador,
            @NotBlank @Pattern(regexp = "soporte|admin") String rol) {
    }

    @PostMapping("/equipo")
    @PreAuthorize("hasAuthority('admin_usuarios.asignar_roles')")
    public Map<String, Object> agregarAlEquipo(@Valid @RequestBody EquipoRequest req) {
        return panel.agregarAlEquipo(req.identificador(), req.rol());
    }

    @GetMapping("/permisos")
    @PreAuthorize("hasAuthority('seguridad.ver')")
    public Map<String, Object> permisos() {
        return panel.permisos();
    }

    public record PermisoRequest(@NotBlank String rol, @NotBlank String permiso, @NotNull Boolean activo) {
    }

    @PutMapping("/permisos")
    @PreAuthorize("hasAuthority('seguridad.gestionar')")
    public Map<String, Object> cambiarPermiso(@Valid @RequestBody PermisoRequest req) {
        return panel.cambiarPermiso(req.rol(), req.permiso(), req.activo());
    }

    // ───────────── Catálogos de operación ─────────────

    @GetMapping("/catalogos/operacion")
    @PreAuthorize("hasAuthority('catalogos.ver')")
    public Map<String, Object> operacion() {
        return panel.catalogoOperacion();
    }

    @GetMapping("/catalogos/contenido-prohibido")
    @PreAuthorize("hasAuthority('catalogos.ver')")
    public Map<String, Object> contenidoProhibido() {
        return panel.contenidoProhibido();
    }

    public record PalabraRequest(@NotBlank @Size(max = 60) String palabra, @Size(max = 40) String categoria,
            @Pattern(regexp = "prohibida|permitida") String tipo) {
    }

    @PostMapping("/catalogos/contenido-prohibido")
    @PreAuthorize("hasAuthority('catalogos.gestionar')")
    public Map<String, Object> agregarPalabra(@Valid @RequestBody PalabraRequest req) {
        return panel.agregarPalabra(req.palabra(), req.categoria(), req.tipo());
    }

    @DeleteMapping("/catalogos/contenido-prohibido/{id}")
    @PreAuthorize("hasAuthority('catalogos.gestionar')")
    public Map<String, Object> quitarPalabra(@PathVariable Long id) {
        panel.quitarPalabra(id);
        return Map.of("message", "Palabra quitada");
    }
}
