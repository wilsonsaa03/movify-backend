package com.movify.backend.autenticacion.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.movify.backend.comun.ReglaNegocioException;

/**
 * Elige cómo se mandan los SMS:
 *   1. Twilio, si están TWILIO_ACCOUNT_SID + credenciales + (TWILIO_FROM o TWILIO_MESSAGING_SERVICE_SID).
 *   2. Consola, si SMS_CONSOLA=true (solo desarrollo).
 *   3. Si no hay nada configurado, pedir un código responde 503 (nunca se finge un envío).
 */
@Configuration
public class SmsConfiguracion {

    private static final Logger log = LoggerFactory.getLogger(SmsConfiguracion.class);

    @Bean
    public SmsEnviador smsEnviador(
            @Value("${app.sms.twilio.account-sid:}") String accountSid,
            @Value("${app.sms.twilio.auth-token:}") String authToken,
            @Value("${app.sms.twilio.api-key:}") String apiKey,
            @Value("${app.sms.twilio.api-secret:}") String apiSecret,
            @Value("${app.sms.twilio.from:}") String from,
            @Value("${app.sms.twilio.messaging-service-sid:}") String messagingServiceSid,
            @Value("${app.sms.consola:false}") boolean consola) {

        boolean credenciales = !apiKey.isBlank() && !apiSecret.isBlank() || !authToken.isBlank();
        boolean remitente = !from.isBlank() || !messagingServiceSid.isBlank();
        if (!accountSid.isBlank() && credenciales && remitente) {
            log.info("SMS por Twilio ({})", messagingServiceSid.isBlank() ? "número " + from : "servicio de mensajería");
            return apiKey.isBlank()
                    ? new TwilioSmsEnviador(accountSid, accountSid, authToken, from, messagingServiceSid)
                    : new TwilioSmsEnviador(accountSid, apiKey, apiSecret, from, messagingServiceSid);
        }
        if (consola) {
            return new ConsolaSmsEnviador();
        }
        log.warn("No hay proveedor de SMS configurado (TWILIO_*): no se podrán enviar códigos");
        return (celular, texto) -> {
            throw new ReglaNegocioException(503, "El envío de SMS no está configurado. Avísale al equipo de MoviFY.");
        };
    }
}
