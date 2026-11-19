package com.movify.backend.tiemporeal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** Los avisos llegan solo a quien sigue ese servicio, y un cliente caído no frena a los demás. */
class TiempoRealTest {

    private static WebSocketSession sesion(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    @Test
    void soloLeLlegaAQuienSigueElServicio() throws Exception {
        TiempoReal tr = new TiempoReal();
        WebSocketSession cliente = sesion("a");
        WebSocketSession otro = sesion("b");
        tr.seguir(12L, cliente);
        tr.seguir(99L, otro);

        tr.enviarYa(12L, "mensaje", Map.of("contenido", "Ya llegué"));

        ArgumentCaptor<TextMessage> m = ArgumentCaptor.forClass(TextMessage.class);
        verify(cliente).sendMessage(m.capture());
        assertThat(m.getValue().getPayload()).contains("\"tipo\":\"mensaje\"").contains("\"servicio\":12")
                .contains("\"contenido\":\"Ya llegué\"");
        verify(otro, never()).sendMessage(any());
    }

    @Test
    void sinOyentesNoHaceNadaYAlDejarDeSeguirYaNoLlega() throws Exception {
        TiempoReal tr = new TiempoReal();
        assertThat(tr.conOyentes(5L)).isFalse();
        WebSocketSession s = sesion("a");
        tr.seguir(5L, s);
        assertThat(tr.conOyentes(5L)).isTrue();
        tr.olvidar(s);
        assertThat(tr.conOyentes(5L)).isFalse();
        tr.enviarYa(5L, "estado", Map.of("estado", "en_curso"));
        verify(s, never()).sendMessage(any());
    }

    @Test
    void unClienteQueFallaSeCierraYLosDemasSiguenRecibiendo() throws Exception {
        TiempoReal tr = new TiempoReal();
        WebSocketSession caido = sesion("a");
        WebSocketSession bien = sesion("b");
        doThrow(new IOException("se cayó")).when(caido).sendMessage(any());
        tr.seguir(7L, caido);
        tr.seguir(7L, bien);

        tr.enviarYa(7L, "ubicacion", Map.of("lat", 3.88, "lng", -77.02));
        tr.enviarYa(7L, "ubicacion", Map.of("lat", 3.89, "lng", -77.02));

        verify(caido).close();
        verify(bien, org.mockito.Mockito.times(2)).sendMessage(any());
    }
}
