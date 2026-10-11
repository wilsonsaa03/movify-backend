package com.movify.backend.Servicio;

import static com.movify.backend.Modelo.Servicio.EstadoServicio.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.movify.backend.Servicio.MaquinaEstados.Actor;

class MaquinaEstadosTest {

    @Test
    void noSePuedeCancelarUnViajeFinalizado() {
        for (Actor a : Actor.values()) {
            assertFalse(MaquinaEstados.permitido(a, FINALIZADO, CANCELADO));
            assertFalse(MaquinaEstados.permitido(a, CANCELADO, FINALIZADO));
        }
    }

    @Test
    void flujoNormalDelConductor() {
        assertTrue(MaquinaEstados.permitido(Actor.CONDUCTOR, PENDIENTE, ACEPTADO));
        assertTrue(MaquinaEstados.permitido(Actor.CONDUCTOR, ACEPTADO, EN_CAMINO));
        assertTrue(MaquinaEstados.permitido(Actor.CONDUCTOR, EN_CAMINO, FINALIZADO));
        assertTrue(MaquinaEstados.permitido(Actor.CONDUCTOR, ACEPTADO, PAQUETE_RECOGIDO));
        assertTrue(MaquinaEstados.permitido(Actor.CONDUCTOR, PAQUETE_RECOGIDO, FINALIZADO));
    }

    @Test
    void elConductorNoFinalizaSinHaberIniciado() {
        assertFalse(MaquinaEstados.permitido(Actor.CONDUCTOR, PENDIENTE, FINALIZADO));
        assertFalse(MaquinaEstados.permitido(Actor.CONDUCTOR, ACEPTADO, FINALIZADO));
    }

    @Test
    void elClienteSoloCancelaAntesDeArrancar() {
        assertTrue(MaquinaEstados.permitido(Actor.CLIENTE, PENDIENTE, CANCELADO));
        assertTrue(MaquinaEstados.permitido(Actor.CLIENTE, ACEPTADO, CANCELADO));
        assertFalse(MaquinaEstados.permitido(Actor.CLIENTE, EN_VIAJE, CANCELADO));
        assertFalse(MaquinaEstados.permitido(Actor.CLIENTE, PENDIENTE, ACEPTADO));
        assertFalse(MaquinaEstados.permitido(Actor.CLIENTE, EN_VIAJE, FINALIZADO));
    }

    @Test
    void elPersonalSoloCancela() {
        assertTrue(MaquinaEstados.permitido(Actor.PERSONAL, EN_VIAJE, CANCELADO));
        assertFalse(MaquinaEstados.permitido(Actor.PERSONAL, EN_VIAJE, FINALIZADO));
    }
}
