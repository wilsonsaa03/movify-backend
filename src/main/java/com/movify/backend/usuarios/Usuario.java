package com.movify.backend.usuarios;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Una fila de la tabla usuarios (DER v2.3). */
public record Usuario(
        Long id,
        String nombres,
        String apellidos,
        String telefono,
        boolean telefonoVerificado,
        String email,
        boolean emailVerificado,
        String googleSub,
        String passwordHash,
        String fotoUrl,
        String estado,
        String motivoBloqueo,
        BigDecimal calificacionPromedio,
        int totalCalificaciones,
        OffsetDateTime creadoEn) {

    public String nombreCompleto() {
        return apellidos == null || apellidos.isBlank() ? nombres : nombres + " " + apellidos;
    }
}
