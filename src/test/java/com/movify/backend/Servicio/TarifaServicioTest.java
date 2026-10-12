package com.movify.backend.Servicio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;

class TarifaServicioTest {

    @Test
    void horaPicoUsaLaHoraLocalRecibida() {
        double normal = TarifaServicio.calcular(5, false, LocalTime.of(12, 0));
        double pico = TarifaServicio.calcular(5, false, LocalTime.of(8, 0));
        double noche = TarifaServicio.calcular(5, false, LocalTime.of(23, 0));
        assertTrue(pico > normal);
        assertTrue(noche > normal);
    }

    @Test
    void redondeaACentenas() {
        assertEquals(0, TarifaServicio.calcular(3.37, true, LocalTime.NOON) % 100);
    }

    @Test
    void distanciaCeroCobraLaBase() {
        assertEquals(2500, TarifaServicio.calcular(0, false, LocalTime.NOON));
    }

    @Test
    void haversineBuenaventura() {
        // ~1,1 km entre dos puntos del centro
        double km = TarifaServicio.haversineKm(3.8821, -77.0253, 3.8920, -77.0253);
        assertTrue(km > 1.0 && km < 1.2, "km=" + km);
    }
}
