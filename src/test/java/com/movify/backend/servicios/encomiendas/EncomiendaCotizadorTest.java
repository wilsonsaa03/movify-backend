package com.movify.backend.servicios.encomiendas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.movify.backend.comun.ReglaNegocioException;
import com.movify.backend.servicios.TarifaServicio;
import com.movify.backend.servicios.encomiendas.EncomiendaCotizador.Bulto;
import com.movify.backend.servicios.encomiendas.EncomiendaCotizador.Capacidad;
import com.movify.backend.servicios.encomiendas.EncomiendaCotizador.Parametros;
import com.movify.backend.servicios.encomiendas.EncomiendaCotizador.Resultado;
import com.movify.backend.servicios.encomiendas.EncomiendaDtos.BultoRequest;

/** El cálculo del precio y las reglas de capacidad, sin base de datos (parámetros de prueba fijos). */
class EncomiendaCotizadorTest {

    private static final TarifaServicio.Ruta RUTA = new TarifaServicio.Ruta(new BigDecimal("3.20"), 12);
    private static final BigDecimal RUTA_VALOR = new BigDecimal("6000");

    private static Parametros params(String proteccionPct) {
        return new Parametros(bd("5000"), bd("5"), bd("600"), bd("1000"), bd("1500"), bd(proteccionPct), bd("2000000"), 10,
                Map.of("moto", new Capacidad(bd("15"), bd("50"), 2), "taxi", new Capacidad(bd("30"), bd("100"), 3)));
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static Bulto bulto(String tipo, String kg) {
        return new Bulto(tipo, bd(kg), null, null, null, null, false, null);
    }

    private static Bulto conMedidas(String tipo, String kg, String l, String a, String h) {
        return new Bulto(tipo, bd(kg), bd(l), bd(a), bd(h), null, false, null);
    }

    @Test
    void paqueteLivianoCuestaSoloLaRuta() {
        Resultado r = EncomiendaCotizador.calcular(List.of(bulto("pequeno", "2")), "moto", RUTA_VALOR, RUTA, params("0"));
        assertTrue(r.disponible());
        assertEquals(1, r.desglose().size());
        assertEquals(0, r.total().compareTo(RUTA_VALOR));
        assertEquals("Tarifa de la ruta", r.desglose().get(0).concepto());
        assertEquals("3.2 km · 12 min", r.desglose().get(0).detalle());
    }

    @Test
    void pesoVolumetricoManda_cuandoElPaqueteEsLivianoPeroGrande() {
        // 40 × 40 × 40 = 64 000 cm³ / 5000 = 12,8 kg volumétricos (pesa 3 kg reales)
        Resultado r = EncomiendaCotizador.calcular(List.of(conMedidas("pequeno", "3", "40", "40", "40")), "taxi", RUTA_VALOR,
                RUTA, params("0"));
        assertEquals(0, r.pesoVolumetricoKg().compareTo(bd("12.80")));
        assertEquals(0, r.pesoFacturableKg().compareTo(bd("12.80")));
        // 12,8 − 5 incluidos = 7,8 → 8 kg empezados × $600 = $4 800
        assertEquals(0, r.total().compareTo(bd("10800")));
        assertEquals("8 kg sobre 5 kg incluidos", r.desglose().get(1).detalle());
    }

    @Test
    void bultosAdicionalesYFragilesSeCobranPorUnidad() {
        Bulto fragil = new Bulto("pequeno", bd("1"), null, null, null, null, true, "Vasos");
        Resultado r = EncomiendaCotizador.calcular(List.of(fragil, bulto("sobre", "0.5"), fragil), "moto", RUTA_VALOR, RUTA,
                params("0"));
        // 2 adicionales × 1000 + 2 frágiles × 1500
        assertEquals(0, r.total().compareTo(bd("11000")));
        assertEquals(3, r.desglose().size());
        assertEquals("2 paquetes frágiles", r.desglose().get(2).detalle());
    }

    @Test
    void proteccionSoloSiElAdminLaHabilita() {
        Bulto declarado = new Bulto("pequeno", bd("1"), null, null, null, bd("150000"), false, null);
        Resultado sin = EncomiendaCotizador.calcular(List.of(declarado), "moto", RUTA_VALOR, RUTA, params("0"));
        assertEquals(1, sin.desglose().size());
        Resultado con = EncomiendaCotizador.calcular(List.of(declarado), "moto", RUTA_VALOR, RUTA, params("1"));
        // 1 % de 150 000 = 1 500
        assertEquals(0, con.total().compareTo(bd("7500")));
        assertEquals("1 % de $150.000", con.desglose().get(1).detalle());
    }

    @Test
    void elMedianoNoCabeEnLaMotoPeroSiEnElTaxi() {
        List<Bulto> b = List.of(bulto("mediano", "4"));
        Resultado moto = EncomiendaCotizador.calcular(b, "moto", RUTA_VALOR, RUTA, params("0"));
        assertFalse(moto.disponible());
        assertEquals("Un paquete mediano no cabe en la moto", moto.motivo());
        assertTrue(EncomiendaCotizador.calcular(b, "taxi", RUTA_VALOR, RUTA, params("0")).disponible());
    }

    @Test
    void ladoMayorYPesoMaximoPorVehiculo() {
        Resultado largo = EncomiendaCotizador.calcular(List.of(conMedidas("pequeno", "2", "60", "20", "10")), "moto",
                RUTA_VALOR, RUTA, params("0"));
        assertEquals("Un paquete mide 60 cm y en la moto caben hasta 50 cm", largo.motivo());
        Resultado pesado = EncomiendaCotizador.calcular(List.of(bulto("pequeno", "10"), bulto("pequeno", "8")), "moto",
                RUTA_VALOR, RUTA, params("0"));
        assertEquals("Son 18 kg facturables y la moto lleva hasta 15 kg", pesado.motivo());
        assertNull(EncomiendaCotizador.calcular(List.of(bulto("pequeno", "10"), bulto("pequeno", "8")), "taxi", RUTA_VALOR,
                RUTA, params("0")).motivo());
    }

    @Test
    void validaCantidadDeBultosYValorDeclarado() {
        Parametros p = params("0");
        assertThrows(ReglaNegocioException.class, () -> EncomiendaCotizador.validarConjunto(List.of(), p));
        assertThrows(ReglaNegocioException.class,
                () -> EncomiendaCotizador.validarConjunto(Collections.nCopies(11, bulto("sobre", "0.2")), p));
        Bulto caro = new Bulto("pequeno", bd("1"), null, null, null, bd("2000001"), false, null);
        ReglaNegocioException e = assertThrows(ReglaNegocioException.class,
                () -> EncomiendaCotizador.validarConjunto(List.of(caro), p));
        assertEquals("El valor declarado no puede pasar de $2.000.000", e.getMessage());
    }

    @Test
    void redondeaHaciaArribaAlCien() {
        assertEquals(0, EncomiendaCotizador.redondear(bd("4801")).compareTo(bd("4900")));
        assertEquals(0, EncomiendaCotizador.redondear(bd("4800")).compareTo(bd("4800")));
    }

    // ───────────── Bultos: lo que manda la app y lo que manda la web ─────────────

    @Test
    void laWebMandaUnSoloPaquete() {
        List<Bulto> b = Bultos.normalizar(null, "documento", null, "0,5", true, null, "Contrato");
        assertEquals(1, b.size());
        assertEquals("sobre", b.get(0).tipo());
        assertEquals(0, b.get(0).pesoKg().compareTo(bd("0.5")));
        assertTrue(b.get(0).fragil());
        assertEquals("Contrato", b.get(0).descripcion());
        assertEquals("pequeno", Bultos.tamanoMayor(b));
    }

    @Test
    void medidasIncompletasSeRechazan() {
        BultoRequest malo = new BultoRequest("pequeno", bd("2"), bd("30"), null, bd("10"), null, false, null);
        ReglaNegocioException e = assertThrows(ReglaNegocioException.class,
                () -> Bultos.normalizar(List.of(malo), null, null, null, null, null, null));
        assertTrue(e.getMessage().startsWith("El paquete 1"));
    }

    @Test
    void elTamanoGuardadoEsElDelBultoMasGrande() {
        List<Bulto> b = Bultos.normalizar(List.of(
                new BultoRequest("sobre", bd("0.2"), null, null, null, null, false, null),
                new BultoRequest("mediano", bd("4"), bd("40"), bd("30"), bd("20"), bd("0"), true, "Ropa")),
                null, null, null, null, null, null);
        assertEquals("mediano", Bultos.tamanoMayor(b));
        assertNull(b.get(1).valorDeclarado()); // 0 = sin valor declarado
    }
}
