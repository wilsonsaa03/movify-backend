package com.movify.backend.servicios.pagos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Simulador local para probar y hacer la demo sin llaves ni internet. Usa los datos de RECHAZO del sandbox de Wompi
 * ("Datos de prueba en Sandbox"), pero para que la demo funcione con datos reales, todo lo demás se APRUEBA
 * (en el sandbox de Wompi, en cambio, solo aprueban los datos de prueba: 3991111111, 4242…, código 574829).
 *
 * Nequi (el cliente "aprueba en su app"; se resuelve a los {@value #SEGUNDOS_BANCO} s):
 *   3992222222 → DECLINED · cualquier otro celular → APPROVED
 * Daviplata (código OTP de 6 números):
 *   932015 → DECLINED · 186743 → DECLINED (saldo insuficiente) · 999999 → ERROR · cualquier otro → APPROVED
 * Tarjeta (a los {@value #SEGUNDOS_TARJETA} s): 4111 1111 1111 1111 → DECLINED · cualquier otra → APPROVED.
 *   El número NUNCA llega al backend: en modo simulado la app arma un token "sim_tok_{A|D|E}_{últimos 4}".
 * PSE: banco "1" → APPROVED · banco "2" → DECLINED · otro → ERROR.
 * Botón Bancolombia: en el sandbox se elige el resultado en la página del banco; aquí se aprueba.
 *
 * Nunca se usa en producción (ProduccionSegura no arranca con PAGOS_MODO=simulado).
 */
public class SimuladorPagos implements ProveedorPagos {

    static final int SEGUNDOS_BANCO = 6;
    static final int SEGUNDOS_TARJETA = 2;
    public static final String NEQUI_APRUEBA = "3991111111";
    public static final String NEQUI_RECHAZA = "3992222222";
    public static final String OTP_APRUEBA = "574829";
    public static final String TARJETA_APRUEBA = "4242 4242 4242 4242";
    public static final String TARJETA_RECHAZA = "4111 1111 1111 1111";
    public static final String PSE_APRUEBA = "1";
    public static final String PSE_RECHAZA = "2";

    @Override
    public String nombre() {
        return "simulado";
    }

    @Override
    public String terminosUrl() {
        return null;
    }

    @Override
    public List<Map<String, String>> bancosPse() {
        return List.of(Map.of("codigo", PSE_APRUEBA, "nombre", "Banco que aprueba (prueba)"),
                Map.of("codigo", PSE_RECHAZA, "nombre", "Banco que rechaza (prueba)"));
    }

    @Override
    public Resultado iniciar(Cobro c) {
        String id = "sim-" + UUID.randomUUID();
        Map<String, Object> datos = new HashMap<>();
        datos.put("metodo", c.metodo());
        switch (c.metodo()) {
            case "daviplata" -> {
                return new Resultado(id, "PENDING", true, "Te enviamos un código por SMS. Escríbelo para pagar.", datos);
            }
            case "nequi" -> datos.put("resultado", NEQUI_RECHAZA.equals(c.cuenta()) ? "DECLINED" : "APPROVED");
            case "tarjeta" -> {
                // sim_tok_A_4242: A aprueba, D declina, E error (lo arma la app con la tarjeta de prueba)
                String[] t = c.cuenta().split("_");
                String r = t.length >= 3 ? t[2] : "E";
                datos.put("resultado", "A".equals(r) ? "APPROVED" : "D".equals(r) ? "DECLINED" : "ERROR");
                datos.put("segundos", SEGUNDOS_TARJETA);
            }
            case "pse" -> datos.put("resultado", switch (String.valueOf(c.extra("banco"))) {
                case PSE_APRUEBA -> "APPROVED";
                case PSE_RECHAZA -> "DECLINED";
                default -> "ERROR";
            });
            default -> datos.put("resultado", "APPROVED"); // bancolombia
        }
        return new Resultado(id, "PENDING", false, pendiente(c.metodo()), datos);
    }

    @Override
    public Resultado consultar(String transaccionId, Map<String, Object> datos, long creadoMs) {
        Object fin = datos == null ? null : datos.get("resultado");
        String metodo = datos == null ? "" : String.valueOf(datos.get("metodo"));
        int segundos = datos != null && datos.get("segundos") instanceof Number n ? n.intValue() : SEGUNDOS_BANCO;
        if (fin != null && System.currentTimeMillis() - creadoMs >= segundos * 1000L) {
            return new Resultado(transaccionId, String.valueOf(fin), false, detalle(metodo, String.valueOf(fin)), datos);
        }
        boolean otp = fin == null;
        return new Resultado(transaccionId, "PENDING", otp, otp ? "Escribe el código que te llegó por SMS" : pendiente(metodo), datos);
    }

    @Override
    public Resultado validarOtp(String transaccionId, Map<String, Object> datos, String codigo) {
        return switch (codigo) {
            case OTP_APRUEBA -> new Resultado(transaccionId, "APPROVED", false, "Pago aprobado", datos);
            case "932015" -> new Resultado(transaccionId, "DECLINED", false, "Daviplata rechazó el pago", datos);
            case "186743" -> new Resultado(transaccionId, "DECLINED", false, "Saldo insuficiente en Daviplata", datos);
            case "999999" -> new Resultado(transaccionId, "ERROR", false, "Daviplata no respondió", datos);
            // En el simulador cualquier otro código se acepta (en el sandbox de Wompi solo 574829)
            default -> new Resultado(transaccionId, "APPROVED", false, "Pago aprobado", datos);
        };
    }

    private static String pendiente(String metodo) {
        return switch (metodo) {
            case "tarjeta" -> "Procesando el pago con tu tarjeta…";
            case "pse" -> "Banco de prueba: el pago se resuelve en unos segundos";
            case "bancolombia" -> "Bancolombia de prueba: el pago se aprueba en unos segundos";
            default -> "Aprueba el pago en tu app Nequi";
        };
    }

    private static String detalle(String metodo, String estado) {
        String quien = switch (metodo) {
            case "tarjeta" -> "El banco de tu tarjeta";
            case "pse" -> "Tu banco";
            case "bancolombia" -> "Bancolombia";
            default -> "Nequi";
        };
        return switch (estado) {
            case "APPROVED" -> "Pago aprobado";
            case "DECLINED" -> quien + " rechazó el pago";
            default -> quien + " no pudo procesar el pago";
        };
    }
}
