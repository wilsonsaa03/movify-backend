package com.movify.backend.Modelo;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "conductores")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Conductor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // FK → usuarios.id (relación 1:1 del diagrama) para que no se repitan usuarios como conductores
    @OneToOne
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    // licencia de conducir y soat son necesarios para aprobar la solicitud de conductor
    private String licencia;
    private String soat;

    //estados de solicitud de conductor pendiente | aprobado | rechazado
    private String estado = "pendiente";

    @Column(name = "fecha_verificacion")
    private LocalDateTime fechaVerificacion;
}