package com.movify.backend.Modelo;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "cola_offline")
@Data @NoArgsConstructor @AllArgsConstructor
public class ColaOffline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    private String tipo;

    @Column(columnDefinition = "TEXT")
    private String payload;

    private Integer intentos = 0;

    @Column(name = "fecha_creacion")
    private LocalDateTime fechaCreacion = LocalDateTime.now();

    private Boolean sync = false;
}