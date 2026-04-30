package com.movify.backend.Modelo;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "fotos_evidencia")
@Data @NoArgsConstructor @AllArgsConstructor
public class FotoEvidencia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "servicio_id", nullable = false)
    private Servicio servicio;

    // inicio | entrega | soporte
    private String tipo;

    @Column(length = 500)
    private String url;

    @Column(length = 500)
    private String descripcion;

    private LocalDateTime fecha = LocalDateTime.now();

    private Boolean sync = false;
}