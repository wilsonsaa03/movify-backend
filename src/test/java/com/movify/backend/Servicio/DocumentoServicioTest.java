package com.movify.backend.Servicio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import com.movify.backend.Comun.RecursoNoEncontradoException;
import com.movify.backend.Comun.ReglaNegocioException;

class DocumentoServicioTest {

    @TempDir
    Path dir;

    @Test
    void rechazaHtmlDisfrazado() {
        DocumentoServicio s = new DocumentoServicio(dir.toString());
        var html = new MockMultipartFile("cedula", "cedula.png", "image/png",
                "<html><script>alert(1)</script></html>".getBytes());
        assertThrows(ReglaNegocioException.class, () -> s.guardar(html, "cedula"));
    }

    @Test
    void aceptaPdfYPoneLaExtensionDelServidor() throws Exception {
        DocumentoServicio s = new DocumentoServicio(dir.toString());
        var pdf = new MockMultipartFile("soat", "soat.html", "text/html", "%PDF-1.7 contenido".getBytes());
        String nombre = s.guardar(pdf, "soat");
        assertTrue(nombre.startsWith("soat_") && nombre.endsWith(".pdf"));
        assertTrue(Files.exists(dir.resolve(nombre)));
        assertEquals("application/pdf", s.obtener(nombre).contentType());
    }

    @Test
    void noPermiteSalirDelDirectorio() {
        DocumentoServicio s = new DocumentoServicio(dir.toString());
        assertThrows(RecursoNoEncontradoException.class, () -> s.obtener("../../etc/passwd"));
        assertThrows(RecursoNoEncontradoException.class, () -> s.obtener("..%2F..%2Fetc%2Fpasswd"));
    }
}
