package com.movify.backend.administracion;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * Centro de operación del panel web (V15):
 *
 *   GET   /api/admin/en-vivo                conductores en línea, servicios activos y contadores (cada pocos segundos)
 *   GET   /api/admin/alertas-viaje?pendientes=true   "¿Todo bien?" de los viajes (desvío / parada larga)
 *   PATCH /api/admin/alertas-viaje/{id}     marcar revisada, con lo que se hizo
 *   GET   /api/admin/programados            viajes programados (pendientes y de los últimos 2 días)
 */
@RestController
@RequestMapping("/api/admin")
public class AdminOperacionControlador {

    private final AdminOperacionServicio operacion;

    public AdminOperacionControlador(AdminOperacionServicio operacion) {
        this.operacion = operacion;
    }

    @GetMapping("/en-vivo")
    @PreAuthorize("hasAuthority('admin_servicios.ver')")
    public Map<String, Object> enVivo() {
        return operacion.enVivo();
    }

    @GetMapping("/alertas-viaje")
    @PreAuthorize("hasAuthority('admin_sos.ver')")
    public List<Map<String, Object>> alertasViaje(@RequestParam(defaultValue = "false") boolean pendientes) {
        return operacion.alertasViaje(pendientes);
    }

    public record RevisionAlertaRequest(@Size(max = 500) String notas) {
    }

    @PatchMapping("/alertas-viaje/{id}")
    @PreAuthorize("hasAuthority('admin_sos.atender')")
    public Map<String, Object> revisarAlerta(@PathVariable Long id, @Valid @RequestBody RevisionAlertaRequest req) {
        return operacion.revisarAlerta(id, req.notas());
    }

    @GetMapping("/programados")
    @PreAuthorize("hasAuthority('admin_servicios.ver')")
    public List<Map<String, Object>> programados() {
        return operacion.programados();
    }
}
