package com.movify.backend.servicios;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movify.backend.autenticacion.sms.SmsEnviador;
import com.movify.backend.comun.AccesoDenegadoException;
import com.movify.backend.comun.Celular;
import com.movify.backend.comun.ContenidoProhibido;
import com.movify.backend.comun.Datos;
import com.movify.backend.comun.DespuesDelCommit;
import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.comun.ReglaNegocioException;
import com.movify.backend.notificaciones.NotificacionServicio;
import com.movify.backend.seguridad.SesionActual;
import com.movify.backend.servicios.ServicioDtos.CambioEstadoRequest;
import com.movify.backend.servicios.ServicioDtos.SolicitudRequest;
import com.movify.backend.servicios.encomiendas.Bultos;
import com.movify.backend.servicios.encomiendas.EncomiendaCotizador;
import com.movify.backend.servicios.encomiendas.EncomiendaCotizador.Bulto;
import com.movify.backend.servicios.encomiendas.EncomiendaDtos.NovedadRequest;

/**
 * Lo propio de la encomienda: bultos, remitente y destinatario, número de guía, rastreo público,
 * novedades de recogida y entrega, y el PIN de 4 dígitos que el destinatario le da al conductor al recibir.
 * El ciclo de estados es el mismo de todos los servicios (ViajeServicio).
 */
@Service
public class EncomiendaServicio {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** Tope de servicio_encomienda.peso_kg (CHECK de la BD): peso real total. */
    private static final BigDecimal PESO_MAX_KG = new BigDecimal("30");
    /** Crockford base32 sin I, L, O, U: se dicta por teléfono sin confusiones. */
    private static final char[] ALFABETO_GUIA = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final Set<String> ACTIVOS = Set.of("asignado", "en_origen", "en_curso");

    /** Texto de cada novedad para el cliente, el destinatario y el rastreo público. */
    static final Map<String, String> TITULO_NOVEDAD = Map.of(
            "destinatario_ausente", "No encontramos a quien recibe",
            "remitente_ausente", "No encontramos a quien envía",
            "direccion_incorrecta", "La dirección no coincide",
            "sin_acceso", "No se pudo llegar al lugar",
            "rechazado", "Quien recibe no aceptó el paquete",
            "paquete_danado", "Se reportó un daño en el paquete",
            "retraso", "Hay un retraso en el envío",
            "otro", "Novedad en el envío");

    private static final Logger log = LoggerFactory.getLogger(EncomiendaServicio.class);

    private final JdbcTemplate db;
    private final SmsEnviador sms;
    private final EncomiendaCotizador cotizador;
    private final SesionActual sesion;
    private final NotificacionServicio avisos;
    private final ObjectMapper json = new ObjectMapper();

    @Value("${app.frontend.url:https://movify.ds2.eleueleo.com}")
    private String urlWeb;

    public EncomiendaServicio(JdbcTemplate db, SmsEnviador sms, EncomiendaCotizador cotizador, SesionActual sesion,
            NotificacionServicio avisos) {
        this.db = db;
        this.sms = sms;
        this.cotizador = cotizador;
        this.sesion = sesion;
        this.avisos = avisos;
    }

    /** Bultos ya validados y la cotización con la que se cobra (la misma que vio el cliente). */
    public record Preparada(List<Bulto> bultos, EncomiendaCotizador.Resultado cotizacion) {
    }

    /** Antes de crear el servicio: arma los bultos, verifica que quepan en el vehículo y calcula el precio. */
    Preparada preparar(SolicitudRequest s, String vehiculo, TarifaServicio.Ruta ruta) {
        List<Bulto> bultos = Bultos.normalizar(s.paquetes(), s.tipoPaquete(), s.tamano(), s.pesoKg(), s.fragil(),
                s.valorDeclarado(), s.descripcion());
        EncomiendaCotizador.Resultado r = cotizador.cotizar(bultos, vehiculo, ruta);
        if (!r.disponible()) throw new ReglaNegocioException(400, r.motivo());
        if (r.pesoRealKg().compareTo(PESO_MAX_KG) > 0) {
            throw new ReglaNegocioException(400, "Por ahora llevamos hasta " + PESO_MAX_KG + " kg por encomienda");
        }
        return new Preparada(bultos, r);
    }

    /** @return el PIN de 4 dígitos que el destinatario le dará al conductor al recibir. */
    String crear(Long servicioId, SolicitudRequest s, Long clienteId, Preparada p) {
        Map<String, Object> cli = db.queryForMap(
                "SELECT trim(nombres || ' ' || COALESCE(apellidos, '')) AS nombre, telefono FROM usuarios WHERE id = ?",
                clienteId);
        String remitente = ViajeServicio.firstNonBlank(s.nombreRemitente(), Datos.texto(cli.get("nombre")));
        String telRemitente = Celular.normalizarContacto(
                ViajeServicio.firstNonBlank(s.telefonoRemitente(), Datos.texto(cli.get("telefono"))));
        String telDestinatario = Celular.normalizarContacto(s.telefonoDestinatario());
        if (telRemitente == null) throw new ReglaNegocioException(400, "Escribe un teléfono válido para quien envía");
        if (Datos.vacioANull(s.nombreDestinatario()) == null) throw new ReglaNegocioException(400, "¿A quién le llega el paquete?");
        if (telDestinatario == null) throw new ReglaNegocioException(400, "Escribe un teléfono válido para quien recibe");
        String descripcion = Datos.vacioANull(s.descripcion());
        if (descripcion == null) {
            descripcion = String.join(", ", p.bultos().stream().map(Bulto::descripcion).filter(Objects::nonNull).toList());
        }
        if (Datos.vacioANull(descripcion) == null) throw new ReglaNegocioException(400, "Describe qué vas a enviar");
        String instrucciones = Datos.recortar(Datos.vacioANull(s.instruccionesEntrega()), 300);
        // Drogas, armas, dinero, robado…: la encomienda no se crea (la transacción deshace también el servicio)
        List<String> textos = new ArrayList<>(List.of(descripcion));
        p.bultos().stream().map(Bulto::descripcion).filter(Objects::nonNull).forEach(textos::add);
        if (s.notas() != null) textos.add(s.notas());
        if (s.destinoReferencia() != null) textos.add(s.destinoReferencia());
        if (instrucciones != null) textos.add(instrucciones);
        ContenidoProhibido.exigirPermitido(textos.toArray(String[]::new));

        EncomiendaCotizador.Resultado r = p.cotizacion();
        BigDecimal declarado = p.bultos().stream().map(Bulto::valorDeclarado).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean fragil = p.bultos().stream().anyMatch(Bulto::fragil);
        String pin = String.format("%04d", RANDOM.nextInt(10_000));
        String guia = nuevaGuia();
        db.update("""
                INSERT INTO servicio_encomienda (servicio_id, remitente_nombre, remitente_telefono, destinatario_nombre,
                    destinatario_telefono, descripcion_paquete, tamano, peso_kg, fragil, valor_declarado, codigo_entrega,
                    paga_destinatario, numero_guia, token_publico, bultos, peso_volumetrico_kg, peso_facturable_kg,
                    cotizacion, instrucciones_entrega)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, servicioId, Datos.recortar(remitente, 120), telRemitente, Datos.recortar(s.nombreDestinatario(), 120),
                telDestinatario, Datos.recortar(descripcion, 300), Bultos.tamanoMayor(p.bultos()), r.pesoRealKg(), fragil,
                declarado.signum() > 0 ? declarado : null, pin, Boolean.TRUE.equals(s.pagaDestinatario()), guia,
                nuevoToken(), p.bultos().size(), r.pesoVolumetricoKg(), r.pesoFacturableKg(), aJson(r.comoMapa()),
                instrucciones);
        guardarBultos(servicioId, p.bultos());

        // Si quien recibe es otra persona, le llega el código por SMS (Figma "Encomienda 1"). Solo cuando la
        // encomienda ya quedó guardada, y si el SMS falla la encomienda sigue: el remitente también tiene el código.
        if (!telDestinatario.equals(telRemitente)) {
            String texto = "MoviFY: " + Datos.recortar(remitente, 40) + " te envia un paquete (guia " + guia
                    + "). Cuando llegue, dale al conductor este codigo de entrega: " + pin;
            DespuesDelCommit.ejecutar(() -> {
                try {
                    sms.enviar(telDestinatario, texto);
                } catch (RuntimeException e) {
                    log.warn("No se pudo enviar el SMS de la encomienda {}: {}", servicioId, e.getMessage());
                }
            });
        }
        return pin;
    }

    private void guardarBultos(Long servicioId, List<Bulto> bultos) {
        BigDecimal factor = cotizador.parametros().factorVolumetrico();
        List<Object[]> filas = new ArrayList<>();
        for (int i = 0; i < bultos.size(); i++) {
            Bulto b = bultos.get(i);
            BigDecimal vol = b.largoCm() == null || factor.signum() <= 0 ? null
                    : b.largoCm().multiply(b.anchoCm()).multiply(b.altoCm()).divide(factor, 2, java.math.RoundingMode.HALF_UP);
            filas.add(new Object[] { servicioId, i + 1, b.tipo(), b.descripcion(), b.pesoKg(), b.largoCm(), b.anchoCm(),
                    b.altoCm(), vol, b.valorDeclarado(), b.fragil() });
        }
        db.batchUpdate("""
                INSERT INTO encomienda_paquetes (servicio_id, numero, tipo, descripcion, peso_kg, largo_cm, ancho_cm, alto_cm,
                    peso_volumetrico_kg, valor_declarado, fragil)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, filas);
    }

    /** "documento", "caja-pequeña"... (web) → pequeno | mediano | grande. */
    static String tamano(String tamano, String tipoPaquete) {
        return Bultos.tamanoMayor(Bultos.normalizar(null, tipoPaquete, tamano, "1", false, null, null));
    }

    /** Antes de finalizar: el conductor debe dar el PIN que le dio quien recibe (el personal no lo necesita). */
    void prepararEntrega(Long servicioId, CambioEstadoRequest req, boolean personal) {
        Map<String, Object> e = db.queryForMap(
                "SELECT codigo_entrega, destinatario_nombre FROM servicio_encomienda WHERE servicio_id = ?", servicioId);
        if (!personal && !String.valueOf(e.get("codigo_entrega")).equals(req.codigoEntrega())) {
            throw new ReglaNegocioException(400, "El código de entrega no coincide. Pídeselo a quien recibe el paquete.");
        }
        db.update("UPDATE servicio_encomienda SET entregado_a = ? WHERE servicio_id = ?",
                ViajeServicio.firstNonBlank(req.entregadoA(), Datos.texto(e.get("destinatario_nombre"))), servicioId);
    }

    String codigoEntrega(Long servicioId) {
        return db.queryForObject("SELECT codigo_entrega FROM servicio_encomienda WHERE servicio_id = ?", String.class, servicioId);
    }

    /**
     * Datos de la encomienda de un servicio que la persona ya puede ver (lo que devolvió ViajeServicio.obtener).
     * Los teléfonos de remitente y destinatario siguen la misma regla que el del cliente: no los ve un
     * conductor al que solo se le ofreció el servicio. El PIN y el enlace de rastreo solo los ve quien la pidió.
     */
    @Transactional
    public Map<String, Object> detalle(Map<String, Object> servicioVisible) {
        if (!"encomienda".equals(servicioVisible.get("tipo_servicio"))) {
            throw new RecursoNoEncontradoException("Este servicio no es una encomienda");
        }
        Long id = Datos.aLong(servicioVisible.get("id"));
        asegurarGuia(id);
        List<Map<String, Object>> f = db.queryForList("""
                SELECT remitente_nombre, remitente_telefono, destinatario_nombre, destinatario_telefono,
                       descripcion_paquete, tamano, peso_kg, fragil, valor_declarado, entregado_a, paga_destinatario,
                       numero_guia, token_publico, bultos, peso_volumetrico_kg, peso_facturable_kg,
                       cotizacion::text AS cotizacion, instrucciones_entrega
                FROM servicio_encomienda WHERE servicio_id = ?
                """, id);
        if (f.isEmpty()) throw new RecursoNoEncontradoException("Encomienda no encontrada");
        Map<String, Object> r = new HashMap<>(f.get(0));
        r.put("servicio_id", id);
        r.put("estado", servicioVisible.get("estado"));
        r.put("estado_codigo", servicioVisible.get("estado_codigo"));
        r.put("cotizacion", deJson((String) r.get("cotizacion")));
        r.put("paquetes", db.queryForList("""
                SELECT numero, tipo, descripcion, peso_kg, largo_cm, ancho_cm, alto_cm, peso_volumetrico_kg,
                       valor_declarado, fragil
                FROM encomienda_paquetes WHERE servicio_id = ? ORDER BY numero
                """, id));
        r.put("novedades", novedades(id, true));
        String token = (String) r.remove("token_publico");
        boolean soyCliente = Objects.equals(Datos.aLong(servicioVisible.get("cliente_id")), sesion.usuarioId());
        if (soyCliente || sesion.esPersonal()) r.put("url_rastreo", urlRastreo(token));
        if (!Boolean.TRUE.equals(servicioVisible.get("ve_contactos"))) {
            r.remove("remitente_telefono");
            r.remove("destinatario_telefono");
        }
        if (servicioVisible.get("codigo_entrega") != null) r.put("codigo_entrega", servicioVisible.get("codigo_entrega"));
        return r;
    }

    // ═════════════════════════════ Novedades ═════════════════════════════

    /**
     * El conductor asignado (o el personal) registra algo que pasó en la recogida o la entrega.
     * Queda con hora, GPS y quién la reportó, y no se puede editar (trigger de la BD). Se le avisa al cliente.
     */
    @Transactional
    public Map<String, Object> registrarNovedad(Long servicioId, NovedadRequest req) {
        Map<String, Object> s = db.queryForList("""
                SELECT s.tipo_servicio, s.estado, s.conductor_id, s.cliente_id, e.numero_guia
                FROM servicios s LEFT JOIN servicio_encomienda e ON e.servicio_id = s.id
                WHERE s.id = ?
                """, servicioId).stream().findFirst()
                .orElseThrow(() -> new RecursoNoEncontradoException("Servicio no encontrado"));
        if (!"encomienda".equals(s.get("tipo_servicio"))) {
            throw new RecursoNoEncontradoException("Este servicio no es una encomienda");
        }
        boolean personal = sesion.esPersonal();
        Long yo = sesion.usuarioId();
        if (!personal && !Objects.equals(Datos.aLong(s.get("conductor_id")), yo)) {
            throw new AccesoDenegadoException("Solo el conductor de esta encomienda puede reportar novedades");
        }
        String estado = String.valueOf(s.get("estado"));
        if (!ACTIVOS.contains(estado)) {
            throw new ReglaNegocioException(400, "Esta encomienda ya no está en curso");
        }
        String etapa = "en_curso".equals(estado) ? "entrega" : "recogida";
        if ("destinatario_ausente".equals(req.tipo()) && "recogida".equals(etapa)) {
            throw new ReglaNegocioException(400, "Todavía no has recogido el paquete");
        }
        if ("remitente_ausente".equals(req.tipo()) && "entrega".equals(etapa)) {
            throw new ReglaNegocioException(400, "El paquete ya fue recogido");
        }
        String nota = Datos.recortar(Datos.vacioANull(req.nota()), 300);
        if ("otro".equals(req.tipo()) && (nota == null || nota.trim().length() < 5)) {
            throw new ReglaNegocioException(400, "Cuéntanos qué pasó");
        }
        if ((req.lat() == null) != (req.lng() == null)) throw new ReglaNegocioException(400, "Ubicación incompleta");
        Double lat = req.lat();
        Double lng = req.lng();
        if (lat == null && !personal) {
            // Si la app no la mandó, queda la última posición GPS del conductor (trazabilidad de dónde ocurrió)
            Map<String, Object> ub = db.queryForList(
                    "SELECT lat, lng FROM ubicacion_conductores WHERE conductor_id = ?", yo).stream().findFirst().orElse(null);
            if (ub != null) {
                lat = ((Number) ub.get("lat")).doubleValue();
                lng = ((Number) ub.get("lng")).doubleValue();
            }
        }
        String rol = personal ? sesion.actorPersonal() : "conductor";
        Map<String, Object> n = db.queryForMap("""
                INSERT INTO encomienda_novedades (servicio_id, tipo, etapa, nota, lat, lng, reportado_por, actor_rol)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id, tipo, etapa, nota, creado_en
                """, servicioId, req.tipo(), etapa, nota, lat, lng, yo, rol);
        String titulo = TITULO_NOVEDAD.get(req.tipo());
        avisos.avisar(Datos.aLong(s.get("cliente_id")), servicioId, NotificacionServicio.ESTADO, "novedad_encomienda",
                titulo, nota != null ? nota : "Tu encomienda " + s.get("numero_guia") + " tiene una novedad. Revisa el seguimiento.");
        Map<String, Object> r = new LinkedHashMap<>(n);
        r.put("titulo", titulo);
        return r;
    }

    /** Novedades de una encomienda. Con notas solo para quien ya puede ver el servicio (no en el rastreo público). */
    private List<Map<String, Object>> novedades(Long servicioId, boolean conNota) {
        return db.queryForList("""
                SELECT tipo, etapa, nota, creado_en FROM encomienda_novedades WHERE servicio_id = ? ORDER BY creado_en
                """, servicioId).stream().map(x -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tipo", x.get("tipo"));
                    m.put("etapa", x.get("etapa"));
                    m.put("titulo", TITULO_NOVEDAD.get(String.valueOf(x.get("tipo"))));
                    if (conNota && x.get("nota") != null) m.put("nota", x.get("nota"));
                    m.put("creado_en", x.get("creado_en"));
                    return m;
                }).toList();
    }

    // ═════════════════════════════ Rastreo público (sin cuenta) ═════════════════════════════

    /**
     * Lo que ve el destinatario o cualquiera con el enlace: guía, estado, línea de tiempo, primer nombre del
     * conductor, tipo de vehículo y una ubicación aproximada (~100 m) solo mientras el envío está activo.
     * Nunca celulares, apellidos, direcciones exactas, PIN ni notas internas. Vence N días después de terminar.
     */
    public Map<String, Object> rastreoPublico(String token) {
        if (token == null || !token.matches("^[A-Za-z0-9_-]{43}$")) {
            throw new RecursoNoEncontradoException("Este enlace de rastreo no existe");
        }
        Map<String, Object> e = db.queryForList("""
                SELECT s.id, e.numero_guia, s.estado AS estado_codigo, est.nombre AS estado_nombre, s.tipo_vehiculo,
                       e.bultos, e.fragil,
                       COALESCE(s.origen_barrio, 'Punto de recogida') AS origen_zona,
                       COALESCE(s.destino_barrio, 'Punto de entrega') AS destino_zona,
                       split_part(trim(con.nombres), ' ', 1) AS conductor,
                       split_part(trim(e.entregado_a), ' ', 1) AS recibido_por,
                       s.solicitado_en, s.iniciado_en AS recogido_en, s.finalizado_en AS entregado_en, s.cancelado_en,
                       s.duracion_estimada_min,
                       CASE WHEN s.estado IN ('asignado', 'en_origen', 'en_curso') THEN round(ub.lat::numeric, 3) END AS lat_aprox,
                       CASE WHEN s.estado IN ('asignado', 'en_origen', 'en_curso') THEN round(ub.lng::numeric, 3) END AS lng_aprox,
                       CASE WHEN s.estado IN ('asignado', 'en_origen', 'en_curso') THEN ub.actualizado_en END AS ubicacion_en,
                       (COALESCE(s.finalizado_en, s.cancelado_en)
                           + make_interval(days => fn_config_num('encomienda_dias_rastreo_publico', 30)::int) < now())
                           AS vencido
                FROM servicio_encomienda e
                JOIN servicios s ON s.id = e.servicio_id
                JOIN estados est ON est.ambito = 'servicio' AND est.codigo = s.estado
                LEFT JOIN usuarios con ON con.id = s.conductor_id
                LEFT JOIN ubicacion_conductores ub ON ub.conductor_id = s.conductor_id
                WHERE e.token_publico = ?
                """, token).stream().findFirst()
                .orElseThrow(() -> new RecursoNoEncontradoException("Este enlace de rastreo no existe"));
        if (Boolean.TRUE.equals(e.get("vencido"))) throw new ReglaNegocioException(410, "Este enlace de rastreo ya venció");
        Long id = Datos.aLong(e.remove("id"));
        e.remove("vencido");
        List<Map<String, Object>> eventos = new ArrayList<>();
        db.queryForList("""
                SELECT h.estado_nuevo AS codigo, est.nombre AS titulo, h.creado_en
                FROM historial_estados h
                JOIN estados est ON est.ambito = h.ambito AND est.codigo = h.estado_nuevo
                WHERE h.ambito = 'servicio' AND h.registro_id = ? ORDER BY h.creado_en, h.id
                """, id).forEach(h -> {
                    Map<String, Object> m = new LinkedHashMap<>(h);
                    m.put("tipo", "estado");
                    eventos.add(m);
                });
        for (Map<String, Object> n : novedades(id, false)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tipo", "novedad");
            m.put("codigo", n.get("tipo"));
            m.put("titulo", n.get("titulo"));
            m.put("creado_en", n.get("creado_en"));
            eventos.add(m);
        }
        eventos.sort((a, b) -> String.valueOf(a.get("creado_en")).compareTo(String.valueOf(b.get("creado_en"))));
        Map<String, Object> r = new LinkedHashMap<>(Datos.sinNulos(e));
        r.put("eventos", eventos);
        return r;
    }

    // ═════════════════════════════ Guía y token ═════════════════════════════

    /** Las encomiendas de antes de V25 reciben su guía y su token la primera vez que se consultan. */
    void asegurarGuia(Long servicioId) {
        db.update("""
                UPDATE servicio_encomienda SET numero_guia = ?, token_publico = ?
                WHERE servicio_id = ? AND numero_guia IS NULL
                """, nuevaGuia(), nuevoToken(), servicioId);
    }

    /** MVF + 10 caracteres aleatorios (32^10 combinaciones); el índice único de la BD evita repetidos. */
    static String nuevaGuia() {
        StringBuilder b = new StringBuilder("MVF");
        for (int i = 0; i < 10; i++) b.append(ALFABETO_GUIA[RANDOM.nextInt(ALFABETO_GUIA.length)]);
        return b.toString();
    }

    /** 256 bits aleatorios en base64url (43 caracteres). */
    static String nuevoToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private String urlRastreo(String token) {
        return token == null ? null : urlWeb.replaceAll("/+$", "") + "/rastreo/" + token;
    }

    private String aJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo guardar la cotización", e);
        }
    }

    private Object deJson(String s) {
        if (s == null) return null;
        try {
            return json.readValue(s, Object.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
