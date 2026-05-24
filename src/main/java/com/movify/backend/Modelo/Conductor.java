package com.movify.backend.Modelo;

import java.time.LocalDateTime;

import jakarta.persistence.*;

import lombok.*;

@Entity
@Table(name = "conductores")

@Data
@NoArgsConstructor
@AllArgsConstructor

public class Conductor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)

    private Long id;

    // =========================
    // RELACION USUARIO
    // =========================

    @OneToOne
    @JoinColumn(name = "usuario_id")

    private Usuario usuario;

    // =========================
    // VEHICULO
    // =========================

    private String placa;

    private String modelo;

    // =========================
    // DOCUMENTOS
    // =========================

    private String licencia;

    private String soat;

    @Column(name = "tarjeta_propiedad")
    private String tarjetaPropiedad;

    private String cedula;

    // =========================
    // ESTADO
    // =========================

    private String estado = "pendiente";

    @Column(name = "fecha_verificacion")
    private LocalDateTime fechaVerificacion;

    // =========================
    // ESTADISTICAS
    // =========================

    @Column(name = "ganancias_hoy")
    private Double gananciasHoy = 0.0;

    @Column(name = "ganancias_semana")
    private Double gananciasSemana = 0.0;

    @Column(name = "viajes_hoy")
    private Integer viajesHoy = 0;

    @Column(name = "viajes_total")
    private Integer viajesTotal = 0;

    // ==========================================
    // CAMPOS DE UBICACIÓN Y ESTADO
    // ==========================================
    @Column(name = "latitud")
    private Double latitud;

    @Column(name = "longitud")
    private Double longitud;

    @Column(name = "en_linea")
    private Boolean enLinea;

    // ==========================================
    // GETTERS Y SETTERS
    // ==========================================

    public Double getLatitud() {
        return latitud;
    }

    public void setLatitud(Double latitud) {
        this.latitud = latitud;
    }

    public Double getLongitud() {
        return longitud;
    }

    public void setLongitud(Double longitud) {
        this.longitud = longitud;
    }

    public Boolean getEnLinea() {
        return enLinea;
    }

    public void setEnLinea(Boolean enLinea) {
        this.enLinea = enLinea;
    }

}