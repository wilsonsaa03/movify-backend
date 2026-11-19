package com.movify.backend.servicios.llamadas;

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
import com.movify.backend.comun.Datos;
import com.movify.backend.comun.ReglaNegocioException;

/**
 * Llamada con número protegido usando la API de Voz de Twilio (Programmable Voice):
 *
 *   POST https://api.twilio.com/2010-04-01/Accounts/{AccountSid}/Calls.json
 *   To = celular de quien llama, From = número de MoviFY en Twilio, Twiml = instrucciones en línea
 *
 * Twilio llama primero a quien tocó "Llamar"; cuando contesta, el &lt;Dial&gt; marca al otro mostrando el número
 * de MoviFY (callerId). Así ninguno de los dos ve el celular real del otro. Con Twiml en línea no hace falta
 * que Twilio pueda entrar al backend (no hay webhook público).
 *
 * Cuenta de prueba de Twilio: solo llama a números verificados en la consola y antes dice un mensaje de prueba.
 */
public class TwilioLlamadas {

    private static final Logger log = LoggerFactory.getLogger(TwilioLlamadas.class);
    private static final String API = "https://api.twilio.com/2010-04-01/Accounts/";
    /** Una llamada del viaje no necesita más de 10 minutos. */
    static final int LIMITE_SEGUNDOS = 600;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String accountSid;
    private final String usuario;
    private final String clave;
    private final String numero;

    public TwilioLlamadas(String accountSid, String usuario, String clave, String numero) {
        this.accountSid = accountSid;
        this.usuario = usuario;
        this.clave = clave;
        this.numero = numero;
    }

    /** @return el SID de la llamada en Twilio. */
    public String conectar(String quienLlamaE164, String aQuienE164, String saludo) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("To", quienLlamaE164);
        form.put("From", numero);
        form.put("Twiml", twiml(saludo, numero, aQuienE164));
        form.put("TimeLimit", String.valueOf(LIMITE_SEGUNDOS));
        form.put("Timeout", "25");
        String cuerpo = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        String auth = Base64.getEncoder().encodeToString((usuario + ":" + clave).getBytes(StandardCharsets.UTF_8));
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(API + accountSid + "/Calls.json"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Basic " + auth)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(cuerpo))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode j = json.readTree(res.body() == null || res.body().isBlank() ? "{}" : res.body());
            if (res.statusCode() == 201 || res.statusCode() == 200) {
                log.info("Llamada protegida creada (sid {}) de {} a {}", j.path("sid").asText(),
                        Datos.ocultarTelefono(quienLlamaE164), Datos.ocultarTelefono(aQuienE164));
                return j.path("sid").asText(null);
            }
            // 21219/21608: cuenta de prueba y número no verificado; 21215/21216: país o número sin permiso de voz
            log.error("Twilio rechazó la llamada: HTTP {} código {} - {}", res.statusCode(), j.path("code").asText(),
                    j.path("message").asText());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("No se pudo contactar a Twilio para la llamada", e);
        }
        throw new ReglaNegocioException(503, "No pudimos hacer la llamada en este momento. Usa el chat o intenta en un minuto.");
    }

    /** &lt;Say&gt; corto en español y &lt;Dial&gt; al otro con el número de MoviFY como identificador. */
    static String twiml(String saludo, String callerId, String destino) {
        return "<Response><Say language=\"es-MX\">" + xml(saludo) + "</Say>"
                + "<Dial callerId=\"" + xml(callerId) + "\" timeLimit=\"" + LIMITE_SEGUNDOS + "\" timeout=\"25\">"
                + "<Number>" + xml(destino) + "</Number></Dial>"
                + "<Say language=\"es-MX\">No contestó. Puedes escribirle por el chat de MoviFY.</Say></Response>";
    }

    static String xml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
