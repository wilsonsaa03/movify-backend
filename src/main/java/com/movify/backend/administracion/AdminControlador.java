package com.movify.backend.administracion;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.movify.backend.autenticacion.AuthDtos.CambiarEstadoUsuarioRequest;
import com.movify.backend.servicios.ServicioDtos.CambioEstadoRequest;
import com.movify.backend.servicios.ViajeServicio;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Panel de administración y soporte. Cada endpoint exige un permiso del catálogo de seguridad
 * del DER (rol_permisos): soporte consulta y atiende; solo admin aprueba, bloquea y configura.
 * Se mantienen también las rutas viejas que usa la web (/api/auth/usuarios, /api/transporte/admin/...).
 */
@RestController
public class AdminControlador {

    private final AdminServicio admin;
    private final AdminConsultaServicio consultas;
    private final ViajeServicio viajes;

    public AdminControlador(AdminServicio admin, AdminConsultaServicio consultas, ViajeServicio viajes) {
        this.admin = admin;
        this.consultas = consultas;
        this.viajes = viajes;
    }

    public record EstadoConductorRequest(
            @NotBlank @Pattern(regexp = "(?i)pendiente|aprobado|rechazado|suspendido", message = "Estado no válido") String estado,
            @Size(max = 300) @JsonAlias({ "motivo_rechazo", "motivoRechazo" }) String motivo) {
    }

    public record RevisionDocumentoRequest(@NotBlank @Pattern(regexp = "aprobado|rechazado") String estado,
            @Size(max = 300) String observacion) {
    }

    public record RolRequest(@NotBlank @Pattern(regexp = "cliente|conductor|soporte|admin") String rol) {
    }

    public record TarifaRequest(
            @NotBlank @Pattern(regexp = "transporte|domicilio|encomienda") @JsonAlias("tipo_servicio") String tipoServicio,
            @NotBlank @Pattern(regexp = "moto|taxi") @JsonAlias("tipo_vehiculo") String tipoVehiculo,
            @NotNull @DecimalMin("0") @JsonAlias("tarifa_base") BigDecimal tarifaBase,
            @NotNull @DecimalMin("0") @JsonAlias("valor_km") BigDecimal valorKm,
            @NotNull @DecimalMin("0") @JsonAlias("valor_minuto") BigDecimal valorMinuto,
            @NotNull @DecimalMin("0") @JsonAlias("tarifa_minima") BigDecimal tarifaMinima,
            @NotNull @DecimalMin("0") @DecimalMax("100") @JsonAlias("recargo_nocturno_pct") BigDecimal recargoNocturnoPct) {
    }

    public record ValorRequest(@NotBlank @Size(max = 200) String valor) {
    }

    public record SosAdminRequest(@NotBlank @Pattern(regexp = "en_atencion|atendida|falsa_alarma") String estado,
            @Size(max = 500) String notas) {
    }

    public record PqrsAdminRequest(@Pattern(regexp = "en_proceso|resuelto|cerrado") String estado,
            @Size(max = 2000) String respuesta, @Pattern(regexp = "baja|media|alta|urgente") String prioridad) {
    }

    // ═════════════ Rutas que ya usaba la web ═════════════

    @GetMapping("/api/auth/usuarios")
    @PreAuthorize("hasAuthority('admin_usuarios.ver')")
    public List<Map<String, Object>> listarUsuarios() {
        return admin.usuarios();
    }

    @PatchMapping("/api/auth/usuarios/{id}/estado")
    @PreAuthorize("hasAuthority('admin_usuarios.bloquear')")
    public Map<String, Object> cambiarEstadoUsuario(@PathVariable Long id, @Valid @RequestBody CambiarEstadoUsuarioRequest req) {
        return admin.cambiarEstadoUsuario(id, req.estado(), req.motivo());
    }

    @GetMapping({ "/api/transporte/admin/conductores", "/api/admin/conductores" })
    @PreAuthorize("hasAuthority('admin_conductores.ver')")
    public List<Map<String, Object>> conductores(@RequestParam(required = false) String estado) {
        return admin.conductores(estado);
    }

    @GetMapping({ "/api/transporte/admin/conductor/{id}/detalle", "/api/admin/conductores/{id}" })
    @PreAuthorize("hasAuthority('admin_conductores.ver')")
    public Map<String, Object> detalleConductor(@PathVariable Long id) {
        return admin.detalleConductor(id);
    }

    @PatchMapping({ "/api/transporte/admin/conductor/{id}/estado", "/api/admin/conductores/{id}/estado" })
    @PreAuthorize("hasAuthority('admin_conductores.aprobar')")
    public Map<String, Object> estadoConductor(@PathVariable Long id, @Valid @RequestBody EstadoConductorRequest req) {
        return admin.cambiarEstadoConductor(id, req.estado(), req.motivo());
    }

    @GetMapping({ "/api/transporte/admin/servicios", "/api/admin/servicios" })
    @PreAuthorize("hasAuthority('admin_servicios.ver')")
    public List<Map<String, Object>> servicios(@RequestParam(required = false) String estado,
            @RequestParam(defaultValue = "200") int limite) {
        return consultas.servicios(estado, limite);
    }

    // ═════════════ Rutas nuevas /api/admin ═════════════

    @GetMapping("/api/admin/conductores/pendientes")
    @PreAuthorize("hasAuthority('admin_conductores.ver')")
    public List<Map<String, Object>> pendientes() {
        return consultas.conductoresPendientes();
    }

    @PatchMapping("/api/admin/documentos/{id}")
    @PreAuthorize("hasAuthority('admin_conductores.aprobar')")
    public Map<String, Object> revisarDocumento(@PathVariable Long id, @Valid @RequestBody RevisionDocumentoRequest req) {
        return admin.revisarDocumento(id, req.estado(), req.observacion());
    }

    @GetMapping("/api/admin/documentos/por-vencer")
    @PreAuthorize("hasAuthority('admin_conductores.ver')")
    public List<Map<String, Object>> porVencer() {
        return consultas.documentosPorVencer();
    }

    @PostMapping("/api/admin/usuarios/{id}/roles")
    @PreAuthorize("hasAuthority('admin_usuarios.asignar_roles')")
    public Map<String, Object> asignarRol(@PathVariable Long id, @Valid @RequestBody RolRequest req) {
        return admin.asignarRol(id, req.rol(), false);
    }

    @DeleteMapping("/api/admin/usuarios/{id}/roles/{rol}")
    @PreAuthorize("hasAuthority('admin_usuarios.asignar_roles')")
    public Map<String, Object> quitarRol(@PathVariable Long id, @PathVariable String rol) {
        return admin.asignarRol(id, rol, true);
    }

    @PostMapping("/api/admin/servicios/{id}/cancelar")
    @PreAuthorize("hasAuthority('admin_servicios.cancelar')")
    public Map<String, Object> cancelarServicio(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        Short motivo = body != null && body.get("motivo_id") instanceof Number n ? n.shortValue() : null;
        String detalle = body == null ? null : (String) body.get("motivo");
        return viajes.cambiarEstado(id, new CambioEstadoRequest("cancelado", detalle, motivo, null, null, null));
    }

    @GetMapping("/api/admin/resumen")
    @PreAuthorize("hasAuthority('reportes.ver')")
    public Map<String, Object> resumen() {
        return consultas.resumen();
    }

    @GetMapping("/api/admin/tarifas")
    @PreAuthorize("hasAuthority('tarifas.ver')")
    public List<Map<String, Object>> tarifas() {
        return consultas.tarifas();
    }

    @PostMapping("/api/admin/tarifas")
    @PreAuthorize("hasAuthority('tarifas.gestionar')")
    public Map<String, Object> nuevaTarifa(@Valid @RequestBody TarifaRequest t) {
        return admin.nuevaTarifa(t.tipoServicio(), t.tipoVehiculo(), t.tarifaBase(), t.valorKm(), t.valorMinuto(),
                t.tarifaMinima(), t.recargoNocturnoPct());
    }

    @GetMapping("/api/admin/configuracion")
    @PreAuthorize("hasAuthority('configuracion.ver')")
    public List<Map<String, Object>> configuracion() {
        return consultas.configuracion();
    }

    @PutMapping("/api/admin/configuracion/{clave}")
    @PreAuthorize("hasAuthority('configuracion.editar')")
    public Map<String, Object> editarConfiguracion(@PathVariable String clave, @Valid @RequestBody ValorRequest req) {
        return admin.editarConfiguracion(clave, req.valor());
    }

    @GetMapping("/api/admin/sos")
    @PreAuthorize("hasAuthority('admin_sos.ver')")
    public List<Map<String, Object>> sos() {
        return consultas.alertasSos();
    }

    @PatchMapping("/api/admin/sos/{id}")
    @PreAuthorize("hasAuthority('admin_sos.atender')")
    public Map<String, Object> atenderSos(@PathVariable Long id, @Valid @RequestBody SosAdminRequest req) {
        return admin.atenderSos(id, req.estado(), req.notas());
    }

    @GetMapping("/api/admin/pqrs")
    @PreAuthorize("hasAuthority('admin_pqrs.ver')")
    public List<Map<String, Object>> pqrs() {
        return consultas.pqrsPendientes();
    }

    @PatchMapping("/api/admin/pqrs/{id}")
    @PreAuthorize("hasAuthority('admin_pqrs.responder')")
    public Map<String, Object> responderPqrs(@PathVariable Long id, @Valid @RequestBody PqrsAdminRequest req) {
        return admin.responderPqrs(id, req.estado(), req.respuesta(), req.prioridad());
    }

    @GetMapping("/api/admin/auditoria")
    @PreAuthorize("hasAuthority('auditoria.ver')")
    public Map<String, Object> auditoria() {
        return consultas.auditoria();
    }
}
