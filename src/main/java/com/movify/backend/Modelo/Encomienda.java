package com.movify.backend.Modelo;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "encomiendas")
@Data @NoArgsConstructor @AllArgsConstructor
public class Encomienda {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "servicio_id", nullable = false)
    private Servicio servicio;

    private String tipo;
    private Double peso;
    private String destinatario;

    @Column(name = "telefono_destinatario")
    private String telefonoDestinatario;
}