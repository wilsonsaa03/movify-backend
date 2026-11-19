package com.movify.backend.servicios.encomiendas;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Cuerpos de las peticiones de encomiendas (JSON en snake_case, como el resto de la API). */
public final class EncomiendaDtos {

    private EncomiendaDtos() {
    }

    /** Un bulto. Las medidas son opcionales, pero van las tres o ninguna. */
    public record BultoRequest(
            @Size(max = 10) String tipo,
            @NotNull(message = "Escribe el peso de cada paquete")
            @DecimalMin(value = "0.01", message = "El peso debe ser mayor que cero")
            @DecimalMax(value = "100", message = "Cada paquete puede pesar hasta 100 kg")
            @JsonProperty("peso_kg") BigDecimal pesoKg,
            @DecimalMin(value = "0.1") @DecimalMax(value = "300") @JsonProperty("largo_cm") BigDecimal largoCm,
            @DecimalMin(value = "0.1") @DecimalMax(value = "300") @JsonProperty("ancho_cm") BigDecimal anchoCm,
            @DecimalMin(value = "0.1") @DecimalMax(value = "300") @JsonProperty("alto_cm") BigDecimal altoCm,
            @DecimalMin(value = "0") @JsonProperty("valor_declarado") BigDecimal valorDeclarado,
            Boolean fragil,
            @Size(max = 120) String descripcion) {
    }

    /** POST /api/encomiendas/cotizar */
    public record CotizarRequest(
            @NotNull @DecimalMin("-90") @DecimalMax("90") @JsonProperty("origen_lat") Double origenLat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") @JsonProperty("origen_lng") Double origenLng,
            @NotNull(message = "Elige a dónde va el paquete")
            @DecimalMin("-90") @DecimalMax("90") @JsonProperty("destino_lat") Double destinoLat,
            @NotNull(message = "Elige a dónde va el paquete")
            @DecimalMin("-180") @DecimalMax("180") @JsonProperty("destino_lng") Double destinoLng,
            @NotNull(message = "Agrega al menos un paquete") @Size(min = 1, max = 50) @Valid List<BultoRequest> paquetes) {
    }

    /** POST /api/servicios/{id}/encomienda/novedades (conductor asignado o personal). */
    public record NovedadRequest(
            @NotBlank @Pattern(regexp = "destinatario_ausente|remitente_ausente|direccion_incorrecta|sin_acceso|rechazado"
                    + "|paquete_danado|retraso|otro", message = "Tipo de novedad no válido") String tipo,
            @Size(max = 300) String nota,
            @DecimalMin("-90") @DecimalMax("90") Double lat,
            @DecimalMin("-180") @DecimalMax("180") Double lng) {
    }
}
