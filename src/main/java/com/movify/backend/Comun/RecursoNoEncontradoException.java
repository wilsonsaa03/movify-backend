package com.movify.backend.Comun;

/** 404 con un mensaje que se puede mostrar al usuario. */
public class RecursoNoEncontradoException extends RuntimeException {
    public RecursoNoEncontradoException(String mensaje) {
        super(mensaje);
    }
}
