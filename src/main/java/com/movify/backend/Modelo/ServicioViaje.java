package com.movify.backend.Modelo;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "servicio_viaje")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServicioViaje {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "usuario_id", nullable = false)
    private String usuarioId;

    @Column(name = "conductor_id")
    private String conductorId;

    @Column(name = "conductor_nombre")
    private String conductorNombre;

    @Column(name = "origen_lat", nullable = false)
    private Double origenLat;

    @Column(name = "origen_lng", nullable = false)
    private Double origenLng;

    @Column(name = "destino_lat", nullable = false)
    private Double destinoLat;

    @Column(name = "destino_lng", nullable = false)
    private Double destinoLng;

    @Column(name = "distancia_km", nullable = false)
    private Double distanciaKm;

    @Column(nullable = false)
    private Double tarifa;

    // pendiente | aceptado | rechazado | completado
    @Builder.Default
    @Column(nullable = false)
    private String estado = "pendiente";

    @Builder.Default
    @Column(name = "fecha_creacion")
    private LocalDateTime fechaCreacion = LocalDateTime.now();
}