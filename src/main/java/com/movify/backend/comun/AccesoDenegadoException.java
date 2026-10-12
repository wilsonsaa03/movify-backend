package com.movify.backend.comun;

/** El usuario autenticado no puede tocar este recurso (403). */
public class AccesoDenegadoException extends RuntimeException {
    public AccesoDenegadoException(String mensaje) {
        super(mensaje);
    }
}
