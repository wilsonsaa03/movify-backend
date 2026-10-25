package com.movify.backend.servicios;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Cuerpos de las peticiones de servicios. Los nombres JSON siguen en snake_case (como la web). */
public final class ServicioDtos {

    private ServicioDtos() {
    }

    /**
     * Solicitud de transporte, domicilio o encomienda. El cliente sale del token; la distancia,
     * la duración y el precio los calcula el servidor.
     */
    public record SolicitudRequest(
            @NotNull @DecimalMin("-90") @DecimalMax("90") @JsonProperty("origen_lat") Double origenLat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") @JsonProperty("origen_lng") Double origenLng,
            @NotNull(message = "Debes seleccionar un punto de destino en el mapa")
            @DecimalMin("-90") @DecimalMax("90") @JsonProperty("destino_lat") Double destinoLat,
            @NotNull(message = "Debes seleccionar un punto de destino en el mapa")
            @DecimalMin("-180") @DecimalMax("180") @JsonProperty("destino_lng") Double destinoLng,
            @Size(max = 30) @JsonAlias("tipo_servicio") String tipo,
            @Size(max = 10) @JsonProperty("tipo_vehiculo") String tipoVehiculo,
            @Size(max = 200) String origen,
            @Size(max = 80) @JsonProperty("origen_barrio") String origenBarrio,
            @Size(max = 200) @JsonProperty("origen_referencia") String origenReferencia,
            @Size(max = 200) String destino,
            @Size(max = 80) @JsonProperty("destino_barrio") String destinoBarrio,
            @Size(max = 200) @JsonProperty("destino_referencia") String destinoReferencia,
            @Size(max = 500) String descripcion,
            @Size(max = 300) String notas,
            @Min(1) @Max(8) Integer pasajeros,
            @JsonProperty("entrega_rapida") Boolean entregaRapida,
            @Size(max = 30) @JsonProperty("metodo_pago") String metodoPago,
            // Domicilio
            @Size(max = 120) String establecimiento,
            @JsonProperty("requiere_compra") Boolean requiereCompra,
            @JsonProperty("valor_productos_aprox") BigDecimal valorProductosAprox,
            @Size(max = 20) @JsonProperty("telefono_contacto") String telefonoContacto,
            @Valid List<ItemDomicilio> items,
            /** comida | farmacia | mercado | diligencia (V12). */
            @Size(max = 12) String categoria,
            // Encomienda
            @Size(max = 120) @JsonProperty("nombre_remitente") String nombreRemitente,
            @Size(max = 20) @JsonProperty("telefono_remitente") String telefonoRemitente,
            @Size(max = 120) @JsonProperty("nombre_destinatario") String nombreDestinatario,
            @Size(max = 20) @JsonProperty("telefono_destinatario") String telefonoDestinatario,
            @Size(max = 30) @JsonProperty("tipo_paquete") String tipoPaquete,
            @Size(max = 10) String tamano,
            @Size(max = 10) @JsonProperty("peso_kg") String pesoKg,
            Boolean fragil,
            @JsonProperty("valor_declarado") BigDecimal valorDeclarado,
            /** true = el conductor le cobra a quien recibe (V11). */
            @JsonProperty("paga_destinatario") Boolean pagaDestinatario,
            // Viaje (V14)
            /** Moto: el pasajero no tiene casco; solo se ofrece a motos que llevan uno. */
            @JsonProperty("necesita_casco") Boolean necesitaCasco,
            @JsonProperty("lleva_equipaje") Boolean llevaEquipaje,
            @JsonProperty("lleva_mascota") Boolean llevaMascota,
            /** Si el viaje es para otra persona. */
            @Size(max = 80) @JsonProperty("pasajero_nombre") String pasajeroNombre,
            @Size(max = 20) @JsonProperty("pasajero_telefono") String pasajeroTelefono,
            /** Una parada en el camino. */
            @Size(max = 200) String parada,
            @DecimalMin("-90") @DecimalMax("90") @JsonProperty("parada_lat") Double paradaLat,
            @DecimalMin("-180") @DecimalMax("180") @JsonProperty("parada_lng") Double paradaLng,
            /** Lo que el cliente ofrece pagar (≥ lo cotizado, con tope); null = lo cotizado. */
            @JsonProperty("valor_ofrecido") BigDecimal valorOfrecido) {
    }

    /** "Subir tarifa" mientras se busca conductor. */
    public record SubirTarifaRequest(@NotNull @DecimalMin("100") BigDecimal valor) {
    }

    /** Cambiar el destino (y la parada) con el viaje andando. Sin parada_lat = sin parada. */
    public record CambioDestinoRequest(
            @NotBlank @Size(max = 200) String destino,
            @Size(max = 80) @JsonProperty("destino_barrio") String destinoBarrio,
            @NotNull @DecimalMin("-90") @DecimalMax("90") @JsonProperty("destino_lat") Double destinoLat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") @JsonProperty("destino_lng") Double destinoLng,
            @Size(max = 200) String parada,
            @DecimalMin("-90") @DecimalMax("90") @JsonProperty("parada_lat") Double paradaLat,
            @DecimalMin("-180") @DecimalMax("180") @JsonProperty("parada_lng") Double paradaLng) {
    }

    /** La app detectó un desvío o una parada larga y el pasajero respondió "¿Todo bien?". */
    public record AlertaViajeRequest(
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "desvio|detenido") String tipo,
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "estoy_bien|sos|sin_respuesta") String respuesta,
            @DecimalMin("-90") @DecimalMax("90") Double lat,
            @DecimalMin("-180") @DecimalMax("180") Double lng) {
    }

    /** Programar un viaje en moto o taxi para más tarde. */
    public record ProgramadoRequest(
            @NotNull @JsonProperty("programado_para") java.time.OffsetDateTime programadoPara,
            @NotBlank @Size(max = 10) @JsonProperty("tipo_vehiculo") String tipoVehiculo,
            @NotNull @DecimalMin("-90") @DecimalMax("90") @JsonProperty("origen_lat") Double origenLat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") @JsonProperty("origen_lng") Double origenLng,
            @NotNull @DecimalMin("-90") @DecimalMax("90") @JsonProperty("destino_lat") Double destinoLat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") @JsonProperty("destino_lng") Double destinoLng,
            @Size(max = 200) String origen,
            @Size(max = 80) @JsonProperty("origen_barrio") String origenBarrio,
            @Size(max = 200) @JsonProperty("origen_referencia") String origenReferencia,
            @Size(max = 200) String destino,
            @Size(max = 80) @JsonProperty("destino_barrio") String destinoBarrio,
            @Min(1) @Max(4) Integer pasajeros,
            @JsonProperty("necesita_casco") Boolean necesitaCasco,
            @JsonProperty("lleva_equipaje") Boolean llevaEquipaje,
            @JsonProperty("lleva_mascota") Boolean llevaMascota,
            @Size(max = 80) @JsonProperty("pasajero_nombre") String pasajeroNombre,
            @Size(max = 20) @JsonProperty("pasajero_telefono") String pasajeroTelefono) {
    }

    public record ItemDomicilio(
            @NotBlank @Size(max = 150) String descripcion,
            @Min(1) @Max(99) Integer cantidad,
            @JsonProperty("precio_unitario_aprox") BigDecimal precioUnitarioAprox,
            @Size(max = 200) String notas) {
    }

    /**
     * estado: código nuevo (asignado, en_origen, en_curso, finalizado, cancelado, rechazar)
     * o nombre viejo de la web (ACEPTADO, EN_VIAJE, FINALIZADO, CANCELADO...).
     */
    public record CambioEstadoRequest(
            @NotBlank String estado,
            @Size(max = 300) String motivo,
            @JsonProperty("motivo_id") Short motivoId,
            @Size(max = 4) @JsonProperty("codigo_entrega") String codigoEntrega,
            /** PIN del viaje que el pasajero le dice al conductor al subir (V13). */
            @Size(max = 4) @JsonProperty("codigo_abordaje") String codigoAbordaje,
            @Size(max = 120) @JsonProperty("entregado_a") String entregadoA,
            @JsonProperty("valor_compra_real") BigDecimal valorCompraReal) {
    }

    public record CalificacionRequest(
            @NotNull @JsonProperty("servicio_id") Long servicioId,
            @NotNull @Min(value = 1, message = "La calificación va de 1 a 5")
            @Max(value = 5, message = "La calificación va de 1 a 5") @JsonAlias("puntuacion") Integer puntos,
            @Size(max = 300) String comentario) {
    }

    public record UbicacionRequest(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") @JsonAlias("lon") Double lng) {
    }

    public record MensajeRequest(@NotBlank @Size(max = 500) String contenido) {
    }

    public record SosRequest(
            @JsonProperty("servicio_id") Long servicioId,
            @DecimalMin("-90") @DecimalMax("90") Double lat,
            @DecimalMin("-180") @DecimalMax("180") Double lng) {
    }
}
