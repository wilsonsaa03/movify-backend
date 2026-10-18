package com.movify.backend.comun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class CelularYArchivosTest {

    @Test
    void normalizaCelularesColombianos() {
        assertEquals("+573001234567", Celular.normalizar("300 123 4567"));
        assertEquals("+573001234567", Celular.normalizar("+57 300-123-4567"));
        assertEquals("+573001234567", Celular.normalizar("573001234567"));
        assertNull(Celular.normalizar("6012345678"));   // fijo, no celular
        assertNull(Celular.normalizar("30012345"));     // incompleto
        assertEquals("+57 300 123 4567", Celular.visible("+573001234567"));
        assertEquals("+576022401234", Celular.normalizarContacto("602 240 1234"));
    }

    @Test
    void reconoceElTipoPorLosPrimerosBytes() {
        assertEquals("pdf", ArchivoServicio.extensionPorFirma("%PDF-1.7".getBytes()));
        assertEquals("png", ArchivoServicio.extensionPorFirma(new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A }));
        assertEquals("jpg", ArchivoServicio.extensionPorFirma(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0 }));
        assertEquals("webp", ArchivoServicio.extensionPorFirma("RIFF0000WEBP".getBytes()));
        assertNull(ArchivoServicio.extensionPorFirma("<html><script>".getBytes()));
    }

    @Test
    void reconoceNotasDeVozYNoLasConfundeConFotos() {
        byte[] webm = { 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0 };
        assertEquals("webm", ArchivoServicio.extensionAudio(webm));
        assertEquals("ogg", ArchivoServicio.extensionAudio("OggS\0\2\0\0".getBytes()));
        assertEquals("m4a", ArchivoServicio.extensionAudio("\0\0\0\u0018ftypM4A ".getBytes()));
        assertNull(ArchivoServicio.extensionAudio("%PDF-1.7".getBytes()));
        // Un audio nunca pasa como foto o documento
        assertNull(ArchivoServicio.extensionPorFirma(webm));
    }
}
