package com.movify.backend.comun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContenidoProhibidoTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Una caja de zapatos con ropa",
            "Documentos de la notaría",
            "Almuerzo: arroz, pescado y una Coca-Cola",
            "Huevos pericos para el desayuno",
            "Un machete nuevo para la finca",
            "Pistola de silicona y barras",
            "Cartuchos de tinta para la impresora",
            "Granadas y granadillas del mercado",
            "Papeles para la señora Hurtado",
            "Repuestos de moto",
            "Comprobante de pago",
            "Medicamentos de la droguería" })
    void dejaPasarEnviosNormales(String texto) {
        assertEquals(Optional.empty(), ContenidoProhibido.detectar(texto), texto);
    }

    @Test
    void detectaDrogasArmasYDinero() {
        assertEquals(Optional.of("drogas"), ContenidoProhibido.detectar("Un paquete de marihuana"));
        assertEquals(Optional.of("drogas"), ContenidoProhibido.detectar("100 gramos de COCAÍNA"));
        assertEquals(Optional.of("armas"), ContenidoProhibido.detectar("una pistola 9 mm"));
        assertEquals(Optional.of("municiones"), ContenidoProhibido.detectar("caja de balas"));
        assertEquals(Optional.of("explosivos"), ContenidoProhibido.detectar("pólvora para diciembre"));
        assertEquals(Optional.of("dinero en efectivo"), ContenidoProhibido.detectar("Sobre con dinero"));
        assertEquals(Optional.of("objetos robados o de contrabando"), ContenidoProhibido.detectar("celular robado"));
    }

    @Test
    void noSeEnganaConTrucosSimples() {
        assertEquals(Optional.of("drogas"), ContenidoProhibido.detectar("c0ca1na"));
        assertEquals(Optional.of("drogas"), ContenidoProhibido.detectar("m.a.r.i.h.u.a.n.a"));
        assertEquals(Optional.of("armas"), ContenidoProhibido.detectar("P I S T O L A"));
    }

    @Test
    void rechazaConMensajeClaro() {
        ReglaNegocioException e = assertThrows(ReglaNegocioException.class,
                () -> ContenidoProhibido.exigirPermitido("Ropa", "un revolver"));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("armas"));
    }
}
