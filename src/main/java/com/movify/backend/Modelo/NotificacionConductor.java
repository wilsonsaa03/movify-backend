package com.movify.backend.Modelo;

import java.time.OffsetDateTime;

public class NotificacionConductor {

    public enum EstadoNotificacion {
        ENVIADA,
        ACEPTADA,
        RECHAZADA,
        RECHAZADA_POR_OTRO // Cuando otro conductor acepta el servicio
    }

    private Long id;
    private Long servicioId;
    private Long conductorId;
    private EstadoNotificacion estadoNotificacion;
    private OffsetDateTime fechaCreacion;
    private OffsetDateTime fechaRespuesta;

    // Getters y Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getServicioId() {
        return servicioId;
    }

    public void setServicioId(Long servicioId) {
        this.servicioId = servicioId;
    }

    public Long getConductorId() {
        return conductorId;
    }

    public void setConductorId(Long conductorId) {
        this.conductorId = conductorId;
    }

    public EstadoNotificacion getEstadoNotificacion() {
        return estadoNotificacion;
    }

    public void setEstadoNotificacion(EstadoNotificacion estadoNotificacion) {
        this.estadoNotificacion = estadoNotificacion;
    }

    public OffsetDateTime getFechaCreacion() {
        return fechaCreacion;
    }

    public void setFechaCreacion(OffsetDateTime fechaCreacion) {
        this.fechaCreacion = fechaCreacion;
    }

    public OffsetDateTime getFechaRespuesta() {
        return fechaRespuesta;
    }

    public void setFechaRespuesta(OffsetDateTime fechaRespuesta) {
        this.fechaRespuesta = fechaRespuesta;
    }
}