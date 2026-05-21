package com.movify.backend.Modelo;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "viajes")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Viaje {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String tipo;

    private String destino;

    private Double precio;

    private String estado;

    private LocalDateTime fecha;

    @ManyToOne
    @JoinColumn(name = "conductor_id")
    private Conductor conductor;
}