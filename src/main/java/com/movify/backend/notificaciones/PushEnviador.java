package com.movify.backend.notificaciones;

import java.util.Map;

/** Manda una notificación push a un dispositivo (token de Firebase Cloud Messaging). */
public interface PushEnviador {

    /**
     * @return false si el token ya no sirve (app desinstalada): el dispositivo se marca inactivo.
     */
    boolean enviar(String token, String titulo, String cuerpo, Map<String, String> datos);
}
