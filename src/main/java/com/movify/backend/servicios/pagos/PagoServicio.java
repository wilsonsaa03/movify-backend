package com.movify.backend.servicios.pagos;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movify.backend.comun.AccesoDenegadoException;
import com.movify.backend.comun.Datos;
import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.comun.ReglaNegocioException;
import com.movify.backend.notificaciones.NotificacionServicio;
import com.movify.backend.seguridad.SesionActual;
import com.movify.backend.tiemporeal.TiempoReal;

/**
 * Pago digital del servicio con Nequi o Daviplata (Wompi sandbox o simulador). El efectivo sigue siendo
 * la opción por defecto y el respaldo: si el pago digital no se aprueba, el cliente le paga al conductor.
 *
 * Flujo: el cliente elige Nequi/Daviplata al pedir (servicios.metodo_pago) → al terminar el servicio la app
 * le muestra "Pagar $X" → Nequi: aprueba en su app; Daviplata: escribe el código del SMS → aprobado.
 * El conductor recibe un aviso cuando el pago se aprueba (para no cobrarle en efectivo).
 *
 * Modo (app.pagos.modo / PAGOS_MODO): apagado (solo efectivo) | simulado (local) | wompi (llaves WOMPI_*).
 * Nunca se guardan el celular completo ni la cédula: solo los últimos 4 dígitos para el recibo.
 */
@Service
public class PagoServicio {

    private static final Logger log = LoggerFactory.getLogger(PagoServicio.class);
    public static final String EFECTIVO = "efectivo";
    public static final String NEQUI = "nequi";
    public static final String DAVIPLATA = "daviplata";
    public static final String TARJETA = "tarjeta";
    public static final String PSE = "pse";
    public static final String BANCOLOMBIA = "bancolombia";
    /** Medios digitales, en el orden en que la app los muestra. */
    public static final List<String> DIGITALES = List.of(NEQUI, DAVIPLATA, TARJETA, PSE, BANCOLOMBIA);
    private static final Map<String, String> NOMBRES = Map.of(NEQUI, "Nequi", DAVIPLATA, "Daviplata", TARJETA, "tarjeta",
            PSE, "PSE", BANCOLOMBIA, "Bancolombia");
    /** Un cobro de Nequi que nadie aprobó en este tiempo se da por vencido. */
    private static final int MINUTOS_VENCE = 10;
    private static final int MAX_INTENTOS_POR_SERVICIO = 5;
    private static final int MAX_INTENTOS_OTP = 5;

    private final JdbcTemplate db;
    private final SesionActual sesion;
    private final TiempoReal tiempoReal;
    private final NotificacionServicio avisos;
    private final ProveedorPagos proveedor;
    private final String modo;
    private final boolean deprueba;
    private final String correoPorDefecto;
    private final String wompiUrl;
    private final String llavePublica;
    private final ObjectMapper json = new ObjectMapper();

    public PagoServicio(JdbcTemplate db, SesionActual sesion, TiempoReal tiempoReal, NotificacionServicio avisos,
            @Value("${app.pagos.modo:apagado}") String modo,
            @Value("${app.pagos.wompi.url:https://sandbox.wompi.co/v1}") String wompiUrl,
            @Value("${app.pagos.wompi.llave-publica:}") String llavePublica,
            @Value("${app.pagos.wompi.secreto-integridad:}") String secretoIntegridad,
            @Value("${app.pagos.correo-por-defecto:pagos@movify.co}") String correoPorDefecto) {
        this.db = db;
        this.sesion = sesion;
        this.tiempoReal = tiempoReal;
        this.avisos = avisos;
        this.correoPorDefecto = correoPorDefecto;
        this.wompiUrl = wompiUrl;
        this.llavePublica = llavePublica;
        String m = modo == null ? "apagado" : modo.trim().toLowerCase(Locale.ROOT);
        if ("wompi".equals(m) && (llavePublica.isBlank() || secretoIntegridad.isBlank())) {
            log.warn("PAGOS_MODO=wompi sin WOMPI_LLAVE_PUBLICA o WOMPI_SECRETO_INTEGRIDAD: los pagos digitales quedan apagados");
            m = "apagado";
        }
        this.modo = m;
        this.proveedor = switch (m) {
            case "simulado" -> new SimuladorPagos();
            case "wompi" -> new WompiPagos(wompiUrl, llavePublica, secretoIntegridad);
            default -> null;
        };
        this.deprueba = "simulado".equals(m) || ("wompi".equals(m) && (wompiUrl.contains("sandbox") || llavePublica.startsWith("pub_test_")));
        if (proveedor != null) log.info("Pagos digitales: {}{}", m, deprueba ? " (modo de prueba, sin plata real)" : "");
    }

    // ───────────── Catálogo ─────────────

    /** GET /api/catalogos/formas-pago: lo que la app puede ofrecer y, en modo de prueba, los datos para probar. */
    public Map<String, Object> formas() {
        Map<String, Object> r = new LinkedHashMap<>();
        List<String> metodos = new ArrayList<>(List.of(EFECTIVO));
        if (proveedor != null) metodos.addAll(DIGITALES);
        r.put("metodos", metodos);
        r.put("modo", modo);
        r.put("de_prueba", deprueba);
        // Tarjeta: la app crea el token directo con Wompi (la llave pública es pública por diseño; la privada nunca sale)
        if ("wompi".equals(modo)) r.put("wompi", Map.of("url", wompiUrl, "llave_publica", llavePublica));
        if (deprueba) {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("nequi_aprueba", SimuladorPagos.NEQUI_APRUEBA);
            p.put("nequi_rechaza", SimuladorPagos.NEQUI_RECHAZA);
            p.put("daviplata_codigo", SimuladorPagos.OTP_APRUEBA);
            p.put("tarjeta_aprueba", SimuladorPagos.TARJETA_APRUEBA);
            p.put("tarjeta_rechaza", SimuladorPagos.TARJETA_RECHAZA);
            p.put("pse_banco_aprueba", SimuladorPagos.PSE_APRUEBA);
            p.put("pse_banco_rechaza", SimuladorPagos.PSE_RECHAZA);
            r.put("pruebas", p);
        }
        return r;
    }

    /** GET /api/catalogos/bancos-pse */
    public List<Map<String, String>> bancosPse() {
        if (proveedor == null) return List.of();
        return proveedor.bancosPse();
    }

    /** Al pedir: efectivo, o Nequi/Daviplata si están habilitados. La encomienda que paga quien recibe es en efectivo. */
    public String validarMetodo(String metodo, String tipoServicio, Boolean pagaDestinatario) {
        String m = metodo == null || metodo.isBlank() ? EFECTIVO : metodo.trim().toLowerCase(Locale.ROOT);
        if (EFECTIVO.equals(m)) return m;
        if (!DIGITALES.contains(m)) {
            throw new ReglaNegocioException(400, "Forma de pago no válida: efectivo, Nequi, Daviplata, tarjeta, PSE o Bancolombia.");
        }
        if (proveedor == null) throw new ReglaNegocioException(400, "Por ahora en MoviFY solo se paga en efectivo.");
        if ("encomienda".equals(tipoServicio) && Boolean.TRUE.equals(pagaDestinatario)) {
            throw new ReglaNegocioException(400, "Si paga quien recibe, el pago es en efectivo.");
        }
        return m;
    }

    // ───────────── Consulta ─────────────

    /** Lo que se muestra dentro del detalle del servicio (sin consultar al proveedor). */
    public Map<String, Object> resumen(Long servicioId) {
        return db.queryForList("""
                SELECT id, metodo, estado, monto, detalle, cuenta_mascara, actualizado_en FROM pagos
                WHERE servicio_id = ? ORDER BY creado_en DESC LIMIT 1
                """, servicioId).stream().findFirst().orElse(null);
    }

    /** GET /api/servicios/{id}/pago. Si hay un cobro pendiente, se pregunta al proveedor cómo va. */
    public Map<String, Object> estado(Long servicioId) {
        Map<String, Object> s = servicio(servicioId);
        boolean soyCliente = Objects.equals(Datos.aLong(s.get("cliente_id")), sesion.usuarioId());
        Map<String, Object> pago = resumenCompleto(servicioId);
        if (pago != null && "pendiente".equals(pago.get("estado"))) {
            refrescar(pago, s);
            pago = resumenCompleto(servicioId);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("servicio_id", servicioId);
        r.put("metodo_pago", s.get("metodo_pago"));
        r.put("monto", monto(s));
        r.put("modo", modo);
        r.put("de_prueba", deprueba);
        r.put("terminos_url", proveedor == null ? null : proveedor.terminosUrl());
        r.put("pago", publico(pago));
        boolean aprobado = pago != null && "aprobado".equals(pago.get("estado"));
        r.put("puede_pagar", soyCliente && proveedor != null && "finalizado".equals(s.get("estado")) && !aprobado
                && !Boolean.TRUE.equals(s.get("paga_destinatario")));
        if (deprueba) r.put("pruebas", formas().get("pruebas"));
        return r;
    }

    // ───────────── Cobrar ─────────────

    /** El cliente paga un servicio terminado. cuenta = celular Nequi o cédula Daviplata. */
    public Map<String, Object> iniciar(Long servicioId, String metodo, String cuenta, Map<String, String> extra) {
        if (proveedor == null) throw new ReglaNegocioException(400, "Los pagos con Nequi o Daviplata no están disponibles.");
        Map<String, Object> s = servicio(servicioId);
        Long yo = sesion.usuarioId();
        if (!Objects.equals(Datos.aLong(s.get("cliente_id")), yo)) {
            throw new AccesoDenegadoException("Solo quien pidió el servicio puede pagarlo");
        }
        if (!"finalizado".equals(s.get("estado"))) throw new ReglaNegocioException(400, "El servicio todavía no ha terminado.");
        if (Boolean.TRUE.equals(s.get("paga_destinatario"))) {
            throw new ReglaNegocioException(400, "Esta encomienda la paga quien recibe, en efectivo.");
        }
        String m = metodo == null ? "" : metodo.trim().toLowerCase(Locale.ROOT);
        if (!DIGITALES.contains(m)) throw new ReglaNegocioException(400, "Elige un medio de pago digital.");
        Map<String, String> x = extra == null ? new HashMap<>() : new HashMap<>(extra);
        String numero = switch (m) {
            case NEQUI -> celularNequi(cuenta);
            case DAVIPLATA -> cedula(cuenta);
            case TARJETA -> {
                String c = x.get("cuotas") == null ? "1" : x.get("cuotas").trim();
                if (!c.matches("\\d{1,2}") || Integer.parseInt(c) < 1 || Integer.parseInt(c) > 36) {
                    throw new ReglaNegocioException(400, "Las cuotas van de 1 a 36.");
                }
                x.put("cuotas", c);
                yield tokenTarjeta(cuenta);
            }
            case PSE -> datosPse(cuenta, x, yo);
            default -> ""; // Bancolombia: todo se hace en la página del banco
        };

        Map<String, Object> vivo = resumenCompleto(servicioId);
        if (vivo != null && "aprobado".equals(vivo.get("estado"))) throw new ReglaNegocioException(409, "Este servicio ya está pago.");
        if (vivo != null && "pendiente".equals(vivo.get("estado"))) return estado(servicioId);
        Integer intentos = db.queryForObject("SELECT count(*) FROM pagos WHERE servicio_id = ?", Integer.class, servicioId);
        if (intentos != null && intentos >= MAX_INTENTOS_POR_SERVICIO) {
            throw new ReglaNegocioException(429, "Ya intentaste pagar varias veces. Págale al conductor en efectivo.");
        }

        BigDecimal monto = monto(s);
        long centavos = monto.movePointRight(2).longValueExact();
        String referencia = "MVY-" + servicioId + "-" + UUID.randomUUID().toString().substring(0, 8);
        String mascara = switch (m) {
            case DAVIPLATA -> "CC ***" + numero.substring(numero.length() - 4);
            case PSE -> x.get("tipo_documento") + " ***" + numero.substring(numero.length() - 4);
            case TARJETA -> "**** " + ultimos4(x.get("ultimos4"));
            case BANCOLOMBIA -> "Bancolombia";
            default -> "***" + numero.substring(numero.length() - 4);
        };
        Long pagoId;
        try {
            pagoId = db.queryForObject("""
                    INSERT INTO pagos (servicio_id, cliente_id, metodo, proveedor, monto, referencia, cuenta_mascara, detalle)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'Iniciando el pago') RETURNING id
                    """, Long.class, servicioId, yo, m, proveedor.nombre(), monto, referencia, mascara);
        } catch (DuplicateKeyException e) {
            return estado(servicioId); // otro toque ya lo inició
        }
        String correo = db.queryForObject("SELECT email FROM usuarios WHERE id = ?", String.class, yo);
        try {
            ProveedorPagos.Resultado r = proveedor.iniciar(new ProveedorPagos.Cobro(referencia, centavos, m, numero,
                    correo == null || correo.isBlank() ? correoPorDefecto : correo, "MoviFY servicio " + servicioId, x));
            guardar(pagoId, r, s);
            log.info("Pago {} del servicio {} iniciado con {} ({}): {}", pagoId, servicioId, m, proveedor.nombre(), r.estado());
        } catch (ReglaNegocioException e) {
            db.update("UPDATE pagos SET estado = 'error', detalle = ? WHERE id = ?", Datos.recortar(e.getMessage(), 200), pagoId);
            throw e;
        }
        return estado(servicioId);
    }

    /** Daviplata: el cliente escribe el código de 6 números que le llegó por SMS. */
    public Map<String, Object> otp(Long servicioId, String codigo) {
        if (proveedor == null) throw new ReglaNegocioException(400, "Los pagos digitales no están disponibles.");
        if (codigo == null || !codigo.trim().matches("\\d{6}")) throw new ReglaNegocioException(400, "El código tiene 6 números.");
        Map<String, Object> s = servicio(servicioId);
        if (!Objects.equals(Datos.aLong(s.get("cliente_id")), sesion.usuarioId())) {
            throw new AccesoDenegadoException("Solo quien pidió el servicio puede pagarlo");
        }
        Map<String, Object> p = resumenCompleto(servicioId);
        if (p == null || !"pendiente".equals(p.get("estado")) || !Boolean.TRUE.equals(p.get("requiere_otp"))) {
            throw new ReglaNegocioException(409, "No hay un pago esperando código.");
        }
        int intentos = ((Number) p.get("intentos_otp")).intValue();
        if (intentos >= MAX_INTENTOS_OTP) {
            db.update("UPDATE pagos SET estado = 'error', detalle = 'Demasiados códigos incorrectos' WHERE id = ?", p.get("id"));
            throw new ReglaNegocioException(429, "Demasiados códigos incorrectos. Págale al conductor en efectivo o intenta de nuevo.");
        }
        db.update("UPDATE pagos SET intentos_otp = intentos_otp + 1 WHERE id = ?", p.get("id"));
        ProveedorPagos.Resultado r = proveedor.validarOtp((String) p.get("transaccion_id"), datos(p), codigo.trim());
        guardar(Datos.aLong(p.get("id")), r, s);
        return estado(servicioId);
    }

    /** El cliente prefiere pagar en efectivo: el cobro pendiente se cancela. */
    public Map<String, Object> cancelar(Long servicioId) {
        Map<String, Object> s = servicio(servicioId);
        if (!Objects.equals(Datos.aLong(s.get("cliente_id")), sesion.usuarioId())) {
            throw new AccesoDenegadoException("Solo quien pidió el servicio puede cambiar el pago");
        }
        Boolean pagado = db.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pagos WHERE servicio_id = ? AND estado = 'aprobado')", Boolean.class, servicioId);
        if (Boolean.TRUE.equals(pagado)) throw new ReglaNegocioException(400, "Este servicio ya está pagado.");
        int n = db.update("""
                UPDATE pagos SET estado = 'cancelado', detalle = 'Pagará en efectivo'
                WHERE servicio_id = ? AND estado = 'pendiente'
                """, servicioId);
        // Queda como pago en efectivo: la app deja de pedirle que pague y el conductor sabe que le cobra a él
        // (también si nunca intentó pagar o el pago digital fue rechazado)
        int m = db.update("UPDATE servicios SET metodo_pago = 'efectivo' WHERE id = ? AND metodo_pago <> 'efectivo'", servicioId);
        if (n > 0 || m > 0) tiempoReal.publicar(servicioId, "pago", Map.of("estado", "cancelado"));
        return estado(servicioId);
    }

    // ───────────── Internos ─────────────

    private void refrescar(Map<String, Object> p, Map<String, Object> s) {
        OffsetDateTime creado = (OffsetDateTime) db.queryForObject("SELECT creado_en FROM pagos WHERE id = ?", OffsetDateTime.class,
                p.get("id"));
        if (creado != null && creado.isBefore(OffsetDateTime.now().minusMinutes(MINUTOS_VENCE))) {
            db.update("UPDATE pagos SET estado = 'error', detalle = 'El pago venció sin aprobarse' WHERE id = ? AND estado = 'pendiente'",
                    p.get("id"));
            return;
        }
        if (p.get("transaccion_id") == null) return;
        try {
            ProveedorPagos.Resultado r = proveedor.consultar((String) p.get("transaccion_id"), datos(p),
                    creado == null ? 0 : creado.toInstant().toEpochMilli());
            if (!r.finalizado()) {
                // Sigue pendiente: solo se guardan los datos nuevos del proveedor (p. ej. las URLs del OTP),
                // sin pisar el mensaje que ve el cliente ("El código no es correcto"...)
                db.update("UPDATE pagos SET datos_proveedor = CAST(? AS jsonb) WHERE id = ? AND estado = 'pendiente'",
                        escribir(r.datos()), p.get("id"));
                return;
            }
            guardar(Datos.aLong(p.get("id")), r, s);
        } catch (ReglaNegocioException e) {
            log.debug("No se pudo consultar el pago {}: {}", p.get("id"), e.getMessage());
        }
    }

    private void guardar(Long pagoId, ProveedorPagos.Resultado r, Map<String, Object> s) {
        String nuevo = switch (r.estado()) {
            case "APPROVED" -> "aprobado";
            case "DECLINED" -> "rechazado";
            case "ERROR" -> "error";
            case "VOIDED" -> "cancelado";
            default -> "pendiente";
        };
        String antes = db.queryForObject("SELECT estado FROM pagos WHERE id = ?", String.class, pagoId);
        db.update("""
                UPDATE pagos SET estado = ?, detalle = ?, requiere_otp = ?, transaccion_id = COALESCE(?, transaccion_id),
                                 datos_proveedor = CAST(? AS jsonb)
                WHERE id = ? AND estado = 'pendiente'
                """, nuevo, Datos.recortar(r.detalle(), 200), r.requiereOtp(), r.transaccionId(), escribir(r.datos()), pagoId);
        if (Objects.equals(antes, nuevo)) return;
        Long servicioId = Datos.aLong(s.get("id"));
        tiempoReal.publicar(servicioId, "pago", Map.of("estado", nuevo));
        if ("aprobado".equals(nuevo)) {
            Long conductor = Datos.aLong(s.get("conductor_id"));
            String que = NOMBRES.getOrDefault(db.queryForObject("SELECT metodo FROM pagos WHERE id = ?", String.class, pagoId),
                    "la app");
            if (conductor != null) {
                avisos.avisar(conductor, servicioId, NotificacionServicio.ESTADO, "pago",
                        "Pago recibido por " + que, "El cliente ya pagó " + pesos(monto(s)) + ". No le cobres en efectivo.");
            }
        }
    }

    private Map<String, Object> servicio(Long servicioId) {
        Map<String, Object> s = db.queryForList("""
                SELECT s.id, s.cliente_id, s.conductor_id, s.estado, s.tipo_servicio, s.metodo_pago, s.valor_final,
                       s.valor_estimado, d.requiere_compra, d.valor_compra_real, d.descuento, e.paga_destinatario
                FROM servicios s
                LEFT JOIN servicio_domicilio d ON d.servicio_id = s.id
                LEFT JOIN servicio_encomienda e ON e.servicio_id = s.id
                WHERE s.id = ?
                """, servicioId).stream().findFirst().orElseThrow(() -> new RecursoNoEncontradoException("Servicio no encontrado"));
        Long yo = sesion.usuarioId();
        if (!Objects.equals(Datos.aLong(s.get("cliente_id")), yo) && !Objects.equals(Datos.aLong(s.get("conductor_id")), yo)
                && !sesion.esPersonal()) {
            throw new AccesoDenegadoException("No puedes ver el pago de este servicio");
        }
        return s;
    }

    /** Lo que se cobra: el servicio y, si el domiciliario compró algo, lo que dice la factura. */
    static BigDecimal monto(Map<String, Object> s) {
        BigDecimal v = s.get("valor_final") instanceof BigDecimal b ? b
                : s.get("valor_estimado") instanceof BigDecimal b2 ? b2 : BigDecimal.ZERO;
        if (Boolean.TRUE.equals(s.get("requiere_compra")) && s.get("valor_compra_real") instanceof BigDecimal c) v = v.add(c);
        // V24: cupón del domicilio con catálogo (simulado: lo asume MoviFY)
        if (s.get("descuento") instanceof BigDecimal d) v = v.subtract(d);
        return v.max(BigDecimal.ZERO);
    }

    private Map<String, Object> resumenCompleto(Long servicioId) {
        return db.queryForList("""
                SELECT id, metodo, proveedor, estado, monto, detalle, cuenta_mascara, requiere_otp, intentos_otp,
                       transaccion_id, datos_proveedor::text AS datos_proveedor, actualizado_en
                FROM pagos WHERE servicio_id = ? ORDER BY creado_en DESC LIMIT 1
                """, servicioId).stream().findFirst().orElse(null);
    }

    /** Sin transacción ni datos del proveedor (los tokens del OTP nunca salen del backend); solo la página del banco. */
    private Map<String, Object> publico(Map<String, Object> p) {
        if (p == null) return null;
        Map<String, Object> r = new LinkedHashMap<>();
        for (String k : List.of("id", "metodo", "proveedor", "estado", "monto", "detalle", "cuenta_mascara", "requiere_otp",
                "actualizado_en")) {
            r.put(k, p.get(k));
        }
        r.put("url_pago", "pendiente".equals(p.get("estado")) ? datos(p).get(ProveedorPagos.URL_PAGO) : null);
        return r;
    }

    private Map<String, Object> datos(Map<String, Object> p) {
        Object d = p.get("datos_proveedor");
        if (d == null) return new HashMap<>();
        try {
            return json.readValue(String.valueOf(d), new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private String escribir(Map<String, Object> d) {
        try {
            return json.writeValueAsString(d == null ? Map.of() : d);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 3001234567, +57 300 123 4567 → 3001234567. */
    static String celularNequi(String texto) {
        String d = texto == null ? "" : texto.replaceAll("\\D", "");
        if (d.length() == 12 && d.startsWith("57")) d = d.substring(2);
        if (!d.matches("3\\d{9}")) throw new ReglaNegocioException(400, "Escribe el celular de Nequi (10 números, empieza por 3).");
        return d;
    }

    /** El token lo crea la app con Wompi (tok_...) o, en modo simulado, sim_tok_{A|D|E}_{últimos 4}. Nunca el número. */
    static String tokenTarjeta(String texto) {
        String t = texto == null ? "" : texto.trim();
        if (!t.matches("(tok_[A-Za-z0-9_\\-]{6,120})|(sim_tok_[ADE]_\\d{4})")) {
            throw new ReglaNegocioException(400, "No pudimos leer la tarjeta. Revisa los datos e intenta de nuevo.");
        }
        return t;
    }

    static String ultimos4(String texto) {
        String d = texto == null ? "" : texto.replaceAll("\\D", "");
        return d.length() == 4 ? d : "****";
    }

    /** PSE: tipo de persona, documento y banco; Wompi también pide nombre y celular (salen de la cuenta). */
    private String datosPse(String documento, Map<String, String> x, Long usuarioId) {
        String tipoPersona = "juridica".equals(x.get("tipo_persona")) ? "juridica" : "natural";
        String tipoDoc = x.getOrDefault("tipo_documento", "CC").trim().toUpperCase(Locale.ROOT);
        if (!List.of("CC", "NIT").contains(tipoDoc)) throw new ReglaNegocioException(400, "El documento debe ser CC o NIT.");
        String banco = Datos.vacioANull(x.get("banco"));
        if (banco == null || !banco.matches("[A-Za-z0-9]{1,10}")) throw new ReglaNegocioException(400, "Elige tu banco.");
        String doc = documento == null ? "" : documento.replaceAll("\\D", "");
        if (!doc.matches("\\d{5,12}")) throw new ReglaNegocioException(400, "Escribe el número de documento (sin puntos).");
        Map<String, Object> u = db.queryForMap(
                "SELECT trim(nombres || ' ' || COALESCE(apellidos, '')) AS nombre, telefono FROM usuarios WHERE id = ?", usuarioId);
        String tel = u.get("telefono") == null ? "" : String.valueOf(u.get("telefono")).replaceAll("\\D", "");
        if (tel.startsWith("57") && tel.length() == 12) tel = tel.substring(2);
        x.put("tipo_persona", tipoPersona);
        x.put("tipo_documento", tipoDoc);
        x.put("banco", banco);
        x.put("nombre", String.valueOf(u.get("nombre")));
        x.put("telefono", tel);
        return doc;
    }

    static String cedula(String texto) {
        String d = texto == null ? "" : texto.replaceAll("\\D", "");
        if (!d.matches("\\d{5,10}")) throw new ReglaNegocioException(400, "Escribe tu número de cédula (sin puntos).");
        return d;
    }

    private static String pesos(BigDecimal v) {
        return "$" + String.format(Locale.ROOT, "%,d", v.longValue()).replace(',', '.');
    }
}
