package com.movify.backend.servicios.simulacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Motor de simulación con rutas REALES de Buenaventura (respuestas de OSRM guardadas en
 * src/test/resources/simulacion, una por servicio). No necesita internet ni base de datos.
 *
 * Se comprueba que el vehículo:
 *  - nunca se sale de la polilínea (a cada décima de segundo está sobre la calle),
 *  - avanza tramo por tramo en orden (pasa por todas las esquinas, no salta),
 *  - no va en línea recta de origen a destino,
 *  - mira hacia donde va (rumbo) y llega exacto al final, con distancia y ETA que bajan a 0,
 *  - respeta pausar, reanudar, reiniciar, finalizar y el cambio de velocidad.
 */
class SimulacionRutasTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    record Osrm(List<double[]> geometria, double distancia, List<double[]> paradas) {
    }

    static Osrm cargar(String archivo) throws Exception {
        try (InputStream in = SimulacionRutasTest.class.getResourceAsStream("/simulacion/" + archivo)) {
            JsonNode raiz = JSON.readTree(in);
            JsonNode r = raiz.path("routes").path(0);
            List<double[]> g = new ArrayList<>();
            for (JsonNode c : r.path("geometry").path("coordinates")) g.add(new double[] { c.get(1).asDouble(), c.get(0).asDouble() });
            List<double[]> w = new ArrayList<>();
            for (JsonNode p : raiz.path("waypoints")) w.add(new double[] { p.path("location").get(1).asDouble(), p.path("location").get(0).asDouble() });
            return new Osrm(g, r.path("distance").asDouble(), w);
        }
    }

    @ParameterizedTest(name = "{0}: sigue la calle de principio a fin a {2} km/h")
    @CsvSource({
            "MOTOCICLETA (transporte), moto_transporte.json, 32",
            "TAXI (transporte),        taxi_transporte.json, 28",
            "ENCOMIENDA (recogida → entrega), encomienda.json, 32",
            "DOMICILIO (tienda → casa), domicilio.json, 32" })
    void elVehiculoSigueLaPolilinea(String servicio, String archivo, double kmh) throws Exception {
        Osrm osrm = cargar(archivo);
        RutaSimulada ruta = new RutaSimulada(osrm.geometria());

        // La distancia medida con nuestra fórmula cuadra con la de OSRM (menos de 1 %)
        assertThat(ruta.largo()).isCloseTo(osrm.distancia(), within(osrm.distancia() * 0.01));

        long marca = 1_000_000;
        long pasoMs = 100;
        double[] inicio = osrm.geometria().get(0);
        double[] fin = osrm.geometria().get(osrm.geometria().size() - 1);
        int tramoAnterior = 0;
        double recorridoAnterior = -1;
        double etaAnterior = Double.MAX_VALUE;
        double desvioMaximoDeLaRecta = 0;
        long ahora = marca;
        RutaSimulada.Posicion p;
        int muestras = 0;
        do {
            double s = SimulacionViajes.recorridoEn(SimulacionViajes.CORRIENDO, 0, marca, kmh, ruta.largo(), ahora);
            p = ruta.en(s);
            // 1. Siempre sobre la calle
            assertThat(ruta.distanciaA(p.lat(), p.lng())).as("%s: fuera de la ruta en %.1f m", servicio, s).isLessThan(0.05);
            // 2. Avanza en orden: nunca retrocede de tramo ni de metros
            assertThat(p.tramo()).isGreaterThanOrEqualTo(tramoAnterior);
            assertThat(p.recorrido()).isGreaterThan(recorridoAnterior);
            // 3. Distancia restante y ETA bajan
            double eta = (ruta.largo() - s) / (kmh / 3.6);
            assertThat(eta).isLessThanOrEqualTo(etaAnterior);
            desvioMaximoDeLaRecta = Math.max(desvioMaximoDeLaRecta, distanciaARecta(p.lat(), p.lng(), inicio, fin));
            tramoAnterior = p.tramo();
            recorridoAnterior = p.recorrido();
            etaAnterior = eta;
            ahora += pasoMs;
            muestras++;
        } while (p.recorrido() < ruta.largo());

        // 4. Llegó exacto al último vértice y recorrió TODOS los tramos
        assertThat(RutaSimulada.metros(new double[] { p.lat(), p.lng() }, fin)).isLessThan(0.01);
        assertThat(tramoAnterior).isEqualTo(ruta.vertices() - 2);
        // 5. No es una línea recta: en algún momento se separa más de 40 m de la recta origen → destino
        assertThat(desvioMaximoDeLaRecta).as("%s: parece línea recta", servicio).isGreaterThan(40);
        assertThat(ruta.largo()).isGreaterThan(RutaSimulada.metros(inicio, fin) * 1.05);
        // 6. Tardó lo que dice la velocidad (± un paso)
        double segundosEsperados = ruta.largo() / (kmh / 3.6);
        assertThat(muestras * pasoMs / 1000.0).isCloseTo(segundosEsperados, within(0.3));

        System.out.printf("%-34s %6.0f m · %3d vértices · %5.0f s a %.0f km/h · se aleja %4.0f m de la recta%n",
                servicio, ruta.largo(), ruta.vertices(), segundosEsperados, kmh, desvioMaximoDeLaRecta);
    }

    @ParameterizedTest(name = "{0}: pasa por cada vértice de la calle")
    @CsvSource({ "MOTOCICLETA, moto_transporte.json", "TAXI, taxi_transporte.json", "ENCOMIENDA, encomienda.json",
            "DOMICILIO, domicilio.json" })
    void pasaPorTodasLasEsquinas(String servicio, String archivo) throws Exception {
        RutaSimulada ruta = new RutaSimulada(cargar(archivo).geometria());
        for (int i = 0; i < ruta.vertices(); i++) {
            RutaSimulada.Posicion p = ruta.en(ruta.acumuladoEn(i));
            assertThat(RutaSimulada.metros(new double[] { p.lat(), p.lng() }, ruta.vertice(i)))
                    .as("%s: vértice %d", servicio, i).isLessThan(0.01);
        }
    }

    @Test
    void encomiendaPasaPorElPuntoDeRecogidaAntesDeEntregar() throws Exception {
        Osrm osrm = cargar("encomienda.json");
        RutaSimulada ruta = new RutaSimulada(osrm.geometria());
        double[] recogida = osrm.paradas().get(1);
        RutaSimulada.Proyeccion p = ruta.proyectar(recogida[0], recogida[1]);
        assertThat(p.distancia()).isLessThan(1.0);
        assertThat(p.recorrido()).isBetween(1.0, ruta.largo() - 1.0);
    }

    @Test
    void elRumboApuntaHaciaDondeVa() throws Exception {
        RutaSimulada ruta = new RutaSimulada(cargar("taxi_transporte.json").geometria());
        int revisados = 0;
        for (int i = 0; i < ruta.vertices() - 1; i++) {
            double a = ruta.acumuladoEn(i), b = ruta.acumuladoEn(i + 1);
            if (b - a < 20) continue; // tramos cortos: el rumbo se suaviza con la esquina
            double medio = (a + b) / 2;
            double[] va = ruta.vertice(i), vb = ruta.vertice(i + 1);
            double esperado = RutaSimulada.rumbo(va[0], va[1], vb[0], vb[1]);
            double diferencia = Math.abs(((ruta.rumboEn(medio) - esperado) + 540) % 360 - 180);
            assertThat(diferencia).isLessThan(1.0);
            revisados++;
        }
        assertThat(revisados).isPositive();
        assertThat(RutaSimulada.rumbo(0, 0, 1, 0)).isCloseTo(0, within(0.001)); // norte
        assertThat(RutaSimulada.rumbo(0, 0, 0, 1)).isCloseTo(90, within(0.001)); // este
        assertThat(RutaSimulada.rumbo(0, 0, -1, 0)).isCloseTo(180, within(0.001)); // sur
        assertThat(RutaSimulada.rumbo(0, 0, 0, -1)).isCloseTo(270, within(0.001)); // oeste
    }

    @Test
    void controlesPausarReanudarVelocidadYFinal() {
        double largo = 1000;
        long t0 = 0;
        // Corriendo a 36 km/h = 10 m/s
        assertThat(SimulacionViajes.recorridoEn("corriendo", 0, t0, 36, largo, 5_000)).isCloseTo(50, within(1e-9));
        // Pausada: no se mueve aunque pase el tiempo
        assertThat(SimulacionViajes.recorridoEn("pausada", 50, 5_000, 36, largo, 60_000)).isEqualTo(50);
        // Lista (reiniciada): quieta en 0 hasta que se inicie
        assertThat(SimulacionViajes.recorridoEn("lista", 0, 0, 36, largo, 60_000)).isZero();
        // Reanudada desde 50 m a 72 km/h (20 m/s) 2 s después
        assertThat(SimulacionViajes.recorridoEn("corriendo", 50, 10_000, 72, largo, 12_000)).isCloseTo(90, within(1e-9));
        // Nunca pasa del final
        assertThat(SimulacionViajes.recorridoEn("corriendo", 0, 0, 36, largo, 10_000_000)).isEqualTo(largo);
        // Llegó: queda en el final
        assertThat(SimulacionViajes.recorridoEn("llego", largo, 0, 36, largo, 99_000)).isEqualTo(largo);
    }

    @Test
    void rutaConVerticesRepetidosNoSeRompe() {
        RutaSimulada r = new RutaSimulada(List.of(new double[] { 3.88, -77.03 }, new double[] { 3.88, -77.03 },
                new double[] { 3.881, -77.03 }));
        assertThat(r.vertices()).isEqualTo(2);
        assertThat(r.en(-5).recorrido()).isZero();
        assertThat(r.en(1e9).recorrido()).isEqualTo(r.largo());
    }

    /** Distancia (m) de un punto a la recta origen → destino. */
    private static double distanciaARecta(double lat, double lng, double[] a, double[] b) {
        return new RutaSimulada(List.of(a, b)).distanciaA(lat, lng);
    }
}
