package com.movify.backend.Modelo;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "servicios")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Servicio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // FK → usuarios.id
    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    // FK → conductores.id
    @ManyToOne
    @JoinColumn(name = "conductor_id")
    private Conductor conductor;

    // FK → vehiculos.id
    @ManyToOne
    @JoinColumn(name = "vehiculo_id")
    private Vehiculo vehiculo;

    // transporte | domicilio | encomienda
    private String tipo;

    // solicitado | aceptado | en_curso | finalizado | cancelado
    private String estado = "solicitado";

    private String origen;
    private String destino;

    @Column(name = "fecha_solicitud")
    private LocalDateTime fechaSolicitud = LocalDateTime.now();

    @Column(name = "fecha_finalizacion")
    private LocalDateTime fechaFinalizacion;

    @Column(name = "monto_total")
    private Double montoTotal;
}