package com.movify.backend.Dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Cuerpos de las peticiones de /api/transporte. Los nombres JSON se mantienen en snake_case. */
public final class ServicioDtos {

    private ServicioDtos() {
    }

    /**
     * El usuario_id y la distancia ya NO se reciben: el usuario sale del token y
     * la distancia la calcula el servidor con las coordenadas.
     */
    public record SolicitudRequest(
            @NotNull @DecimalMin("-90") @DecimalMax("90") @JsonProperty("origen_lat") Double origenLat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") @JsonProperty("origen_lng") Double origenLng,
            @NotNull(message = "Debes seleccionar un punto de destino en el mapa")
            @DecimalMin("-90") @DecimalMax("90") @JsonProperty("destino_lat") Double destinoLat,
            @NotNull(message = "Debes seleccionar un punto de destino en el mapa")
            @DecimalMin("-180") @DecimalMax("180") @JsonProperty("destino_lng") Double destinoLng,
            @JsonProperty("entrega_rapida") Boolean entregaRapida,
            @Size(max = 30) String tipo,
            @Size(max = 255) String descripcion,
            @Size(max = 255) String origen,
            @Size(max = 255) String destino,
            @Size(max = 150) @JsonProperty("nombre_remitente") String nombreRemitente,
            @Size(max = 20) @JsonProperty("telefono_remitente") String telefonoRemitente,
            @Size(max = 150) @JsonProperty("nombre_destinatario") String nombreDestinatario,
            @Size(max = 20) @JsonProperty("telefono_destinatario") String telefonoDestinatario,
            @Size(max = 50) @JsonProperty("tipo_paquete") String tipoPaquete,
            @Size(max = 10) @JsonProperty("peso_kg") String pesoKg,
            @Size(max = 30) @JsonProperty("metodo_pago") String metodoPago) {
    }

    /** conductor_id se ignora si llega: el conductor sale del token. */
    public record CambioEstadoRequest(@NotBlank String estado, @Size(max = 255) String motivo) {
    }

    public record CalificacionRequest(
            @NotNull @JsonProperty("servicio_id") Long servicioId,
            @NotNull @Min(value = 1, message = "La calificación va de 1 a 5")
            @Max(value = 5, message = "La calificación va de 1 a 5") Integer puntos,
            @Size(max = 500) String comentario) {
    }

    public record RetiroRequest(@NotNull @Positive(message = "Monto inválido") Double monto) {
    }

    public record UbicacionRequest(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng) {
    }

    public record EstadoConductorRequest(
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "(?i)pendiente|aprobado|rechazado|suspendido",
                    message = "Estado no válido") String estado,
            @Size(max = 500) @JsonProperty("motivo_rechazo") String motivoRechazo) {
    }
}
