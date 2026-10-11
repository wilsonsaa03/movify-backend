package com.movify.backend.Servicio;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Distancia y tarifa calculadas SOLO en el servidor. El cliente ya no manda distancia_km.
 */
@Service
public class TarifaServicio {

    private static final Logger log = LoggerFactory.getLogger(TarifaServicio.class);
    /** Las calles no son línea recta: si OSRM falla se aplica este factor a la distancia geodésica. */
    private static final double FACTOR_CALLES = 1.3;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock reloj;

    @Value("${app.osrm.url:https://router.project-osrm.org}")
    private String osrmUrl;

    public TarifaServicio(@Value("${app.zona-horaria:America/Bogota}") String zona) {
        this.reloj = Clock.system(ZoneId.of(zona));
    }

    public record Cotizacion(double distanciaKm, double tarifa) {
    }

    public Cotizacion cotizar(double oLat, double oLng, double dLat, double dLng, boolean entregaRapida) {
        double km = distanciaPorCalles(oLat, oLng, dLat, dLng);
        return new Cotizacion(Math.round(km * 100.0) / 100.0, calcular(km, entregaRapida, LocalTime.now(reloj)));
    }

    /** Pública para poder probarla con cualquier hora. */
    public static double calcular(double distanciaKm, boolean entregaRapida, LocalTime hora) {
        double base = 2500;
        double precioKm = 1200;
        double minutosEstimados = distanciaKm * 2.5; // tráfico promedio
        double precioMinuto = 150;
        double total = base + distanciaKm * precioKm + minutosEstimados * precioMinuto;

        int h = hora.getHour();
        double factor = 1.0;
        if ((h >= 7 && h <= 9) || (h >= 17 && h <= 19)) {
            factor = 1.4; // hora pico (hora de Colombia, no la del VPS)
        } else if (h >= 22 || h <= 5) {
            factor = 1.2; // nocturno
        }
        double calculo = total * factor;
        if (entregaRapida) calculo *= 1.20;
        return Math.ceil(calculo / 100) * 100;
    }

    private double distanciaPorCalles(double oLat, double oLng, double dLat, double dLng) {
        try {
            String url = String.format(Locale.US, "%s/route/v1/driving/%f,%f;%f,%f?overview=false",
                    osrmUrl, oLng, oLat, dLng, dLat);
            HttpResponse<String> res = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(4)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                JsonNode ruta = mapper.readTree(res.body()).path("routes").path(0);
                if (ruta.has("distance")) return ruta.get("distance").asDouble() / 1000.0;
            }
        } catch (Exception e) {
            log.warn("OSRM no respondió, se usa distancia aproximada: {}", e.getMessage());
        }
        return haversineKm(oLat, oLng, dLat, dLng) * FACTOR_CALLES;
    }

    public static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                        * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
