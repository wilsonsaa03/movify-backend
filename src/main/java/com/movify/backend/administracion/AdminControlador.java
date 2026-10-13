package com.movify.backend.administracion;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
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
    private final ViajeServicio viajes;
    private final JdbcTemplate db;

    public AdminControlador(AdminServicio admin, ViajeServicio viajes, JdbcTemplate db) {
        this.admin = admin;
        this.viajes = viajes;
        this.db = db;
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
        return db.queryForList("""
                SELECT v.id, v.tipo, v.tipo_servicio, v.tipo_vehiculo, v.estado, v.estado_codigo, v.estado_nombre,
                       v.estado_color, v.tarifa, v.fecha_solicitud, v.origen, v.destino, v.destino_lat, v.destino_lng,
                       v.usuario_nombre, trim(u.nombres || ' ' || COALESCE(u.apellidos, '')) AS conductor_nombre,
                       v.motivo_cancelacion, v.cancelado_por
                FROM v_servicios_api v
                LEFT JOIN usuarios u ON u.id = v.conductor_id
                WHERE CAST(? AS varchar) IS NULL OR v.estado_codigo = ?
                ORDER BY v.fecha_solicitud DESC
                LIMIT ?
                """, estado, estado, Math.min(Math.max(limite, 1), 1000));
    }

    // ═════════════ Rutas nuevas /api/admin ═════════════

    @GetMapping("/api/admin/conductores/pendientes")
    @PreAuthorize("hasAuthority('admin_conductores.ver')")
    public List<Map<String, Object>> pendientes() {
        return db.queryForList("SELECT * FROM v_conductores_pendientes ORDER BY creado_en");
    }

    @PatchMapping("/api/admin/documentos/{id}")
    @PreAuthorize("hasAuthority('admin_conductores.aprobar')")
    public Map<String, Object> revisarDocumento(@PathVariable Long id, @Valid @RequestBody RevisionDocumentoRequest req) {
        return admin.revisarDocumento(id, req.estado(), req.observacion());
    }

    @GetMapping("/api/admin/documentos/por-vencer")
    @PreAuthorize("hasAuthority('admin_conductores.ver')")
    public List<Map<String, Object>> porVencer() {
        return db.queryForList("SELECT * FROM v_documentos_por_vencer ORDER BY dias_restantes");
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
        return Map.of(
                "por_dia", db.queryForList("SELECT * FROM v_resumen_diario WHERE fecha >= CURRENT_DATE - 30 ORDER BY fecha DESC"),
                "conductores_en_linea", db.queryForList("SELECT usuario_id, nombres, tipo_vehiculo, placa, lat, lng, ocupado FROM v_conductores_en_linea"),
                "totales", db.queryForMap("""
                        SELECT (SELECT count(*) FROM usuarios WHERE estado = 'activo') AS usuarios_activos,
                               (SELECT count(*) FROM conductores WHERE estado = 'aprobado') AS conductores_aprobados,
                               (SELECT count(*) FROM conductores WHERE estado = 'pendiente') AS conductores_pendientes,
                               (SELECT count(*) FROM servicios WHERE estado IN ('buscando','asignado','en_origen','en_curso')) AS servicios_activos,
                               (SELECT count(*) FROM alertas_sos WHERE estado IN ('abierta','en_atencion')) AS sos_abiertas,
                               (SELECT count(*) FROM tickets_soporte WHERE estado IN ('abierto','en_proceso')) AS pqrs_abiertas
                        """));
    }

    @GetMapping("/api/admin/tarifas")
    @PreAuthorize("hasAuthority('tarifas.ver')")
    public List<Map<String, Object>> tarifas() {
        return db.queryForList("SELECT * FROM tarifas ORDER BY tipo_servicio, tipo_vehiculo, activo DESC, vigente_desde DESC");
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
        return db.queryForList("SELECT clave, valor, descripcion, actualizado_en FROM configuracion_sistema ORDER BY clave");
    }

    @PutMapping("/api/admin/configuracion/{clave}")
    @PreAuthorize("hasAuthority('configuracion.editar')")
    public Map<String, Object> editarConfiguracion(@PathVariable String clave, @Valid @RequestBody ValorRequest req) {
        return admin.editarConfiguracion(clave, req.valor());
    }

    @GetMapping("/api/admin/sos")
    @PreAuthorize("hasAuthority('admin_sos.ver')")
    public List<Map<String, Object>> sos() {
        return db.queryForList("""
                SELECT a.*, trim(u.nombres || ' ' || COALESCE(u.apellidos, '')) AS usuario_nombre, u.telefono
                FROM alertas_sos a JOIN usuarios u ON u.id = a.usuario_id
                ORDER BY (a.estado IN ('abierta','en_atencion')) DESC, a.creado_en DESC LIMIT 200
                """);
    }

    @PatchMapping("/api/admin/sos/{id}")
    @PreAuthorize("hasAuthority('admin_sos.atender')")
    public Map<String, Object> atenderSos(@PathVariable Long id, @Valid @RequestBody SosAdminRequest req) {
        return admin.atenderSos(id, req.estado(), req.notas());
    }

    @GetMapping("/api/admin/pqrs")
    @PreAuthorize("hasAuthority('admin_pqrs.ver')")
    public List<Map<String, Object>> pqrs() {
        return db.queryForList("SELECT * FROM v_pqrs_pendientes ORDER BY vencida DESC, fecha_limite_respuesta");
    }

    @PatchMapping("/api/admin/pqrs/{id}")
    @PreAuthorize("hasAuthority('admin_pqrs.responder')")
    public Map<String, Object> responderPqrs(@PathVariable Long id, @Valid @RequestBody PqrsAdminRequest req) {
        return admin.responderPqrs(id, req.estado(), req.respuesta(), req.prioridad());
    }

    @GetMapping("/api/admin/auditoria")
    @PreAuthorize("hasAuthority('auditoria.ver')")
    public Map<String, Object> auditoria() {
        return Map.of(
                "acciones", db.queryForList("SELECT * FROM auditoria_admin ORDER BY creado_en DESC LIMIT 200"),
                "cambios", db.queryForList("SELECT * FROM auditoria_cambios ORDER BY creado_en DESC LIMIT 200"),
                "accesos_sospechosos", db.queryForList("SELECT * FROM v_accesos_sospechosos"));
    }
}
