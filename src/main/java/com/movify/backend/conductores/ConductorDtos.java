package com.movify.backend.conductores;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class ConductorDtos {

    private ConductorDtos() {
    }

    /** Pasos "Identidad" y "Licencia" del registro de conductor (moto: A1/A2, taxi: C1/C2/C3). */
    public record PerfilConductorRequest(
            @NotBlank @Pattern(regexp = "CC|CE|PPT", message = "Tipo de documento no válido") @JsonAlias("tipo_identificacion")
            String tipoIdentificacion,
            @NotBlank(message = "Escribe tu número de documento")
            @Pattern(regexp = "[0-9A-Za-z.\\- ]{4,24}", message = "Número de documento no válido") @JsonAlias("numero_identificacion")
            String numeroIdentificacion,
            @NotNull(message = "Escribe tu fecha de nacimiento") @Past @JsonAlias("fecha_nacimiento") LocalDate fechaNacimiento,
            @NotEmpty(message = "Elige la categoría de tu licencia") @JsonAlias("categorias_licencia")
            List<@Pattern(regexp = "A1|A2|B1|B2|B3|C1|C2|C3", message = "Categoría no válida") String> categoriasLicencia) {
    }

    /** Paso "Vehículo". Taxi: placa ABC123 y empresa afiliadora. Moto: placa ABC12D. */
    public record VehiculoRequest(
            @NotBlank @Pattern(regexp = "moto|taxi", message = "Solo se aceptan moto o taxi") @JsonAlias("tipo_vehiculo")
            String tipoVehiculo,
            @NotBlank(message = "Escribe la placa") @Size(max = 10) String placa,
            @NotBlank(message = "Escribe la marca") @Size(max = 40) String marca,
            @Size(max = 40) String linea,
            @NotNull(message = "Escribe el modelo (año)") @Min(1990) @Max(2100) @JsonAlias("modelo_anio") Integer modeloAnio,
            @NotBlank(message = "Escribe el color") @Size(max = 30) String color,
            @Size(max = 80) @JsonAlias("empresa_afiliadora") String empresaAfiliadora,
            @Size(max = 10) @JsonAlias("numero_interno") String numeroInterno) {
    }

    public record DisponibilidadRequest(
            @NotNull Boolean activo,
            @DecimalMin("-90") @DecimalMax("90") Double lat,
            @DecimalMin("-180") @DecimalMax("180") Double lng) {
    }

    public record UbicacionRequest(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") @JsonAlias("lon") Double lng,
            @Min(0) @Max(359) Integer rumbo,
            @DecimalMin("0") Double velocidad,
            @DecimalMin("0") Double precision) {
    }
}
