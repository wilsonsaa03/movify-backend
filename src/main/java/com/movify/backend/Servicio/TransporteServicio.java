package com.movify.backend.Servicio;

import com.movify.backend.Base_de_datos.ConductorActivoRepositorio;
import com.movify.backend.Base_de_datos.NotificacionViajeRepositorio;
import com.movify.backend.Base_de_datos.ServicioViajeRepositorio;
import com.movify.backend.Modelo.ConductorActivo;
import com.movify.backend.Modelo.NotificacionViaje;
import com.movify.backend.Modelo.ServicioViaje;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class TransporteServicio {

    @Autowired
    private ConductorActivoRepositorio conductorRepo;

    @Autowired
    private ServicioViajeRepositorio servicioRepo;

    @Autowired
    private NotificacionViajeRepositorio notificacionRepo;

    // ================================
    // Conductor: activar en línea
    // ================================
    public ConductorActivo activarConductor(Map<String, Object> body) {

        String conductorId = (String) body.get("conductor_id");
        String nombre = (String) body.getOrDefault("nombre", "Conductor");
        Double lat = ((Number) body.getOrDefault("lat", 3.8801)).doubleValue();
        Double lng = ((Number) body.getOrDefault("lng", -77.0311)).doubleValue();

        ConductorActivo conductor = conductorRepo
                .findById(conductorId)
                .orElse(new ConductorActivo());

        conductor.setConductorId(conductorId);
        conductor.setNombre(nombre);
        conductor.setLat(lat);
        conductor.setLng(lng);

        return conductorRepo.save(conductor);
    }

    // ================================
    // Conductor: desactivar
    // ================================
    public void desactivarConductor(Map<String, Object> body) {
        String conductorId = (String) body.get("conductor_id");
        conductorRepo.deleteById(conductorId);
    }

    // ================================
    // Conductor: actualizar ubicación GPS
    // ================================
    public ConductorActivo actualizarUbicacion(Map<String, Object> body) {

        String conductorId = (String) body.get("conductor_id");
        Double lat = ((Number) body.get("lat")).doubleValue();
        Double lng = ((Number) body.get("lng")).doubleValue();

        Optional<ConductorActivo> optional = conductorRepo.findById(conductorId);

        if (optional.isPresent()) {
            ConductorActivo conductor = optional.get();
            conductor.setLat(lat);
            conductor.setLng(lng);
            return conductorRepo.save(conductor);
        }

        return null;
    }

    // ================================
    // Usuario: obtener conductores activos
    // ================================
    public List<ConductorActivo> getConductoresActivos() {
        return conductorRepo.findAll();
    }

    // ================================
    // Usuario: crear solicitud de viaje
    // ================================
    public ServicioViaje crearSolicitud(Map<String, Object> body) {

        ServicioViaje servicio = new ServicioViaje();
        servicio.setUsuarioId((String) body.get("usuario_id"));
        servicio.setOrigenLat(((Number) body.get("origen_lat")).doubleValue());
        servicio.setOrigenLng(((Number) body.get("origen_lng")).doubleValue());
        servicio.setDestinoLat(((Number) body.get("destino_lat")).doubleValue());
        servicio.setDestinoLng(((Number) body.get("destino_lng")).doubleValue());
        servicio.setDistanciaKm(((Number) body.get("distancia_km")).doubleValue());
        servicio.setTarifa(((Number) body.get("tarifa")).doubleValue());
        servicio.setEstado("pendiente");

        return servicioRepo.save(servicio);
    }

    // ================================
    // Usuario: notificar conductores
    // ================================
    public void notificarConductores(Map<String, Object> body) {

        @SuppressWarnings("unchecked")
        List<String> conductoresIds = (List<String>) body.get("conductores_ids");

        String servicioId = (String) body.get("servicio_id");
        String origen = (String) body.get("origen");

        for (String conductorId : conductoresIds) {
            NotificacionViaje notif = NotificacionViaje.builder()
                    .servicioId(servicioId)
                    .conductorId(conductorId)
                    .origen(origen)
                    .build();
            notificacionRepo.save(notif);
        }
    }

    // ================================
    // Conductor: obtener solicitudes pendientes
    // ================================
    public List<NotificacionViaje> getSolicitudesPendientes(String conductorId) {
        return notificacionRepo
                .findByConductorIdAndEstado(conductorId, "pendiente");
    }

    // ================================
    // Conductor: marcar notificación leída
    // ================================
    public void marcarLeida(String notifId) {
        notificacionRepo.findById(notifId).ifPresent(n -> {
            n.setEstado("leida");
            notificacionRepo.save(n);
        });
    }

    // ================================
    // Conductor: aceptar o rechazar viaje
    // ================================
    public ServicioViaje responderSolicitud(
            String servicioId,
            Map<String, Object> body) {
        String estado = (String) body.get("estado");
        String conductorId = (String) body.getOrDefault("conductor_id", "");

        return servicioRepo.findById(servicioId).map(servicio -> {

            // Solo procesar si aún está pendiente
            if (!"pendiente".equals(servicio.getEstado())) {
                return servicio;
            }

            servicio.setEstado(estado);

            if ("aceptado".equals(estado)) {
                servicio.setConductorId(conductorId);

                // Guardar nombre del conductor
                conductorRepo.findById(conductorId).ifPresent(c -> servicio.setConductorNombre(c.getNombre()));
            }

            return servicioRepo.save(servicio);

        }).orElse(null);
    }

    // ================================
    // Usuario: consultar estado del viaje
    // ================================
    public Optional<ServicioViaje> consultarEstado(String servicioId) {
        return servicioRepo.findById(servicioId);
    }
}