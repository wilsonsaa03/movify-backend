package com.movify.backend.Seguridad;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Verifica el id_token (credential) de Google Identity Services contra Google.
 * Solo se confía en el correo que Google firma, nunca en el que manda el navegador.
 */
@Component
public class GoogleTokenVerificador {

    private static final Logger log = LoggerFactory.getLogger(GoogleTokenVerificador.class);
    private static final Set<String> EMISORES = Set.of("accounts.google.com", "https://accounts.google.com");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.google.client-id}")
    private String clientId;

    public record DatosGoogle(String sub, String correo, String nombre, String foto) {
    }

    /** @return los datos verificados, o null si el token no es válido para MoviFY. */
    public DatosGoogle verificar(String idToken) {
        if (idToken == null || idToken.isBlank()) return null;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                    "https://oauth2.googleapis.com/tokeninfo?id_token="
                            + URLEncoder.encode(idToken, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return null;

            JsonNode t = mapper.readTree(res.body());
            boolean audienciaOk = clientId.equals(t.path("aud").asText());
            boolean emisorOk = EMISORES.contains(t.path("iss").asText());
            boolean correoVerificado = "true".equals(t.path("email_verified").asText());
            long expira = t.path("exp").asLong(0);
            boolean vigente = expira * 1000 > System.currentTimeMillis();

            if (!(audienciaOk && emisorOk && correoVerificado && vigente)) {
                log.warn("Token de Google rechazado (aud={}, iss={}, verificado={}, vigente={})",
                        audienciaOk, emisorOk, correoVerificado, vigente);
                return null;
            }
            return new DatosGoogle(t.path("sub").asText(), t.path("email").asText().toLowerCase(),
                    t.path("name").asText(""), t.path("picture").asText(""));
        } catch (Exception e) {
            log.error("No se pudo verificar el token de Google", e);
            return null;
        }
    }
}
