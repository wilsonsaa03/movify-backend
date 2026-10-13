package com.movify.backend.usuarios;

import com.fasterxml.jackson.annotation.JsonAlias;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class UsuarioDtos {

    private UsuarioDtos() {
    }

    /** nombre (web) se parte en nombres + apellidos; la app manda nombres y apellidos. */
    public record ActualizarPerfilRequest(
            @Size(min = 1, max = 160) String nombre,
            @Size(min = 1, max = 80) String nombres,
            @Size(max = 80) String apellidos,
            @Pattern(regexp = "^$|^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "Correo no válido") @Size(max = 120) String correo,
            @Pattern(regexp = "^$|^[0-9+ \\-]{7,20}$", message = "Teléfono no válido") String telefono,
            @Size(max = 300) String foto) {
    }

    /** Web: contraseña de 8+; app: clave de 4 números. */
    public record CambiarPasswordRequest(
            @NotBlank(message = "Escribe tu contraseña actual") String actual,
            @NotBlank @Size(min = 4, max = 72) String nueva) {
    }

    public record CodigoTelefonoRequest(@NotBlank String celular) {
    }

    public record VerificarTelefonoRequest(@NotBlank String verificacionToken) {
    }

    public record DireccionRequest(
            @NotBlank(message = "Ponle un nombre (Casa, Trabajo...)") @Size(max = 30) String etiqueta,
            @NotBlank(message = "Escribe la dirección") @Size(max = 200) String direccion,
            @Size(max = 80) String barrio,
            @Size(max = 200) String referencia,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng) {
    }

    public record ContactoRequest(
            @NotBlank @Size(max = 80) String nombre,
            @NotBlank String telefono,
            @Size(max = 30) String parentesco) {
    }

    public record DispositivoRequest(
            @NotBlank @Pattern(regexp = "android|ios|web") String plataforma,
            @NotBlank @Size(max = 300) @JsonAlias("token_push") String tokenPush,
            @Size(max = 80) String modelo,
            @Size(max = 20) @JsonAlias("version_app") String versionApp) {
    }
}
