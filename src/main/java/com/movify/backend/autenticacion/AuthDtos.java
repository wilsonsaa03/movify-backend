package com.movify.backend.autenticacion;

import com.fasterxml.jackson.annotation.JsonAlias;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Cuerpos de las peticiones de /api/auth. */
public final class AuthDtos {

    private AuthDtos() {
    }

    // ───────────── App móvil: celular + código SMS + clave de 4 dígitos ─────────────

    /** proposito: registro | login (recuperar clave o entrar con código) | cambio_telefono */
    public record PedirCodigoRequest(
            @NotBlank(message = "Escribe tu celular") String celular,
            @NotBlank String proposito) {
    }

    public record VerificarCodigoRequest(
            @NotBlank(message = "Escribe tu celular") String celular,
            @NotBlank String proposito,
            @NotBlank(message = "Escribe el código") @Pattern(regexp = "\\d{6}", message = "El código tiene 6 números") String codigo) {
    }

    /** rol: cliente | conductor (el conductor sube sus documentos después). */
    public record RegistroMovilRequest(
            @NotBlank(message = "Falta la verificación del celular") String verificacionToken,
            @Pattern(regexp = "cliente|conductor", message = "Rol no válido") String rol,
            @NotBlank(message = "Escribe tu nombre") @Size(max = 80) String nombres,
            @NotBlank(message = "Escribe tu apellido") @Size(max = 80) String apellidos,
            @Email(message = "El correo no es válido") @Size(max = 120) String correo,
            @NotBlank(message = "Crea tu clave") @Pattern(regexp = "\\d{4}", message = "La clave tiene 4 números") String clave,
            @AssertTrue(message = "Debes aceptar los Términos y la Política de privacidad") boolean aceptaTerminos) {
    }

    public record LoginCelularRequest(
            @NotBlank(message = "Escribe tu celular") String celular,
            @NotBlank(message = "Escribe tu clave") @Pattern(regexp = "\\d{4}", message = "La clave tiene 4 números") String clave) {
    }

    public record LoginCodigoRequest(@NotBlank String verificacionToken) {
    }

    public record RestablecerClaveRequest(
            @NotBlank String verificacionToken,
            @NotBlank @Pattern(regexp = "\\d{4}", message = "La clave tiene 4 números") String clave) {
    }

    public record RefreshRequest(@NotBlank @JsonAlias("refresh_token") String refreshToken) {
    }

    // ───────────── Web: correo + contraseña ─────────────

    public record LoginRequest(
            @NotBlank(message = "El correo es obligatorio") @Email(message = "Correo no válido") String correo,
            @NotBlank(message = "La contraseña es obligatoria") String password) {
    }

    public record RegistroUsuarioRequest(
            @NotBlank(message = "El nombre es obligatorio") @Size(max = 160) String nombre,
            @NotBlank(message = "El correo es obligatorio") @Email(message = "Correo no válido") String correo,
            @NotBlank(message = "La contraseña es obligatoria")
            @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres") String password,
            @Pattern(regexp = "^$|^[0-9+ \\-]{7,20}$", message = "Teléfono no válido") String telefono) {
    }

    public record RegistroAdminRequest(
            @NotBlank @Size(max = 160) String nombre,
            @NotBlank @Email String correo,
            @NotBlank @Size(min = 10, max = 72, message = "La contraseña del admin debe tener al menos 10 caracteres")
            String password,
            @NotBlank(message = "El código de administrador es obligatorio") String codigoAdmin) {
    }

    /** credential = id_token que entrega Google Identity Services (web) o el plugin de la app. */
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
            @NotBlank @Pattern(regexp = "activo|bloqueado|inactivo", message = "Estado no válido") String estado,
            @Size(max = 300) String motivo) {
    }
}
