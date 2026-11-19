package com.movify.backend.servicios.simulacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tope de tiempo de la simulación (pruebas): el vehículo hace cada tramo en máximo N segundos por la MISMA ruta,
 * pero el tiempo que ve la gente sale de la velocidad realista. Sin base de datos.
 */
class SimulacionTopeTiempoTest {

    @Test
    void conTopeDeUnMinutoElTramoDuraComoMucho60Segundos() {
        // 3,2 km: a 32 km/h serían 6 minutos; con el tope, 192 km/h
        double v = SimulacionViajes.velocidadConTope(32, 3200, 60);
        assertThat(v).isEqualTo(192.0);
        assertThat(3200 / (v / 3.6)).isLessThanOrEqualTo(60.001);
        // Una ruta larga (15 km) también cabe en el minuto
        double larga = SimulacionViajes.velocidadConTope(32, 15_000, 60);
        assertThat(15_000 / (larga / 3.6)).isLessThanOrEqualTo(60.001);
    }

    @Test
    void siYaVaMasRapidoQueElTopeNoLoFrena() {
        // 300 m a 32 km/h tardan ~34 s: no hace falta subir la velocidad
        assertThat(SimulacionViajes.velocidadConTope(32, 300, 60)).isEqualTo(32.0);
    }

    @Test
    void sinTopeQuedaLaVelocidadElegida() {
        assertThat(SimulacionViajes.velocidadConTope(28, 10_000, 0)).isEqualTo(28.0);
    }

    @Test
    void elTiempoQueSeMuestraEsElRealistaAunqueElVehiculoVayaRapido() {
        RutaSimulada.Posicion p = new RutaSimulada(List.of(new double[] { 3.88, -77.03 }, new double[] { 3.89, -77.03 })).en(0);
        // Le faltan 3,2 km yendo a 192 km/h (llega en 60 s), pero la pantalla dice 6 minutos (a 32 km/h)
        var e = new SimulacionViajes.Estado(1, 2, "destino", "corriendo", 1, 3200, 0, 192, 32, p);
        assertThat(e.etaSegundos()).isCloseTo(360, within(0.5));
        // Datos viejos sin velocidad realista: se usa la del vehículo
        var viejo = new SimulacionViajes.Estado(1, 2, "destino", "corriendo", 1, 3200, 0, 32, 0, p);
        assertThat(viejo.etaSegundos()).isCloseTo(360, within(0.5));
    }
}
