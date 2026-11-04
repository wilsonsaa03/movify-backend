package com.movify.backend.configuracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** La revisión de arranque frena en producción lo que en desarrollo solo avisa. */
class ProduccionSeguraTest {

    private static ProduccionSegura con(String entorno, boolean smsConsola, String cors, String codigoAdmin) {
        ProduccionSegura p = new ProduccionSegura(null);
        ReflectionTestUtils.setField(p, "entorno", entorno);
        ReflectionTestUtils.setField(p, "smsConsola", smsConsola);
        ReflectionTestUtils.setField(p, "simulacion", false);
        ReflectionTestUtils.setField(p, "cors", cors);
        ReflectionTestUtils.setField(p, "jwt", "un-secreto-largo-y-aleatorio-que-nunca-se-publico-0123456789");
        ReflectionTestUtils.setField(p, "codigoAdmin", codigoAdmin);
        ReflectionTestUtils.setField(p, "claveBd", "clave-bd-nueva");
        ReflectionTestUtils.setField(p, "claveCorreo", "");
        return p;
    }

    private static final String CORS_OK = "https://movify.ds2.eleueleo.com,https://localhost,capacitor://localhost,http://localhost";

    @Test
    void configuracionCorrectaArranca() {
        assertThat(con("produccion", false, CORS_OK, "").problemas()).isEmpty();
    }

    @Test
    void enProduccionNoArrancaConSmsEnConsola() {
        assertThatThrownBy(() -> con("produccion", true, CORS_OK, "").revisar())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SMS_CONSOLA");
    }

    @Test
    void enDesarrolloSoloAvisa() {
        con("desarrollo", true, "http://192.168.*.*:8100", "").revisar(); // no lanza
    }

    @Test
    void corsHttpOComodinEsUnProblema() {
        assertThat(con("produccion", false, "http://192.168.*.*:8100", "").problemas()).hasSize(1);
        assertThat(con("produccion", false, "*", "").problemas()).hasSize(1);
    }

    @Test
    void codigoAdminPublicadoEnGitSeDetecta() {
        // Este código estuvo escrito en el código fuente (historial público): ya no sirve
        assertThat(con("produccion", false, CORS_OK, "MOVI-ADMIN-2024-SECRET").problemas())
                .anyMatch(x -> x.contains("ADMIN_SECRET_CODE") && x.contains("historial de git"));
    }

    @Test
    void codigoAdminCortoEsUnProblema() {
        assertThat(con("produccion", false, CORS_OK, "corto123").problemas()).anyMatch(x -> x.contains("corto"));
    }
}
