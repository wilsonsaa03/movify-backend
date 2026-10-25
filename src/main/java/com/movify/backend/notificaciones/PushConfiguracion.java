package com.movify.backend.notificaciones;

import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elige cómo se mandan las notificaciones push (mismo patrón que el SMS):
 *   1. Firebase Cloud Messaging, si FCM_CREDENCIALES apunta al JSON de la cuenta de servicio.
 *   2. Si no, solo se escriben en el log. La notificación igual queda guardada en la tabla
 *      notificaciones y la app la ve al preguntar el estado del viaje (no se pierde nada).
 */
@Configuration
public class PushConfiguracion {

    private static final Logger log = LoggerFactory.getLogger(PushConfiguracion.class);

    @Bean
    public PushEnviador pushEnviador(@Value("${app.push.fcm.credenciales:}") String rutaCredenciales) {
        if (!rutaCredenciales.isBlank()) {
            try {
                FcmPushEnviador fcm = new FcmPushEnviador(Files.readString(Path.of(rutaCredenciales)));
                log.info("Notificaciones push por Firebase (proyecto {})", fcm.proyecto());
                return fcm;
            } catch (Exception e) {
                log.error("No se pudo leer la cuenta de servicio de Firebase ({}): las notificaciones solo irán al log",
                        e.getMessage());
            }
        }
        log.info("Notificaciones push sin configurar (FCM_CREDENCIALES): solo se escriben en el log");
        return (token, titulo, cuerpo, datos) -> {
            log.debug("[push] {} — {} {}", titulo, cuerpo, datos);
            return true;
        };
    }
}
