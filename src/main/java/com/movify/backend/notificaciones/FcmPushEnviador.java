package com.movify.backend.notificaciones;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Firebase Cloud Messaging (API HTTP v1) con la cuenta de servicio del proyecto de Firebase.
 * El token OAuth se firma aquí mismo (JWT RS256 con la llave de la cuenta de servicio) y se
 * reutiliza hasta 5 minutos antes de vencer, así no hace falta la librería de Google.
 */
class FcmPushEnviador implements PushEnviador {

    private static final Logger log = LoggerFactory.getLogger(FcmPushEnviador.class);
    private static final String ALCANCE = "https://www.googleapis.com/auth/firebase.messaging";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String proyecto;
    private final String correo;
    private final String urlToken;
    private final PrivateKey llave;

    private String acceso;
    private Instant accesoVence = Instant.EPOCH;

    /** @param credenciales el JSON de la cuenta de servicio (Firebase → Configuración → Cuentas de servicio). */
    FcmPushEnviador(String credenciales) throws Exception {
        JsonNode c = json.readTree(credenciales);
        this.proyecto = c.path("project_id").asText();
        this.correo = c.path("client_email").asText();
        this.urlToken = c.path("token_uri").asText("https://oauth2.googleapis.com/token");
        String pem = c.path("private_key").asText()
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        this.llave = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        if (proyecto.isBlank() || correo.isBlank()) throw new IllegalArgumentException("Faltan project_id o client_email");
    }

    String proyecto() {
        return proyecto;
    }

    @Override
    public boolean enviar(String token, String titulo, String cuerpo, Map<String, String> datos) {
        try {
            Map<String, Object> mensaje = new LinkedHashMap<>();
            mensaje.put("token", token);
            mensaje.put("notification", Map.of("title", titulo, "body", cuerpo));
            mensaje.put("data", datos);
            // Prioridad alta: que suene aunque el celular esté en reposo (el conductor tiene 20 s para aceptar)
            mensaje.put("android", Map.of("priority", "HIGH",
                    "notification", Map.of("channel_id", "viajes", "sound", "default")));
            mensaje.put("apns", Map.of("payload", Map.of("aps", Map.of("sound", "default"))));
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(
                    URI.create("https://fcm.googleapis.com/v1/projects/" + proyecto + "/messages:send"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + acceso())
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("message", mensaje))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) return true;
            // 404 UNREGISTERED / 400 INVALID_ARGUMENT con el token: la app se desinstaló o el token cambió
            boolean tokenMuerto = r.statusCode() == 404 || r.body().contains("UNREGISTERED")
                    || (r.statusCode() == 400 && r.body().contains("registration token"));
            log.warn("FCM respondió {} ({})", r.statusCode(), tokenMuerto ? "token inválido" : r.body());
            return !tokenMuerto;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        } catch (Exception e) {
            log.warn("No se pudo mandar la notificación push: {}", e.getMessage());
            return true;
        }
    }

    private synchronized String acceso() throws Exception {
        if (acceso != null && Instant.now().isBefore(accesoVence.minusSeconds(300))) return acceso;
        long ahora = Instant.now().getEpochSecond();
        String cabeza = b64(json.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
        String carga = b64(json.writeValueAsBytes(Map.of("iss", correo, "scope", ALCANCE, "aud", urlToken,
                "iat", ahora, "exp", ahora + 3600)));
        Signature firma = Signature.getInstance("SHA256withRSA");
        firma.initSign(llave);
        firma.update((cabeza + "." + carga).getBytes(StandardCharsets.US_ASCII));
        String jwt = cabeza + "." + carga + "." + b64(firma.sign());

        String formulario = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + jwt;
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(urlToken))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formulario)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("Google no entregó el token de FCM: " + r.statusCode());
        JsonNode t = json.readTree(r.body());
        acceso = t.path("access_token").asText();
        accesoVence = Instant.now().plusSeconds(t.path("expires_in").asLong(3600));
        return acceso;
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
