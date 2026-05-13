package com.movify.backend.Modelo;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "historial_pagos")
@Data @NoArgsConstructor @AllArgsConstructor
public class HistorialPago {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "servicio_id", nullable = false)
    private Servicio servicio;

    @Column(nullable = false)
    private Double monto;

    // efectivo | tarjeta | wallet
    private String metodo;

    // pendiente | pagado | fallido
    private String estado = "pendiente";

    @Column(name = "fecha_pago")
    private LocalDateTime fechaPago;

    private String referencia;
}