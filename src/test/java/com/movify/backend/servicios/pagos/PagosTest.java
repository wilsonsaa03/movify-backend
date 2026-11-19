package com.movify.backend.servicios.pagos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.movify.backend.comun.ReglaNegocioException;

/** Pagos con Nequi/Daviplata: simulador (mismos datos que el sandbox de Wompi), firma y validaciones. Sin base de datos. */
class PagosTest {

    private final SimuladorPagos sim = new SimuladorPagos();

    private ProveedorPagos.Cobro cobro(String metodo, String cuenta) {
        return cobro(metodo, cuenta, Map.of());
    }

    private ProveedorPagos.Cobro cobro(String metodo, String cuenta, Map<String, String> extra) {
        return new ProveedorPagos.Cobro("MVY-1-x", 1_250_000, metodo, cuenta, "a@b.co", "MoviFY servicio 1", extra);
    }

    @Test
    void nequiQuedaPendienteHastaQueElClienteApruebaYLuegoDaElResultadoDelSandbox() {
        ProveedorPagos.Resultado r = sim.iniciar(cobro("nequi", SimuladorPagos.NEQUI_APRUEBA));
        assertThat(r.estado()).isEqualTo("PENDING");
        assertThat(r.requiereOtp()).isFalse();
        // El celular no se guarda: solo el resultado que dará la prueba
        assertThat(r.datos()).doesNotContainValue(SimuladorPagos.NEQUI_APRUEBA);
        long ahora = System.currentTimeMillis();
        assertThat(sim.consultar(r.transaccionId(), r.datos(), ahora).estado()).isEqualTo("PENDING");
        long hace = ahora - (SimuladorPagos.SEGUNDOS_BANCO + 1) * 1000L;
        assertThat(sim.consultar(r.transaccionId(), r.datos(), hace).estado()).isEqualTo("APPROVED");

        ProveedorPagos.Resultado rechazo = sim.iniciar(cobro("nequi", SimuladorPagos.NEQUI_RECHAZA));
        assertThat(sim.consultar(rechazo.transaccionId(), rechazo.datos(), hace).estado()).isEqualTo("DECLINED");
        // Un celular real (no el de prueba) también aprueba: así la demo funciona con el número del cliente
        ProveedorPagos.Resultado otro = sim.iniciar(cobro("nequi", "3153654978"));
        assertThat(sim.consultar(otro.transaccionId(), otro.datos(), hace).estado()).isEqualTo("APPROVED");
    }

    @Test
    void daviplataPideCodigoYResponderComoElSandbox() {
        ProveedorPagos.Resultado r = sim.iniciar(cobro("daviplata", "1134568019"));
        assertThat(r.estado()).isEqualTo("PENDING");
        assertThat(r.requiereOtp()).isTrue();
        Map<String, Object> d = r.datos();
        // Código equivocado: sigue pendiente y se puede reintentar
        // En el simulador cualquier código aprueba, salvo los de rechazo del sandbox
        assertThat(sim.validarOtp(r.transaccionId(), d, "123456").estado()).isEqualTo("APPROVED");
        assertThat(sim.validarOtp(r.transaccionId(), d, SimuladorPagos.OTP_APRUEBA).estado()).isEqualTo("APPROVED");
        assertThat(sim.validarOtp(r.transaccionId(), d, "932015").estado()).isEqualTo("DECLINED");
        assertThat(sim.validarOtp(r.transaccionId(), d, "186743").detalle()).contains("Saldo insuficiente");
        assertThat(sim.validarOtp(r.transaccionId(), d, "999999").estado()).isEqualTo("ERROR");
    }

    @Test
    void firmaDeIntegridadDeWompiEsSha256DeReferenciaMontoMonedaYSecreto() {
        // Valor calculado aparte con Python: sha256("MVY-12-abcd1234" + "1250000" + "COP" + "test_integrity_prueba")
        assertThat(WompiPagos.firma("MVY-12-abcd1234", 1_250_000, "COP", "test_integrity_prueba"))
                .isEqualTo("1fa03be00b7a2f6c92590d99ec870a2a4fd31c9cf0cf1cd213c145941f60df89");
    }

    @Test
    void elTokenDelOtpSoloSeLeMandaAWompi() {
        assertThat(WompiPagos.esDeWompi("https://api.wompi.co/v1/daviplata/validate")).isTrue();
        assertThat(WompiPagos.esDeWompi("https://sandbox.wompi.co/v1/x")).isTrue();
        assertThat(WompiPagos.esDeWompi("http://sandbox.wompi.co/v1/x")).isFalse(); // sin https
        assertThat(WompiPagos.esDeWompi("https://wompi.co.atacante.com/x")).isFalse();
        assertThat(WompiPagos.esDeWompi("https://atacante.com/wompi.co")).isFalse();
        assertThat(WompiPagos.esDeWompi("no es una url")).isFalse();
    }

    @Test
    void validaCelularNequiYCedula() {
        assertThat(PagoServicio.celularNequi("+57 399 111 1111")).isEqualTo("3991111111");
        assertThat(PagoServicio.celularNequi("399-111-1111")).isEqualTo("3991111111");
        assertThatThrownBy(() -> PagoServicio.celularNequi("6021234567")).isInstanceOf(ReglaNegocioException.class);
        assertThatThrownBy(() -> PagoServicio.celularNequi("12345")).isInstanceOf(ReglaNegocioException.class);
        assertThat(PagoServicio.cedula("1.134.568.019")).isEqualTo("1134568019");
        assertThatThrownBy(() -> PagoServicio.cedula("12")).isInstanceOf(ReglaNegocioException.class);
    }

    @Test
    void elMontoIncluyeLaCompraDelDomicilio() {
        Map<String, Object> viaje = new HashMap<>();
        viaje.put("valor_final", new BigDecimal("8500.00"));
        assertThat(PagoServicio.monto(viaje)).isEqualByComparingTo("8500");
        Map<String, Object> domicilio = new HashMap<>(viaje);
        domicilio.put("requiere_compra", true);
        domicilio.put("valor_compra_real", new BigDecimal("27400.00"));
        assertThat(PagoServicio.monto(domicilio)).isEqualByComparingTo("35900");
        domicilio.put("requiere_compra", false); // ya estaba pago: solo el domicilio
        assertThat(PagoServicio.monto(domicilio)).isEqualByComparingTo("8500");
    }
    @Test
    void tarjetaPseYBancolombiaSeResuelvenComoElSandbox() {
        long hace = System.currentTimeMillis() - (SimuladorPagos.SEGUNDOS_BANCO + 1) * 1000L;
        ProveedorPagos.Resultado t = sim.iniciar(cobro("tarjeta", "sim_tok_A_4242", Map.of("cuotas", "1")));
        assertThat(t.estado()).isEqualTo("PENDING");
        assertThat(sim.consultar(t.transaccionId(), t.datos(), hace).estado()).isEqualTo("APPROVED");
        ProveedorPagos.Resultado d = sim.iniciar(cobro("tarjeta", "sim_tok_D_1111", Map.of()));
        assertThat(sim.consultar(d.transaccionId(), d.datos(), hace).estado()).isEqualTo("DECLINED");

        ProveedorPagos.Resultado pse = sim.iniciar(cobro("pse", "1134568019", Map.of("banco", SimuladorPagos.PSE_APRUEBA)));
        assertThat(sim.consultar(pse.transaccionId(), pse.datos(), hace).estado()).isEqualTo("APPROVED");
        ProveedorPagos.Resultado pse2 = sim.iniciar(cobro("pse", "1134568019", Map.of("banco", SimuladorPagos.PSE_RECHAZA)));
        assertThat(sim.consultar(pse2.transaccionId(), pse2.datos(), hace).estado()).isEqualTo("DECLINED");

        ProveedorPagos.Resultado b = sim.iniciar(cobro("bancolombia", "", Map.of()));
        assertThat(sim.consultar(b.transaccionId(), b.datos(), hace).estado()).isEqualTo("APPROVED");
        assertThat(sim.bancosPse()).extracting(m -> m.get("codigo")).containsExactly("1", "2");
    }

    @Test
    void elNumeroDeLaTarjetaNuncaEntraComoToken() {
        assertThat(PagoServicio.tokenTarjeta("tok_test_12345_abcdef")).isEqualTo("tok_test_12345_abcdef");
        assertThat(PagoServicio.tokenTarjeta("sim_tok_A_4242")).isEqualTo("sim_tok_A_4242");
        assertThatThrownBy(() -> PagoServicio.tokenTarjeta("4242424242424242")).isInstanceOf(ReglaNegocioException.class);
        assertThatThrownBy(() -> PagoServicio.tokenTarjeta("sim_tok_A_4242424242424242")).isInstanceOf(ReglaNegocioException.class);
        assertThat(PagoServicio.ultimos4("4242")).isEqualTo("4242");
        assertThat(PagoServicio.ultimos4("4242424242424242")).isEqualTo("****");
    }
}
