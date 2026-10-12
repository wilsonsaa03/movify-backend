package com.movify.backend.Seguridad;

/** Lo que viaja dentro del JWT. Es la única fuente de verdad sobre quién hace la petición. */
public record UsuarioAutenticado(Long id, String correo, String rol) {
}
