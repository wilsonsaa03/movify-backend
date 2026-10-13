package com.movify.backend.autenticacion;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClaveYNombreTest {

    @Test
    void rechazaClavesFacilesDeAdivinar() {
        for (String c : new String[] { "1234", "4321", "0000", "7777", "2580", "6789", "abcd", "123" }) {
            assertTrue(AutenticacionServicio.claveDebil(c), c);
        }
    }

    @Test
    void aceptaClavesNormales() {
        for (String c : new String[] { "2468", "9051", "1357", "3092" }) {
            assertFalse(AutenticacionServicio.claveDebil(c), c);
        }
    }

    @Test
    void parteElNombreCompleto() {
        assertArrayEquals(new String[] { "Ana", "Riascos" }, AutenticacionServicio.partirNombre("ana riascos"));
        assertArrayEquals(new String[] { "Ana", "María Riascos" }, AutenticacionServicio.partirNombre("ANA maría riascos"));
        assertArrayEquals(new String[] { "Ana María", "Riascos Mina" }, AutenticacionServicio.partirNombre("ana maría riascos mina"));
        assertArrayEquals(new String[] { "Ana", null }, AutenticacionServicio.partirNombre("  ana "));
        assertEquals("Luis Ángel", AutenticacionServicio.capitalizar("  luis   ÁNGEL "));
    }
}
