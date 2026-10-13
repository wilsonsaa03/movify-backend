package com.movify.backend.comun;

/** 404 con un mensaje que se puede mostrar al usuario. */
public class RecursoNoEncontradoException extends RuntimeException {
    public RecursoNoEncontradoException(String mensaje) {
        super(mensaje);
    }
}
