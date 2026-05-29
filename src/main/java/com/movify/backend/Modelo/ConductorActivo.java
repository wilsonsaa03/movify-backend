package com.movify.backend.Modelo;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "conductor_activo")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConductorActivo {

    @Id
    @Column(name = "conductor_id")
    private String conductorId;

    @Column(nullable = false)
    private String nombre;

    private String foto;

    @Column(nullable = false)
    private Double lat;

    @Column(nullable = false)
    private Double lng;
}