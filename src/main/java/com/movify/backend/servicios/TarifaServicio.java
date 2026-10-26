package com.movify.backend.servicios;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Distancia, duración y precio. El precio lo calcula la BASE (fn_cotizar / fn_calcular_tarifa)
 * con la tarifa activa de cada servicio y vehículo: así no hay dos fórmulas distintas.
 * Aquí solo se averigua cuántos km y minutos tiene la ruta (OSRM; si falla, línea recta × 1.3).
 */
@Service
public class TarifaServicio {

    private static final Logger log = LoggerFactory.getLogger(TarifaServicio.class);
    /** Las calles no son línea recta: si OSRM falla se aplica este factor a la distancia geodésica. */
    private static final double FACTOR_CALLES = 1.3;
    /** Minutos por km en Buenaventura si OSRM no responde (tráfico promedio). */
    private static final double MINUTOS_POR_KM = 2.5;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final JdbcTemplate db;

    @Value("${app.osrm.url:https://router.project-osrm.org}")
    private String osrmUrl;

    public TarifaServicio(JdbcTemplate db) {
        this.db = db;
    }

    public record Ruta(BigDecimal distanciaKm, int duracionMin) {
    }

    public Ruta ruta(double oLat, double oLng, double dLat, double dLng) {
        double km;
        double min;
        try {
            String url = String.format(Locale.US, "%s/route/v1/driving/%f,%f;%f,%f?overview=false",
                    osrmUrl, oLng, oLat, dLng, dLat);
            HttpResponse<String> res = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(4)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode r = res.statusCode() == 200 ? mapper.readTree(res.body()).path("routes").path(0) : null;
            if (r != null && r.has("distance")) {
                km = r.get("distance").asDouble() / 1000.0;
                min = r.path("duration").asDouble(km * 60 * MINUTOS_POR_KM) / 60.0;
                return new Ruta(redondear(km), (int) Math.ceil(min));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("OSRM no respondió, se usa distancia aproximada: {}", e.getMessage());
        }
        km = haversineKm(oLat, oLng, dLat, dLng) * FACTOR_CALLES;
        return new Ruta(redondear(km), (int) Math.ceil(km * MINUTOS_POR_KM));
    }

    /** Ruta pasando por una parada (si hay): se suman los dos tramos. */
    public Ruta ruta(double oLat, double oLng, Double pLat, Double pLng, double dLat, double dLng) {
        if (pLat == null || pLng == null) return ruta(oLat, oLng, dLat, dLng);
        Ruta a = ruta(oLat, oLng, pLat, pLng);
        Ruta b = ruta(pLat, pLng, dLat, dLng);
        return new Ruta(a.distanciaKm().add(b.distanciaKm()), a.duracionMin() + b.duracionMin());
    }

    /** ¿Ahora corre el recargo nocturno? (misma hora de Colombia que usa fn_calcular_tarifa). */
    public boolean esNocturno() {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT h >= fn_config_num('hora_inicio_nocturno', 21) OR h < fn_config_num('hora_fin_nocturno', 5)
                FROM (SELECT EXTRACT(HOUR FROM now() AT TIME ZONE 'America/Bogota') AS h) x
                """, Boolean.class));
    }

    /** ¿El admin prendió el recargo por lluvia? (configuracion_sistema.lluvia_activa) */
    public boolean hayLluvia() {
        return Boolean.TRUE.equals(db.queryForObject("SELECT fn_config_num('lluvia_activa', 0) = 1", Boolean.class));
    }

    public BigDecimal recargoLluviaPct(String tipoServicio, String tipoVehiculo) {
        return db.queryForList("""
                SELECT recargo_lluvia_pct FROM tarifas WHERE tipo_servicio = ? AND tipo_vehiculo = ? AND activo
                """, BigDecimal.class, tipoServicio, tipoVehiculo).stream().findFirst().orElse(BigDecimal.ZERO);
    }

    /** % de recargo nocturno de la tarifa activa (0 si no tiene). */
    public BigDecimal recargoNocturnoPct(String tipoServicio, String tipoVehiculo) {
        return db.queryForList("""
                SELECT recargo_nocturno_pct FROM tarifas WHERE tipo_servicio = ? AND tipo_vehiculo = ? AND activo
                """, BigDecimal.class, tipoServicio, tipoVehiculo).stream().findFirst().orElse(BigDecimal.ZERO);
    }

    /** Lo máximo que el cliente puede ofrecer sobre lo cotizado (max_aumento_tarifa_pct, 100 % por defecto). */
    public BigDecimal maximoOfrecible(BigDecimal cotizado) {
        BigDecimal pct = db.queryForObject("SELECT fn_config_num('max_aumento_tarifa_pct', 100)", BigDecimal.class);
        return cotizado.multiply(BigDecimal.ONE.add(pct.movePointLeft(2))).setScale(0, RoundingMode.DOWN);
    }

    /** Valor que cobraría hoy la tarifa activa (incluye recargo nocturno y mínimo). */
    public BigDecimal cotizar(String tipoServicio, String tipoVehiculo, Ruta ruta) {
        return db.queryForObject("SELECT fn_cotizar(?::varchar, ?::varchar, ?::numeric, ?::numeric)", BigDecimal.class,
                tipoServicio, tipoVehiculo, ruta.distanciaKm(), ruta.duracionMin());
    }

    /** Opciones para la pantalla "Confirma tu viaje": una por vehículo permitido para ese servicio. */
    public List<Map<String, Object>> opciones(String tipoServicio, String tipoVehiculo, Ruta ruta) {
        List<String> vehiculos = db.queryForList("""
                SELECT p.tipo_vehiculo FROM servicio_vehiculo_permitido p
                JOIN tarifas t ON t.tipo_servicio = p.tipo_servicio AND t.tipo_vehiculo = p.tipo_vehiculo AND t.activo
                WHERE p.tipo_servicio = ? AND (CAST(? AS varchar) IS NULL OR p.tipo_vehiculo = ?)
                ORDER BY p.tipo_vehiculo
                """, String.class, tipoServicio, tipoVehiculo, tipoVehiculo);
        List<Map<String, Object>> r = new ArrayList<>();
        boolean nocturno = esNocturno();
        boolean lluvia = hayLluvia();
        for (String v : vehiculos) {
            BigDecimal valor = cotizar(tipoServicio, v, ruta);
            // "Incluye recargo nocturno del 15 %" / "por lluvia": la app explica por qué cuesta más
            BigDecimal recargo = nocturno ? recargoNocturnoPct(tipoServicio, v) : BigDecimal.ZERO;
            BigDecimal recargoLluvia = lluvia ? recargoLluviaPct(tipoServicio, v) : BigDecimal.ZERO;
            r.add(Map.of("tipo_servicio", tipoServicio, "tipo_vehiculo", v, "valor_estimado", valor,
                    "distancia_km", ruta.distanciaKm(), "duracion_min", ruta.duracionMin(),
                    "recargo_nocturno_pct", recargo, "recargo_lluvia_pct", recargoLluvia, "valor_maximo", maximoOfrecible(valor)));
        }
        return r;
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

    private static BigDecimal redondear(double km) {
        return BigDecimal.valueOf(km).setScale(2, RoundingMode.HALF_UP);
    }
}
