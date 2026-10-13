package com.movify.backend.autenticacion.sms;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movify.backend.comun.ReglaNegocioException;

/**
 * SMS con la API de Mensajes de Twilio (Programmable Messaging):
 *   POST https://api.twilio.com/2010-04-01/Accounts/{AccountSid}/Messages.json
 *   To=+57..., Body=..., y From=+1... o MessagingServiceSid=MG...
 *
 * El código lo genera y lo valida MoviFY (tabla codigos_verificacion del DER); Twilio solo lo entrega.
 * Credenciales por variables de entorno, nunca en el repositorio.
 */
public class TwilioSmsEnviador implements SmsEnviador {

    private static final Logger log = LoggerFactory.getLogger(TwilioSmsEnviador.class);
    private static final String API = "https://api.twilio.com/2010-04-01/Accounts/";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final String accountSid;
    private final String usuario;
    private final String clave;
    private final String from;
    private final String messagingServiceSid;

    /**
     * @param usuario  API Key (SK...) o, si no hay, el Account SID
     * @param clave    API Secret o, si no hay, el Auth Token
     */
    public TwilioSmsEnviador(String accountSid, String usuario, String clave, String from, String messagingServiceSid) {
        this.accountSid = accountSid;
        this.usuario = usuario;
        this.clave = clave;
        this.from = from;
        this.messagingServiceSid = messagingServiceSid;
    }

    @Override
    public void enviar(String celularE164, String texto) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("To", celularE164);
        form.put("Body", texto);
        if (messagingServiceSid != null && !messagingServiceSid.isBlank()) {
            form.put("MessagingServiceSid", messagingServiceSid);
        } else {
            form.put("From", from);
        }
        String cuerpo = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        String auth = Base64.getEncoder().encodeToString((usuario + ":" + clave).getBytes(StandardCharsets.UTF_8));

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(API + accountSid + "/Messages.json"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Basic " + auth)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(cuerpo))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 201 || res.statusCode() == 200) {
                JsonNode j = mapper.readTree(res.body());
                log.info("SMS enviado a {} (sid {}, estado {})", ocultar(celularE164), j.path("sid").asText(),
                        j.path("status").asText());
                return;
            }
            JsonNode j = mapper.readTree(res.body());
            // Errores típicos: 21608 (cuenta de prueba: número no verificado), 21211 (número inválido),
            // 21408 (país no habilitado en Geo Permissions), 20003 (credenciales)
            log.error("Twilio rechazó el SMS a {}: HTTP {} código {} - {}", ocultar(celularE164), res.statusCode(),
                    j.path("code").asText(), j.path("message").asText());
            if (j.path("code").asInt() == 21211 || j.path("code").asInt() == 21614) {
                throw new ReglaNegocioException(400, "Ese número no puede recibir SMS. Revisa que esté bien escrito.");
            }
        } catch (ReglaNegocioException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Envío de SMS interrumpido", e);
        } catch (Exception e) {
            log.error("No se pudo contactar a Twilio", e);
        }
        throw new ReglaNegocioException(503, "No pudimos enviarte el SMS en este momento. Intenta de nuevo en un minuto.");
    }

    private static String ocultar(String tel) {
        return tel == null || tel.length() < 4 ? "***" : "***" + tel.substring(tel.length() - 4);
    }
}
