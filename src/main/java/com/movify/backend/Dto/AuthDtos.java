package com.movify.backend.Dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Cuerpos de las peticiones de /api/auth. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank(message = "El correo es obligatorio") @Email(message = "Correo no válido") String correo,
            @NotBlank(message = "La contraseña es obligatoria") String password) {
    }

    public record RegistroUsuarioRequest(
            @NotBlank(message = "El nombre es obligatorio") @Size(max = 120) String nombre,
            @NotBlank(message = "El correo es obligatorio") @Email(message = "Correo no válido") String correo,
            @NotBlank(message = "La contraseña es obligatoria")
            @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres") String password,
            @Pattern(regexp = "^$|^[0-9+ ]{7,16}$", message = "Teléfono no válido") String telefono) {
    }

    public record RegistroAdminRequest(
            @NotBlank @Size(max = 120) String nombre,
            @NotBlank @Email String correo,
            @NotBlank @Size(min = 10, max = 72, message = "La contraseña del admin debe tener al menos 10 caracteres")
            String password,
            @NotBlank(message = "El código de administrador es obligatorio") String codigoAdmin) {
    }

    /** credential = id_token que entrega Google Identity Services en el navegador. */
    public record GoogleLoginRequest(@NotBlank(message = "Falta el token de Google") String token) {
    }

    public record OlvidePasswordRequest(@NotBlank @Email String correo) {
    }

    public record RestablecerPasswordRequest(
            @NotBlank String token,
            @NotBlank @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres")
            String password) {
    }

    public record CambiarEstadoUsuarioRequest(
            @NotBlank @Pattern(regexp = "activo|bloqueado|inactivo", message = "Estado no válido") String estado) {
    }

    public record CambiarPasswordRequest(
            @NotBlank(message = "Escribe tu contraseña actual") String actual,
            @NotBlank @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres")
            String nueva) {
    }

    public record ActualizarPerfilRequest(
            @Size(min = 1, max = 120) String nombre,
            @Pattern(regexp = "^$|^[0-9+ ]{7,16}$", message = "Teléfono no válido") String telefono,
            @Size(max = 500) String foto) {
    }
}
