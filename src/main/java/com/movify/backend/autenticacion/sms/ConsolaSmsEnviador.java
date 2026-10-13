package com.movify.backend.autenticacion.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SOLO PARA DESARROLLO (SMS_CONSOLA=true): no envía nada, escribe el SMS en la consola.
 * En este modo la API también devuelve el código en la respuesta para poder probar la app.
 */
public class ConsolaSmsEnviador implements SmsEnviador {

    private static final Logger log = LoggerFactory.getLogger(ConsolaSmsEnviador.class);

    public ConsolaSmsEnviador() {
        log.warn("SMS EN MODO CONSOLA: los códigos NO se envían por SMS. No usar en producción.");
    }

    @Override
    public void enviar(String celularE164, String texto) {
        log.warn("[SMS de prueba para {}] {}", celularE164, texto);
    }

    @Override
    public boolean esModoConsola() {
        return true;
    }
}
