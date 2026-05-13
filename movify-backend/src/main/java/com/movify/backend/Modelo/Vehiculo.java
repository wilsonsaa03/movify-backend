package com.movify.backend.Modelo;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "vehiculos")
@Data @NoArgsConstructor @AllArgsConstructor
public class Vehiculo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Relación 1:N con conductores
    @ManyToOne
    @JoinColumn(name = "conductor_id", nullable = false)
    private Conductor conductor;

    @Column(unique = true, nullable = false)
    private String placa;

    private String marca;
    private String modelo;

    // activo | inactivo
    private String estado = "activo";
}