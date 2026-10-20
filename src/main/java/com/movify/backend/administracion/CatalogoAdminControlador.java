package com.movify.backend.administracion;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonAlias;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * CRUD de los datos básicos de la plataforma (tarea 1.5). La app sigue leyendo los catálogos
 * por GET /api/catalogos (público); aquí el admin los mantiene. Soporte solo consulta.
 *
 *   /api/admin/catalogos/motivos-cancelacion[/{id}]
 *   /api/admin/catalogos/tipos-documento[/{codigo}]
 */
@RestController
@RequestMapping("/api/admin/catalogos")
public class CatalogoAdminControlador {

    private final CatalogoAdminServicio catalogos;

    public CatalogoAdminControlador(CatalogoAdminServicio catalogos) {
        this.catalogos = catalogos;
    }

    public record MotivoCancelacionRequest(
            @NotBlank(message = "Indica quién cancela con este motivo")
            @Pattern(regexp = "cliente|conductor|admin|sistema", message = "El actor debe ser cliente, conductor, admin o sistema")
            String actor,
            @NotBlank(message = "Escribe la descripción del motivo") @Size(max = 120, message = "La descripción tiene máximo 120 caracteres")
            String descripcion,
            Boolean activo) {
    }

    /** codigo solo se usa al crear; al editar va en la URL y no cambia. */
    public record TipoDocumentoRequest(
            @Pattern(regexp = "[a-z_]{3,30}", message = "El código va en minúsculas y guion bajo (3 a 30 letras)")
            String codigo,
            @NotBlank(message = "Escribe el nombre del documento") @Size(max = 80, message = "El nombre tiene máximo 80 caracteres")
            String nombre,
            @NotBlank(message = "Indica si el documento es del conductor o del vehículo")
            @Pattern(regexp = "conductor|vehiculo", message = "aplicaA debe ser conductor o vehiculo")
            @JsonAlias("aplica_a") String aplicaA,
            @JsonAlias("requiere_vencimiento") Boolean requiereVencimiento,
            @JsonAlias("obligatorio_moto") Boolean obligatorioMoto,
            @JsonAlias("obligatorio_taxi") Boolean obligatorioTaxi,
            @Pattern(regexp = "identidad|licencia|vehiculo|documentos", message = "El paso debe ser identidad, licencia, vehiculo o documentos")
            String paso,
            @Min(value = 0, message = "El orden va de 0 a 999") @Max(value = 999, message = "El orden va de 0 a 999")
            Short orden,
            @JsonAlias("solo_imagen") Boolean soloImagen,
            @Min(value = 1, message = "Los años van de 1 a 50") @Max(value = 50, message = "Los años van de 1 a 50")
            @JsonAlias("exigir_desde_anios") Short exigirDesdeAnios,
            @Size(max = 120, message = "La ayuda tiene máximo 120 caracteres") String ayuda) {
    }

    // ───────────── Motivos de cancelación ─────────────

    @GetMapping("/motivos-cancelacion")
    @PreAuthorize("hasAuthority('catalogos.ver')")
    public List<Map<String, Object>> motivos() {
        return catalogos.motivos();
    }

    @GetMapping("/motivos-cancelacion/{id}")
    @PreAuthorize("hasAuthority('catalogos.ver')")
    public Map<String, Object> motivo(@PathVariable int id) {
        return catalogos.motivo(id);
    }

    @PostMapping("/motivos-cancelacion")
    @PreAuthorize("hasAuthority('catalogos.gestionar')")
    public Map<String, Object> crearMotivo(@Valid @RequestBody MotivoCancelacionRequest req) {
        return catalogos.crearMotivo(req);
    }

    @PutMapping("/motivos-cancelacion/{id}")
    @PreAuthorize("hasAuthority('catalogos.gestionar')")
    public Map<String, Object> editarMotivo(@PathVariable int id, @Valid @RequestBody MotivoCancelacionRequest req) {
        return catalogos.editarMotivo(id, req);
    }

    @DeleteMapping("/motivos-cancelacion/{id}")
    @PreAuthorize("hasAuthority('catalogos.gestionar')")
    public Map<String, Object> eliminarMotivo(@PathVariable int id) {
        return catalogos.eliminarMotivo(id);
    }

    // ───────────── Tipos de documento ─────────────

    @GetMapping("/tipos-documento")
    @PreAuthorize("hasAuthority('catalogos.ver')")
    public List<Map<String, Object>> tiposDocumento() {
        return catalogos.tiposDocumento();
    }

    @GetMapping("/tipos-documento/{codigo}")
    @PreAuthorize("hasAuthority('catalogos.ver')")
    public Map<String, Object> tipoDocumento(@PathVariable String codigo) {
        return catalogos.tipoDocumento(codigo);
    }

    @PostMapping("/tipos-documento")
    @PreAuthorize("hasAuthority('catalogos.gestionar')")
    public Map<String, Object> crearTipoDocumento(@Valid @RequestBody TipoDocumentoRequest req) {
        return catalogos.crearTipoDocumento(req);
    }

    @PutMapping("/tipos-documento/{codigo}")
    @PreAuthorize("hasAuthority('catalogos.gestionar')")
    public Map<String, Object> editarTipoDocumento(@PathVariable String codigo, @Valid @RequestBody TipoDocumentoRequest req) {
        return catalogos.editarTipoDocumento(codigo, req);
    }

    @DeleteMapping("/tipos-documento/{codigo}")
    @PreAuthorize("hasAuthority('catalogos.gestionar')")
    public Map<String, Object> eliminarTipoDocumento(@PathVariable String codigo) {
        return catalogos.eliminarTipoDocumento(codigo);
    }
}
