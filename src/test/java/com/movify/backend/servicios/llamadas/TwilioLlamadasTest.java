package com.movify.backend.servicios.llamadas;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** El TwiML de la llamada protegida: muestra el número de MoviFY y no deja inyectar etiquetas. */
class TwilioLlamadasTest {

    @Test
    void marcaAlOtroConElNumeroDeMovifyComoIdentificador() {
        String t = TwilioLlamadas.twiml("Te comunicamos con tu conductor Carlos.", "+15005550006", "+573001234567");
        assertThat(t).startsWith("<Response><Say language=\"es-MX\">Te comunicamos con tu conductor Carlos.</Say>");
        assertThat(t).contains("<Dial callerId=\"+15005550006\" timeLimit=\"600\" timeout=\"25\"><Number>+573001234567</Number></Dial>");
        assertThat(t).endsWith("</Response>");
    }

    @Test
    void unNombreConEtiquetasNoRompeElTwiml() {
        String t = TwilioLlamadas.twiml("Hola <Dial>+1999</Dial> & \"amigos\"", "+15005550006", "+573001234567");
        assertThat(t).contains("Hola &lt;Dial&gt;+1999&lt;/Dial&gt; &amp; &quot;amigos&quot;");
        assertThat(t.split("<Dial ", -1)).hasSize(2); // un solo Dial: el de MoviFY
    }
}
