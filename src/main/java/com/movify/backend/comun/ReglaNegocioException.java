package com.movify.backend.comun;

/** La petición está bien formada pero viola una regla del negocio (409 por defecto). */
public class ReglaNegocioException extends RuntimeException {
    private final int status;

    public ReglaNegocioException(String mensaje) {
        this(409, mensaje);
    }

    public ReglaNegocioException(int status, String mensaje) {
        super(mensaje);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
