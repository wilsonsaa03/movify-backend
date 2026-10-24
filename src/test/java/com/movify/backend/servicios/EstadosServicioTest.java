package com.movify.backend.servicios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.movify.backend.comun.ReglaNegocioException;

class EstadosServicioTest {

    @Test
    void traduceLosNombresViejosDeLaWeb() {
        assertEquals("asignado", EstadosServicio.normalizar("ACEPTADO"));
        assertEquals("en_origen", EstadosServicio.normalizar("LLEGO_AL_ORIGEN"));
        assertEquals("en_curso", EstadosServicio.normalizar("EN_VIAJE"));
        assertEquals("en_curso", EstadosServicio.normalizar("PAQUETE_RECOGIDO"));
        assertEquals("finalizado", EstadosServicio.normalizar("FINALIZADO"));
        assertEquals("rechazar", EstadosServicio.normalizar("RECHAZADO"));
        assertEquals(EstadosServicio.SIN_CAMBIO, EstadosServicio.normalizar("EN_CAMINO_AL_USUARIO"));
    }

    @Test
    void aceptaLosCodigosNuevos() {
        assertEquals("en_curso", EstadosServicio.normalizar("en_curso"));
        assertEquals("cancelado", EstadosServicio.normalizar("cancelado"));
        assertThrows(ReglaNegocioException.class, () -> EstadosServicio.normalizar("volando"));
    }

    @Test
    void recorreLosPasosIntermediosDelConductor() {
        assertEquals(List.of("en_origen", "en_curso", "finalizado"), EstadosServicio.pasos("asignado", "finalizado"));
        assertEquals(List.of("en_curso"), EstadosServicio.pasos("en_origen", "en_curso"));
        assertEquals(List.of("cancelado"), EstadosServicio.pasos("en_curso", "cancelado"));
    }

    @Test
    void devuelveElEstadoQueEntiendeLaWeb() {
        assertEquals("PENDIENTE", EstadosServicio.legado("transporte", "buscando"));
        assertEquals("EN_VIAJE", EstadosServicio.legado("transporte", "en_curso"));
        assertEquals("PAQUETE_RECOGIDO", EstadosServicio.legado("encomienda", "en_curso"));
        assertEquals("CANCELADO", EstadosServicio.legado("domicilio", "expirado"));
    }

    @Test
    void tipoDeServicio() {
        assertEquals("transporte", EstadosServicio.tipoServicio(null));
        assertEquals("domicilio", EstadosServicio.tipoServicio("DOMICILIO"));
        assertEquals("encomienda", EstadosServicio.tipoServicio("Encomienda"));
    }

    @Test
    void tamanoDelPaqueteDeLaWeb() {
        assertEquals("pequeno", EncomiendaServicio.tamano(null, "documento"));
        assertEquals("pequeno", EncomiendaServicio.tamano(null, "caja-pequeña"));
        assertEquals("mediano", EncomiendaServicio.tamano(null, "caja-mediana"));
        assertEquals("grande", EncomiendaServicio.tamano("grande", null));
    }

    @Test
    void categoriaDelDomicilio() {
        assertEquals("farmacia", ViajeServicio.categoriaDomicilio(" Farmacia "));
        assertEquals(null, ViajeServicio.categoriaDomicilio(""));
        assertEquals(null, ViajeServicio.categoriaDomicilio(null));
        org.junit.jupiter.api.Assertions.assertThrows(com.movify.backend.comun.ReglaNegocioException.class,
                () -> ViajeServicio.categoriaDomicilio("licores"));
    }
}
