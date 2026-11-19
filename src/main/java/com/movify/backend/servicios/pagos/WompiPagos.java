package com.movify.backend.servicios.pagos;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movify.backend.comun.ReglaNegocioException;

/**
 * Cobros con Wompi (Colombia). Por defecto contra el SANDBOX (https://sandbox.wompi.co/v1): no mueve plata real.
 * Basado en la documentación de Wompi (docs.wompi.co):
 *
 *   GET  /merchants/info   (x-merchant-public-key)       → tokens de aceptación:
 *        data.presigned_acceptance.acceptance_token  y  data.presigned_personal_data_auth.acceptance_token
 *   POST /transactions     (Authorization: Bearer llave pública)
 *        { acceptance_token, accept_personal_auth, amount_in_cents, currency: "COP", signature, customer_email,
 *          reference, payment_method: { type: "NEQUI", phone_number } }
 *        Daviplata: payment_method { type: "DAVIPLATA", user_legal_id, user_legal_id_type: "CC", payment_description }
 *                   + payment_method_type: "DAVIPLATA"
 *   GET  /transactions/{id}                                → data.status: PENDING | APPROVED | DECLINED | ERROR
 *   Daviplata OTP: POST data.payment_method.extra.url_services.code_otp_validate con Bearer url_services.token,
 *                  cuerpo { "code": 123456 }.
 *
 *   Firma de integridad = SHA256(referencia + monto_en_centavos + moneda + secreto_de_integridad).
 *
 * Las llaves llegan por variables de entorno (WOMPI_*), nunca en el repositorio.
 */
public class WompiPagos implements ProveedorPagos {

    private static final Logger log = LoggerFactory.getLogger(WompiPagos.class);
    private static final long TOKENS_MS = 10 * 60 * 1000L;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String base;
    private final String llavePublica;
    private final String secretoIntegridad;

    private volatile Tokens tokens;
    private volatile List<Map<String, String>> bancos;
    private volatile long bancosLeidos;

    private record Tokens(String aceptacion, String datosPersonales, String terminos, long leidoEn) {
    }

    public WompiPagos(String base, String llavePublica, String secretoIntegridad) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.llavePublica = llavePublica;
        this.secretoIntegridad = secretoIntegridad;
    }

    @Override
    public String nombre() {
        return "wompi";
    }

    @Override
    public String terminosUrl() {
        try {
            return tokens().terminos();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public Resultado iniciar(Cobro c) {
        Tokens t = tokens();
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("acceptance_token", t.aceptacion());
        cuerpo.put("accept_personal_auth", t.datosPersonales());
        cuerpo.put("amount_in_cents", c.montoCentavos());
        cuerpo.put("currency", "COP");
        cuerpo.put("signature", firma(c.referencia(), c.montoCentavos(), "COP", secretoIntegridad));
        cuerpo.put("customer_email", c.correo());
        cuerpo.put("reference", c.referencia());
        Map<String, Object> metodo = new LinkedHashMap<>();
        switch (c.metodo()) {
            case "nequi" -> {
                metodo.put("type", "NEQUI");
                metodo.put("phone_number", c.cuenta());
            }
            case "daviplata" -> {
                metodo.put("type", "DAVIPLATA");
                metodo.put("user_legal_id", c.cuenta());
                metodo.put("user_legal_id_type", "CC");
                metodo.put("payment_description", recortar(c.descripcion(), 30));
                cuerpo.put("payment_method_type", "DAVIPLATA");
            }
            case "tarjeta" -> {
                // El token lo creó la app directo con Wompi (POST /tokens/cards): el número nunca pasa por MoviFY
                metodo.put("type", "CARD");
                metodo.put("token", c.cuenta());
                metodo.put("installments", Integer.parseInt(c.extra("cuotas") == null ? "1" : c.extra("cuotas")));
            }
            case "pse" -> {
                metodo.put("type", "PSE");
                metodo.put("user_type", "juridica".equals(c.extra("tipo_persona")) ? 1 : 0);
                metodo.put("user_legal_id_type", c.extra("tipo_documento"));
                metodo.put("user_legal_id", c.cuenta());
                metodo.put("financial_institution_code", c.extra("banco"));
                metodo.put("payment_description", recortar(c.descripcion(), 64));
                Map<String, Object> cliente = new LinkedHashMap<>();
                cliente.put("phone_number", c.extra("telefono"));
                cliente.put("full_name", c.extra("nombre"));
                cuerpo.put("customer_data", cliente);
            }
            case "bancolombia" -> {
                metodo.put("type", "BANCOLOMBIA_TRANSFER");
                metodo.put("user_type", "PERSON");
                metodo.put("payment_description", recortar(c.descripcion(), 64));
            }
            default -> throw new ReglaNegocioException(400, "Medio de pago no soportado");
        }
        cuerpo.put("payment_method", metodo);

        JsonNode r = llamar(HttpRequest.newBuilder(URI.create(base + "/transactions"))
                .header("Authorization", "Bearer " + llavePublica)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(escribir(cuerpo))), "crear la transacción");
        JsonNode d = r.path("data");
        return resultado(d, new HashMap<>(), "daviplata".equals(c.metodo()));
    }

    @Override
    public Resultado consultar(String transaccionId, Map<String, Object> datos, long creadoMs) {
        JsonNode r = llamar(HttpRequest.newBuilder(URI.create(base + "/transactions/" + transaccionId))
                .header("Authorization", "Bearer " + llavePublica).GET(), "consultar la transacción");
        JsonNode d = r.path("data");
        boolean daviplata = "DAVIPLATA".equals(d.path("payment_method_type").asText(d.path("payment_method").path("type").asText()));
        return resultado(d, datos == null ? new HashMap<>() : new HashMap<>(datos), daviplata);
    }

    @Override
    public Resultado validarOtp(String transaccionId, Map<String, Object> datos, String codigo) {
        Map<String, Object> d = datos == null ? new HashMap<>() : new HashMap<>(datos);
        if (d.get("otp_url") == null) {
            // Las URLs del OTP llegan cuando Wompi termina de crear la sesión con Daviplata
            Resultado r = consultar(transaccionId, d, 0);
            d = new HashMap<>(r.datos());
            if (r.finalizado()) return r;
        }
        String url = (String) d.get("otp_url");
        String token = (String) d.get("otp_token");
        if (url == null || token == null) {
            return new Resultado(transaccionId, "PENDING", true, "Daviplata todavía no envía el código. Espera unos segundos.", d);
        }
        HttpResponse<String> res = enviar(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"code\":" + Long.parseLong(codigo) + "}")), "validar el código");
        if (res.statusCode() == 401) {
            return new Resultado(transaccionId, "ERROR", false, "El código venció. Vuelve a intentar el pago.", d);
        }
        JsonNode body = leer(res.body());
        if (res.statusCode() == 422) {
            String estado = body.path("data").path("status").asText("PENDING");
            return new Resultado(transaccionId, estado, "PENDING".equals(estado),
                    "PENDING".equals(estado) ? "Se acabaron los intentos con ese código." : "Daviplata rechazó el pago", d);
        }
        boolean confirmado = body.path("data").path("transaction").path("steps").toString().contains("ConfirmIntention");
        if (!confirmado) {
            return new Resultado(transaccionId, "PENDING", true, "El código no es correcto. Revisa el SMS e intenta de nuevo.", d);
        }
        // Código bueno: el estado final llega al consultar la transacción
        return consultar(transaccionId, d, 0);
    }

    /** GET /pse/financial_institutions → [{ codigo, nombre }] (se guarda 1 hora). */
    @Override
    public List<Map<String, String>> bancosPse() {
        List<Map<String, String>> b = bancos;
        if (b != null && System.currentTimeMillis() - bancosLeidos < 3_600_000L) return b;
        JsonNode r = llamar(HttpRequest.newBuilder(URI.create(base + "/pse/financial_institutions"))
                .header("Authorization", "Bearer " + llavePublica).GET(), "leer los bancos de PSE");
        List<Map<String, String>> lista = new ArrayList<>();
        for (JsonNode n : r.path("data")) {
            String codigo = n.path("financial_institution_code").asText("");
            // Wompi devuelve un primer elemento "A continuación seleccione su banco" con código 0
            if (codigo.isBlank() || "0".equals(codigo)) continue;
            lista.add(Map.of("codigo", codigo, "nombre", n.path("financial_institution_name").asText(codigo)));
        }
        bancos = List.copyOf(lista);
        bancosLeidos = System.currentTimeMillis();
        return bancos;
    }

    // ───────────── Utilidades ─────────────

    private static String recortar(String s, int max) {
        String t = s == null ? "" : s.replace("'", "");
        return t.length() > max ? t.substring(0, max) : t;
    }

    static String firma(String referencia, long centavos, String moneda, String secreto) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest((referencia + centavos + moneda + secreto).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Resultado resultado(JsonNode d, Map<String, Object> datos, boolean daviplata) {
        String id = d.path("id").asText(null);
        String estado = d.path("status").asText("PENDING");
        // PSE y Bancolombia: la página del banco donde el cliente termina el pago
        String urlBanco = d.path("payment_method").path("extra").path("async_payment_url").asText(null);
        if (urlBanco != null && urlBanco.startsWith("https://")) datos.put(URL_PAGO, urlBanco);
        JsonNode servicios = d.path("payment_method").path("extra").path("url_services");
        if (servicios.isObject()) {
            String validar = servicios.path("code_otp_validate").asText(null);
            String token = servicios.path("token").asText(null);
            // Solo se le manda el token a Wompi: una URL de otro dominio se ignora
            if (validar != null && esDeWompi(validar) && token != null) {
                datos.put("otp_url", validar);
                datos.put("otp_token", token);
            }
        }
        String detalle = switch (estado) {
            case "APPROVED" -> "Pago aprobado";
            case "DECLINED" -> d.path("status_message").asText("El pago fue rechazado");
            case "ERROR" -> "No se pudo procesar el pago";
            case "VOIDED" -> "El pago fue anulado";
            default -> switch (d.path("payment_method_type").asText(d.path("payment_method").path("type").asText(""))) {
                case "DAVIPLATA" -> "Te enviamos un código por SMS. Escríbelo para pagar.";
                case "CARD" -> "Procesando el pago con tu tarjeta…";
                case "PSE", "BANCOLOMBIA_TRANSFER" -> datos.containsKey(URL_PAGO) ? "Termina el pago en la página de tu banco"
                        : "Preparando la página de tu banco…";
                default -> "Aprueba el pago en tu app Nequi";
            };
        };
        if (id == null) throw new ReglaNegocioException(502, "Wompi no devolvió la transacción");
        return new Resultado(id, estado, daviplata && "PENDING".equals(estado), detalle, datos);
    }

    static boolean esDeWompi(String url) {
        try {
            URI u = URI.create(url);
            String host = u.getHost();
            return "https".equals(u.getScheme()) && host != null && (host.equals("wompi.co") || host.endsWith(".wompi.co"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private Tokens tokens() {
        Tokens t = tokens;
        if (t != null && System.currentTimeMillis() - t.leidoEn() < TOKENS_MS) return t;
        JsonNode r = llamar(HttpRequest.newBuilder(URI.create(base + "/merchants/info"))
                .header("x-merchant-public-key", llavePublica).GET(), "leer los tokens de aceptación");
        JsonNode d = r.path("data");
        String aceptacion = d.path("presigned_acceptance").path("acceptance_token").asText(null);
        String datosPersonales = d.path("presigned_personal_data_auth").path("acceptance_token").asText(null);
        if (aceptacion == null || datosPersonales == null) {
            throw new ReglaNegocioException(502, "Wompi no entregó los tokens de aceptación");
        }
        t = new Tokens(aceptacion, datosPersonales, d.path("presigned_acceptance").path("permalink").asText(null),
                System.currentTimeMillis());
        tokens = t;
        return t;
    }

    private JsonNode llamar(HttpRequest.Builder req, String que) {
        HttpResponse<String> res = enviar(req, que);
        if (res.statusCode() >= 400) {
            JsonNode e = leer(res.body());
            log.warn("Wompi respondió {} al {}: {}", res.statusCode(), que, e.path("error").toString());
            throw new ReglaNegocioException(502, "El proveedor de pagos rechazó la operación. Intenta de nuevo o paga en efectivo.");
        }
        return leer(res.body());
    }

    private HttpResponse<String> enviar(HttpRequest.Builder req, String que) {
        try {
            return http.send(req.timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReglaNegocioException(503, "No pudimos contactar al proveedor de pagos");
        } catch (Exception e) {
            log.warn("No se pudo {} en Wompi: {}", que, e.getMessage());
            throw new ReglaNegocioException(503, "No pudimos contactar al proveedor de pagos. Intenta de nuevo o paga en efectivo.");
        }
    }

    private JsonNode leer(String texto) {
        try {
            return json.readTree(texto == null || texto.isBlank() ? "{}" : texto);
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }

    private String escribir(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
