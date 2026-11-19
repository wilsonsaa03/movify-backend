package com.movify.backend.servicios.simulacion;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Motor de rutas de la simulación: pide a OSRM la geometría COMPLETA de la ruta por las calles
 * (overview=full, todos los vértices). Es el mismo OSRM que ya usan la app, la web y TarifaServicio.
 *
 * Perfil por vehículo: el OSRM público solo trae el perfil de carro ("driving"). Para la moto se puede
 * apuntar a un OSRM propio con perfil de moto (app.simulacion.osrm-url-moto / perfil-moto) sin tocar código.
 *
 * Si OSRM no responde NO se inventa una línea recta: devuelve vacío y la simulación vuelve a intentar.
 */
@Component
public class MotorRutas {

    private static final Logger log = LoggerFactory.getLogger(MotorRutas.class);

    public record Ruta(List<double[]> geometria, double distanciaM, double duracionS, String perfil) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final ObjectMapper json = new ObjectMapper();

    @Value("${app.osrm.url:https://router.project-osrm.org}")
    private String osrmUrl;
    @Value("${app.simulacion.osrm-url-moto:}")
    private String osrmUrlMoto;
    @Value("${app.simulacion.perfil-taxi:driving}")
    private String perfilTaxi;
    @Value("${app.simulacion.perfil-moto:driving}")
    private String perfilMoto;

    /** Ruta por las calles pasando por los puntos en orden ([lat, lng] cada uno). */
    public Optional<Ruta> ruta(String tipoVehiculo, List<double[]> puntos) {
        boolean moto = "moto".equals(tipoVehiculo);
        String base = moto && osrmUrlMoto != null && !osrmUrlMoto.isBlank() ? osrmUrlMoto : osrmUrl;
        String perfil = moto ? perfilMoto : perfilTaxi;
        String coords = puntos.stream().map(p -> String.format(Locale.US, "%.6f,%.6f", p[1], p[0]))
                .collect(Collectors.joining(";"));
        String url = base.replaceAll("/+$", "") + "/route/v1/" + perfil + "/" + coords
                + "?overview=full&geometries=geojson&steps=false&alternatives=false";
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(6)).header("User-Agent", "MoviFY-simulacion").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("Simulación: OSRM respondió {}", res.statusCode());
                return Optional.empty();
            }
            JsonNode r = json.readTree(res.body()).path("routes").path(0);
            JsonNode coordenadas = r.path("geometry").path("coordinates");
            if (!coordenadas.isArray() || coordenadas.size() < 2) return Optional.empty();
            List<double[]> g = new ArrayList<>(coordenadas.size());
            for (JsonNode c : coordenadas) g.add(new double[] { c.get(1).asDouble(), c.get(0).asDouble() });
            // La distancia se mide con la misma fórmula que usa el movimiento (no la de OSRM): cuadra exacto
            double largo = new RutaSimulada(g).largo();
            return Optional.of(new Ruta(g, largo, r.path("duration").asDouble(0), perfil));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Simulación: OSRM no respondió ({})", e.getMessage());
            return Optional.empty();
        }
    }
}
