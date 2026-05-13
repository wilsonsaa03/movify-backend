package com.movify.backend.Modelo;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "conductores")
@Data @NoArgsConstructor @AllArgsConstructor
public class Conductor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Relación 1:1 con usuarios
    @OneToOne
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    private String licencia;
    private String soat;

    // pendiente | aprobado | rechazado
    private String estado = "pendiente";

    @Column(name = "fecha_verificacion")
    private LocalDateTime fechaVerificacion;
}