package com.movify.backend.Modelo;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "notificacion_viaje")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificacionViaje {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "servicio_id", nullable = false)
    private String servicioId;

    @Column(name = "conductor_id", nullable = false)
    private String conductorId;

    @Column(nullable = false)
    private String origen;

    // pendiente | leida
    @Builder.Default
    @Column(nullable = false)
    private String estado = "pendiente";

    @Builder.Default
    @Column(name = "fecha_envio")
    private LocalDateTime fechaEnvio = LocalDateTime.now();
}