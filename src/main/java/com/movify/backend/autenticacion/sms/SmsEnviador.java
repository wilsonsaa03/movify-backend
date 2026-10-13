package com.movify.backend.autenticacion.sms;

/** Envía un SMS a un celular en formato E.164 (+573001234567). */
public interface SmsEnviador {

    void enviar(String celularE164, String texto);

    /** true si el SMS NO sale de verdad (modo desarrollo: se escribe en la consola). */
    default boolean esModoConsola() {
        return false;
    }
}
