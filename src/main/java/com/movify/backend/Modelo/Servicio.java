package com.movify.backend.Modelo;

import java.time.OffsetDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "servicios")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Servicio {

    public enum EstadoServicio {
        PENDIENTE,
        ACEPTADO,
        RECHAZADO,
        FINALIZADO,
        CANCELADO
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long usuarioId;
    private Long conductorId; // Puede ser null si aún no ha sido aceptado
    private Double origenLat;
    private Double origenLng;
    private Double destinoLat;
    private Double destinoLng;
    private Double distanciaKm;
    private Double tarifa;

    @Builder.Default
    @Enumerated(EnumType.STRING) // Esto asegura que en la DB se guarde "PENDIENTE" y no 0
    private EstadoServicio estado = EstadoServicio.PENDIENTE;

    @Builder.Default
    private OffsetDateTime fechaSolicitud = OffsetDateTime.now();
    private OffsetDateTime fechaInicio;
    private OffsetDateTime fechaFin;
}