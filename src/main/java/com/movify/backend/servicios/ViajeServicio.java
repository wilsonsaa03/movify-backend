package com.movify.backend.servicios;

import static com.movify.backend.servicios.EstadosServicio.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.web.multipart.MultipartFile;

import com.movify.backend.autenticacion.sms.SmsEnviador;
import com.movify.backend.comun.AccesoDenegadoException;
import com.movify.backend.comun.ArchivoServicio;
import com.movify.backend.comun.ActorBd;
import com.movify.backend.comun.Celular;
import com.movify.backend.comun.ContenidoProhibido;
import com.movify.backend.comun.Datos;
import com.movify.backend.comun.DespuesDelCommit;
import com.movify.backend.notificaciones.NotificacionServicio;
import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.comun.ReglaNegocioException;
import com.movify.backend.comun.Roles;
import com.movify.backend.seguridad.SesionActual;
import com.movify.backend.seguridad.UsuarioAutenticado;
import com.movify.backend.servicios.ServicioDtos.CambioEstadoRequest;
import com.movify.backend.servicios.ServicioDtos.ItemDomicilio;
import com.movify.backend.servicios.ServicioDtos.SolicitudRequest;

/**
 * Ciclo de vida de un servicio (transporte, domicilio o encomienda) sobre el modelo v2.3:
 * solicitud → ofertas a conductores cercanos → aceptación → en_origen → en_curso → finalizado,
 * o cancelado / expirado. Las transiciones permitidas y quién puede hacerlas están en la
 * tabla transiciones_estado y las valida el trigger fn_servicios_validar_estado.
 */
@Service
public class ViajeServicio {

    private static final Logger log = LoggerFactory.getLogger(ViajeServicio.class);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final List<String> CATEGORIAS_DOMICILIO = List.of("comida", "farmacia", "mercado", "diligencia");
    /** Si la base no tiene el parámetro tope_compra_domicilio (se cambia desde el panel). */
    static final BigDecimal TOPE_COMPRA_DOMICILIO = new BigDecimal("500000");
    private static final BigDecimal CIEN = new BigDecimal("100");

    /** Motivo de cancelación por defecto según quién cancela (motivos_cancelacion). */
    private static final Map<String, Short> MOTIVO_POR_DEFECTO = Map.of(
            ActorBd.CLIENTE, (short) 1, ActorBd.CONDUCTOR, (short) 11, ActorBd.ADMIN, (short) 20,
            ActorBd.SOPORTE, (short) 20);
    /** motivos_cancelacion 10: "El cliente no se presentó" (solo después del tiempo de espera). */
    private static final Short MOTIVO_NO_SE_PRESENTO = 10;

    private final JdbcTemplate db;
    private final SesionActual sesion;
    private final TarifaServicio tarifas;
    private final ActorBd actor;
    private final ObjectProvider<SimulacionDemo> simulacion;
    private final EncomiendaServicio encomiendas;
    private final NotificacionServicio avisos;
    private final SmsEnviador sms;

    public ViajeServicio(JdbcTemplate db, SesionActual sesion, TarifaServicio tarifas, ActorBd actor,
            ObjectProvider<SimulacionDemo> simulacion, EncomiendaServicio encomiendas, NotificacionServicio avisos,
            SmsEnviador sms) {
        this.db = db;
        this.sesion = sesion;
        this.tarifas = tarifas;
        this.actor = actor;
        this.simulacion = simulacion;
        this.encomiendas = encomiendas;
        this.avisos = avisos;
        this.sms = sms;
    }

    // ═════════════════════════════ Solicitud ═════════════════════════════

    @Transactional
    public Map<String, Object> crearSolicitud(SolicitudRequest s) {
        return crearSolicitud(sesion.usuarioId(), s);
    }

    /** También la usan los viajes programados (el cliente no está en sesión: lo lanza una tarea). */
    @Transactional
    public Map<String, Object> crearSolicitud(Long clienteId, SolicitudRequest s) {
        String tipo = EstadosServicio.tipoServicio(s.tipo());
        String vehiculo = s.tipoVehiculo() == null || s.tipoVehiculo().isBlank()
                ? "moto" : s.tipoVehiculo().trim().toLowerCase(Locale.ROOT);
        if (s.metodoPago() != null && !s.metodoPago().isBlank() && !"efectivo".equalsIgnoreCase(s.metodoPago().trim())) {
            throw new ReglaNegocioException(400, "Por ahora en MoviFY solo se paga en efectivo.");
        }
        Extras x = extras(s, tipo, vehiculo, clienteId);

        TarifaServicio.Ruta ruta = tarifas.ruta(s.origenLat(), s.origenLng(), x.paradaLat(), x.paradaLng(),
                s.destinoLat(), s.destinoLng());
        // Lo que cotiza MoviFY; el cliente puede ofrecer más (estilo inDrive) hasta un tope
        BigDecimal cotizado = tarifas.cotizar(tipo, vehiculo, ruta);
        BigDecimal valor = valorOfrecido(s.valorOfrecido(), cotizado);
        actor.declarar(clienteId, ActorBd.CLIENTE);

        Map<String, Object> fila = db.queryForMap("""
                INSERT INTO servicios (tipo_servicio, tipo_vehiculo, cliente_id,
                    origen_direccion, origen_barrio, origen_referencia, origen_lat, origen_lng,
                    destino_direccion, destino_barrio, destino_referencia, destino_lat, destino_lng,
                    distancia_km, duracion_estimada_min, pasajeros, notas_cliente,
                    valor_estimado, valor_sugerido, pasajero_nombre, pasajero_telefono,
                    necesita_casco, lleva_equipaje, lleva_mascota, parada_direccion, parada_lat, parada_lng)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id, valor_estimado, expira_busqueda_en
                """, tipo, vehiculo, clienteId,
                direccion(s.origen(), s.origenLat(), s.origenLng()), Datos.vacioANull(s.origenBarrio()),
                Datos.vacioANull(s.origenReferencia()), s.origenLat(), s.origenLng(),
                direccion(s.destino(), s.destinoLat(), s.destinoLng()), Datos.vacioANull(s.destinoBarrio()),
                Datos.vacioANull(s.destinoReferencia()), s.destinoLat(), s.destinoLng(),
                ruta.distanciaKm(), ruta.duracionMin(), x.pasajeros(),
                Datos.vacioANull("transporte".equals(tipo) ? Datos.recortar(firstNonBlank(s.notas(), s.descripcion()), 300)
                        : s.notas()),
                valor, cotizado, x.pasajeroNombre(), x.pasajeroTelefono(), x.casco(), x.equipaje(), x.mascota(),
                x.parada(), x.paradaLat(), x.paradaLng());
        Long servicioId = Datos.aLong(fila.get("id"));

        String codigoEntrega = null;
        String codigoAbordaje = null;
        switch (tipo) {
            case "domicilio" -> crearDomicilio(servicioId, s);
            case "encomienda" -> codigoEntrega = encomiendas.crear(servicioId, s, clienteId);
            default -> {
                // Viaje: PIN que el pasajero le dice al conductor para empezar (Figma "Conductor app 4 · PIN")
                codigoAbordaje = String.format("%04d", RANDOM.nextInt(10_000));
                db.update("UPDATE servicios SET codigo_abordaje = ? WHERE id = ?", codigoAbordaje, servicioId);
            }
        }

        int notificados = ofertar(servicioId);
        log.info("Servicio {} ({} en {}) creado; {} conductores notificados", servicioId, tipo, vehiculo, notificados);

        // Solo existe si app.demo.simulacion=true (nunca en producción)
        simulacion.ifAvailable(sim -> sim.programar(servicioId));

        Map<String, Object> resp = new HashMap<>();
        resp.put("id", servicioId);
        resp.put("tarifa", fila.get("valor_estimado"));
        resp.put("valor_estimado", fila.get("valor_estimado"));
        resp.put("distancia_km", ruta.distanciaKm());
        resp.put("duracion_min", ruta.duracionMin());
        resp.put("conductores_notificados", notificados);
        resp.put("estado", "PENDIENTE");
        resp.put("estado_codigo", BUSCANDO);
        resp.put("expira_busqueda_en", fila.get("expira_busqueda_en"));
        if (codigoEntrega != null) resp.put("codigo_entrega", codigoEntrega);
        if (codigoAbordaje != null) resp.put("codigo_abordaje", codigoAbordaje);
        resp.put("message", "Solicitud creada con éxito.");
        return resp;
    }

    /**
     * Le ofrece el servicio a los conductores libres cercanos (y a los favoritos del cliente, con más radio).
     * Si el pasajero pidió casco, solo a motos que lo llevan. A cada uno le llega un push.
     * @return cuántos conductores nuevos lo recibieron.
     */
    public int ofertar(Long servicioId) {
        Map<String, Object> s = db.queryForMap("""
                SELECT tipo_servicio, tipo_vehiculo, cliente_id, origen_lat, origen_lng, necesita_casco, valor_estimado
                FROM servicios WHERE id = ?
                """, servicioId);
        double lat = Datos.num(s.get("origen_lat"));
        double lng = Datos.num(s.get("origen_lng"));
        String vehiculo = String.valueOf(s.get("tipo_vehiculo"));
        Long clienteId = Datos.aLong(s.get("cliente_id"));
        List<Long> nuevos = db.queryForList("""
                INSERT INTO servicio_ofertas (servicio_id, conductor_id, distancia_km)
                SELECT ?, c.conductor_id, round(c.distancia_km::numeric, 2)
                FROM (SELECT conductor_id, vehiculo_id, distancia_km
                        FROM fn_conductores_cercanos(?, ?, fn_config_num('radio_busqueda_km', 5)::float8, ?, ?)
                      UNION
                      SELECT f.conductor_id, f.vehiculo_id, f.distancia_km
                        FROM fn_conductores_cercanos(?, ?, fn_config_num('radio_favoritos_km', 8)::float8, ?, ?) f
                        JOIN conductores_favoritos cf ON cf.conductor_id = f.conductor_id AND cf.cliente_id = ?) c
                JOIN vehiculos v ON v.id = c.vehiculo_id
                WHERE NOT ? OR v.casco_pasajero IS TRUE
                ON CONFLICT DO NOTHING
                RETURNING conductor_id
                """, Long.class, servicioId, lat, lng, vehiculo, clienteId, lat, lng, vehiculo, clienteId, clienteId,
                Boolean.TRUE.equals(s.get("necesita_casco")));
        if (!nuevos.isEmpty()) {
            String que = switch (String.valueOf(s.get("tipo_servicio"))) {
                case "domicilio" -> "Nuevo domicilio";
                case "encomienda" -> "Nueva encomienda";
                default -> "Nueva solicitud de " + vehiculo;
            };
            avisos.avisarVarios(nuevos, servicioId, NotificacionServicio.SOLICITUD, "solicitud", "¡" + que + "!",
                    "Gana " + pesos(s.get("valor_estimado")) + ". Tienes unos segundos para aceptar.");
        }
        return nuevos.size();
    }

    /** Lo que trae una solicitud de viaje además del origen y el destino, ya validado. */
    private record Extras(int pasajeros, boolean casco, boolean equipaje, boolean mascota, String pasajeroNombre,
            String pasajeroTelefono, String parada, Double paradaLat, Double paradaLng) {
    }

    private Extras extras(SolicitudRequest s, String tipo, String vehiculo, Long clienteId) {
        boolean transporte = "transporte".equals(tipo);
        int pasajeros = s.pasajeros() == null ? 1 : s.pasajeros();
        if (transporte && "moto".equals(vehiculo) && pasajeros > 1) {
            throw new ReglaNegocioException(400, "En moto viaja 1 pasajero. Para más personas pide un taxi.");
        }
        if (transporte && "taxi".equals(vehiculo) && pasajeros > 4) {
            throw new ReglaNegocioException(400, "En un taxi caben hasta 4 pasajeros.");
        }
        boolean casco = Boolean.TRUE.equals(s.necesitaCasco());
        if (casco && !("moto".equals(vehiculo) && transporte)) {
            throw new ReglaNegocioException(400, "El casco para el pasajero solo aplica en viajes en moto.");
        }

        String nombre = null;
        String telefono = null;
        if (Datos.vacioANull(s.pasajeroNombre()) != null || Datos.vacioANull(s.pasajeroTelefono()) != null) {
            if (!transporte) throw new ReglaNegocioException(400, "Pedir para otra persona solo aplica en viajes.");
            nombre = Datos.vacioANull(s.pasajeroNombre());
            if (nombre == null) throw new ReglaNegocioException(400, "Escribe el nombre de quien va a viajar");
            telefono = Celular.exigir(s.pasajeroTelefono());
            String propio = db.queryForObject("SELECT telefono FROM usuarios WHERE id = ?", String.class, clienteId);
            // Si pone su propio número, el viaje es para él mismo
            if (telefono.equals(propio)) {
                nombre = null;
                telefono = null;
            }
        }

        String parada = null;
        Double pLat = null;
        Double pLng = null;
        if (s.paradaLat() != null || s.paradaLng() != null) {
            if (!transporte) throw new ReglaNegocioException(400, "Las paradas solo aplican en viajes.");
            if (s.paradaLat() == null || s.paradaLng() == null) {
                throw new ReglaNegocioException(400, "Marca la parada en el mapa");
            }
            pLat = s.paradaLat();
            pLng = s.paradaLng();
            parada = direccion(s.parada(), pLat, pLng);
        }
        return new Extras(pasajeros, casco, transporte && Boolean.TRUE.equals(s.llevaEquipaje()),
                transporte && Boolean.TRUE.equals(s.llevaMascota()), Datos.recortar(nombre, 80), telefono, parada, pLat,
                pLng);
    }

    /** null = lo cotizado. Si ofrece más: redondeado a la centena, nunca menos de lo cotizado ni más del tope. */
    private BigDecimal valorOfrecido(BigDecimal ofrecido, BigDecimal cotizado) {
        if (ofrecido == null || ofrecido.compareTo(cotizado) <= 0) return cotizado;
        BigDecimal valor = ofrecido.divide(CIEN, 0, RoundingMode.CEILING).multiply(CIEN);
        BigDecimal tope = tarifas.maximoOfrecible(cotizado);
        if (valor.compareTo(tope) > 0) {
            throw new ReglaNegocioException(400, "Puedes ofrecer hasta " + pesos(tope) + " por este viaje.");
        }
        return valor;
    }

    /** $12.500 */
    static String pesos(Object valor) {
        long v = valor instanceof Number n ? Math.round(n.doubleValue()) : 0;
        return "$" + String.format(Locale.ROOT, "%,d", v).replace(',', '.');
    }

    private void crearDomicilio(Long servicioId, SolicitudRequest s) {
        String descripcion = Datos.vacioANull(s.descripcion());
        if (descripcion == null) throw new ReglaNegocioException(400, "Cuéntanos qué necesitas que te traigan");
        ContenidoProhibido.exigirPermitido(descripcion, s.establecimiento(), s.notas());
        if (s.items() != null) {
            for (ItemDomicilio i : s.items()) ContenidoProhibido.exigirPermitido(i.descripcion(), i.notas());
        }
        boolean compra = Boolean.TRUE.equals(s.requiereCompra());
        BigDecimal valor = s.valorProductosAprox();
        if (compra && (valor == null || valor.signum() <= 0)) {
            throw new ReglaNegocioException(400, "Indica cuánto cuestan más o menos los productos");
        }
        // El domiciliario pone la plata de su bolsillo: un tope evita pedidos que nadie podría pagar
        BigDecimal tope = db.queryForObject("SELECT fn_config_num('tope_compra_domicilio', ?)", BigDecimal.class,
                TOPE_COMPRA_DOMICILIO);
        if (compra && valor.compareTo(tope) > 0) {
            throw new ReglaNegocioException(400, "Por ahora el domiciliario puede pagar pedidos de hasta $"
                    + String.format(Locale.ROOT, "%,d", tope.longValue()).replace(',', '.')
                    + ". Si ya está pago, elige \"Ya está pago\".");
        }
        String categoria = categoriaDomicilio(s.categoria());
        String tel = s.telefonoContacto() == null || s.telefonoContacto().isBlank() ? null
                : Celular.normalizarContacto(s.telefonoContacto());
        db.update("""
                INSERT INTO servicio_domicilio (servicio_id, establecimiento, descripcion_pedido, requiere_compra,
                                                valor_productos_aprox, telefono_contacto, categoria)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, servicioId, Datos.vacioANull(s.establecimiento()), descripcion, compra, compra ? valor : null, tel,
                categoria);
        if (s.items() != null) {
            for (ItemDomicilio i : s.items()) {
                db.update("""
                        INSERT INTO servicio_domicilio_items (servicio_id, descripcion, cantidad, precio_unitario_aprox, notas)
                        VALUES (?, ?, ?, ?, ?)
                        """, servicioId, i.descripcion().trim(), i.cantidad() == null ? 1 : i.cantidad(),
                        i.precioUnitarioAprox(), Datos.vacioANull(i.notas()));
            }
        }
    }

    /** comida | farmacia | mercado | diligencia (Figma "¿Qué es?"); vacío = sin indicar. */
    static String categoriaDomicilio(String texto) {
        String c = Datos.vacioANull(texto);
        if (c == null) return null;
        c = c.trim().toLowerCase(Locale.ROOT);
        if (!CATEGORIAS_DOMICILIO.contains(c)) {
            throw new ReglaNegocioException(400, "Elige qué es el pedido: comida, farmacia, mercado o diligencia");
        }
        return c;
    }

    // ═════════════════════════════ Ofertas para el conductor ═════════════════════════════

    /**
     * Solicitudes que el conductor puede aceptar. Se crean las ofertas que falten para las
     * solicitudes cercanas (por si se conectó después de que el cliente pidió).
     */
    @Transactional
    public List<Map<String, Object>> solicitudesPara(Long conductorId) {
        db.update("""
                INSERT INTO servicio_ofertas (servicio_id, conductor_id, distancia_km)
                SELECT d.servicio_id, ?, round(d.distancia_km::numeric, 2)
                FROM fn_servicios_disponibles(?, fn_config_num('radio_busqueda_km', 5)::float8) d
                ON CONFLICT DO NOTHING
                """, conductorId, conductorId);
        db.update("""
                UPDATE servicio_ofertas o SET estado = 'vista'
                WHERE o.conductor_id = ? AND o.estado = 'enviada'
                """, conductorId);
        return db.queryForList("""
                SELECT o.id AS notificacion_id, o.id AS oferta_id, v.id AS servicio_id, v.tipo, v.tipo_servicio,
                       v.tipo_vehiculo, v.descripcion, v.origen_lat, v.origen_lng, v.destino_lat, v.destino_lng,
                       v.origen, v.destino, v.origen_referencia, v.distancia_km, v.duracion_estimada_min,
                       v.valor_estimado AS tarifa, v.pasajeros, o.distancia_km AS distancia_al_origen_km,
                       v.usuario_nombre, v.expira_busqueda_en, v.estado, s.nota_voz_segundos,
                       v.pasajero_nombre, v.necesita_casco, v.lleva_equipaje, v.lleva_mascota, v.parada,
                       v.parada_lat, v.parada_lng, (v.valor_estimado > v.valor_sugerido) AS tarifa_subida
                FROM servicio_ofertas o
                JOIN v_servicios_api v ON v.id = o.servicio_id
                JOIN servicios s ON s.id = o.servicio_id
                WHERE o.conductor_id = ? AND o.estado IN ('enviada', 'vista')
                  AND v.estado_codigo = 'buscando' AND v.expira_busqueda_en > now()
                ORDER BY o.distancia_km, v.solicitado_en
                """, conductorId);
    }

    // ═════════════════════════════ Consulta ═════════════════════════════

    public Map<String, Object> obtener(Long servicioId) {
        Map<String, Object> s = new HashMap<>(fila(servicioId));
        UsuarioAutenticado yo = sesion.usuario();
        boolean soyCliente = Objects.equals(Datos.aLong(s.get("cliente_id")), yo.id());
        exigirPuedeVer(servicioId, s, yo, soyCliente);

        s.put("conductor_lat", null);
        s.put("conductor_lng", null);
        s.put("conductor_nombre", null);
        s.put("conductor_foto", null);
        s.put("conductor_telefono", null);
        Long conductorId = Datos.aLong(s.get("conductor_id"));
        if (conductorId != null) {
            db.queryForList("""
                    SELECT ub.lat AS conductor_lat, ub.lng AS conductor_lng, ub.actualizado_en AS conductor_ubicacion_en,
                           trim(u.nombres || ' ' || COALESCE(u.apellidos, '')) AS conductor_nombre,
                           u.foto_url AS conductor_foto, u.telefono AS conductor_telefono,
                           c.calificacion_promedio AS conductor_calificacion,
                           c.sobre_mi AS conductor_sobre_mi, (c.estado = 'aprobado') AS conductor_verificado,
                           (SELECT array_to_string(array_agg(t.e ORDER BY t.n DESC, t.e), '|')
                              FROM (SELECT e, count(*) AS n FROM calificaciones ca, unnest(ca.elogios) AS e
                                     WHERE ca.calificado_id = c.usuario_id GROUP BY e ORDER BY n DESC, e LIMIT 3) t)
                               AS conductor_elogios,
                           v.placa AS conductor_placa, trim(v.marca || ' ' || COALESCE(v.linea, '')) AS conductor_modelo,
                           v.color AS conductor_color, v.tipo_vehiculo AS conductor_vehiculo,
                           v.casco_pasajero AS conductor_casco, c.total_servicios AS conductor_viajes,
                           ub.rumbo_grados AS conductor_rumbo,
                           EXISTS (SELECT 1 FROM documentos_conductor d
                                    WHERE d.vehiculo_id = v.id AND d.tipo_documento = 'foto_frente' AND d.vigente
                                      AND d.mime_type LIKE 'image/%') AS conductor_vehiculo_foto
                    FROM conductores c
                    JOIN usuarios u ON u.id = c.usuario_id
                    LEFT JOIN ubicacion_conductores ub ON ub.conductor_id = c.usuario_id
                    LEFT JOIN vehiculos v ON v.id = ?
                    WHERE c.usuario_id = ?
                    """, s.get("vehiculo_id"), conductorId).stream().findFirst().ifPresent(s::putAll);
            if (soyCliente) {
                s.put("es_favorito", db.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM conductores_favoritos WHERE cliente_id = ? AND conductor_id = ?)",
                        Boolean.class, yo.id(), conductorId));
            }
        }
        // Cuánto espera el conductor en la recogida (después puede marcar "no se presentó")
        db.queryForList("""
                SELECT llegada_origen_en + make_interval(mins => fn_config_num('minutos_espera_conductor', 5)::int)
                       AS espera_hasta,
                       (SELECT count(*) FROM mensajes_servicio m
                         WHERE m.servicio_id = s.id AND m.remitente_id <> ? AND m.leido_en IS NULL) AS mensajes_sin_leer,
                       aumentos_tarifa
                FROM servicios s WHERE s.id = ?
                """, yo.id(), servicioId).stream().findFirst().ifPresent(s::putAll);
        // El PIN de entrega solo lo ve quien pidió la encomienda (se lo pasa al destinatario)
        if (soyCliente && "encomienda".equals(s.get("tipo_servicio"))) {
            s.put("codigo_entrega", encomiendas.codigoEntrega(servicioId));
        }
        // El PIN del viaje solo lo ve el pasajero (se lo dice al conductor al subir)
        if (soyCliente && "transporte".equals(s.get("tipo_servicio"))) {
            s.put("codigo_abordaje", db.queryForObject("SELECT codigo_abordaje FROM servicios WHERE id = ?", String.class,
                    servicioId));
        }
        if ("domicilio".equals(s.get("tipo_servicio"))) {
            s.put("categoria", db.queryForList("SELECT categoria FROM servicio_domicilio WHERE servicio_id = ?",
                    String.class, servicioId).stream().findFirst().orElse(null));
            s.put("items", db.queryForList("""
                    SELECT descripcion, cantidad, precio_unitario_aprox, notas FROM servicio_domicilio_items
                    WHERE servicio_id = ? ORDER BY id
                    """, servicioId));
        }
        // Para la tarjeta del pasajero en la solicitud (estrellas y "VERIFICADA") y la nota de voz del punto de referencia
        db.queryForList("""
                SELECT u.calificacion_promedio AS usuario_calificacion, u.telefono_verificado AS usuario_verificado,
                       s.nota_voz_segundos
                FROM servicios s JOIN usuarios u ON u.id = s.cliente_id
                WHERE s.id = ?
                """, servicioId).stream().findFirst().ifPresent(s::putAll);
        // El celular del cliente solo lo ve el conductor asignado (y el personal), no quien recibe la oferta
        // Lo mismo con los celulares de remitente y destinatario de una encomienda (vienen en v_servicios_api)
        if (!soyCliente && !sesion.esPersonal() && !Objects.equals(conductorId, yo.id())) {
            s.remove("usuario_telefono");
            s.remove("pasajero_telefono");
            s.remove("telefono_remitente");
            s.remove("telefono_destinatario");
        }
        s.remove("usuario_id");
        return s;
    }

    private void exigirPuedeVer(Long servicioId, Map<String, Object> s, UsuarioAutenticado yo, boolean soyCliente) {
        if (soyCliente || sesion.esPersonal()) return;
        if (Objects.equals(Datos.aLong(s.get("conductor_id")), yo.id())) return;
        if (yo.tieneRol(Roles.CONDUCTOR)) {
            Boolean ofertado = db.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM servicio_ofertas WHERE servicio_id = ? AND conductor_id = ?)",
                    Boolean.class, servicioId, yo.id());
            if (Boolean.TRUE.equals(ofertado)) return;
        }
        throw new AccesoDenegadoException("No puedes ver este servicio");
    }

    /** El servicio que la persona tiene en curso ahora (como cliente o como conductor), o null. */
    public Map<String, Object> activo() {
        Long yo = sesion.usuarioId();
        List<Long> ids = db.queryForList("""
                SELECT id FROM servicios
                WHERE (cliente_id = ? AND estado IN ('buscando', 'asignado', 'en_origen', 'en_curso'))
                   OR (conductor_id = ? AND estado IN ('asignado', 'en_origen', 'en_curso'))
                ORDER BY solicitado_en DESC LIMIT 1
                """, Long.class, yo, yo);
        return ids.isEmpty() ? null : obtener(ids.get(0));
    }

    /** Línea de tiempo de los estados del servicio (mismas reglas de acceso que obtener). */
    public List<Map<String, Object>> historial(Long servicioId) {
        obtener(servicioId); // valida que pueda verlo
        return db.queryForList("""
                SELECT creado_en, estado_anterior, estado_nuevo, estado_nombre, color, actor_rol, motivo
                FROM v_historial_servicios WHERE servicio_id = ? ORDER BY creado_en
                """, servicioId);
    }

    /**
     * Sanción por alta cancelación: con historial suficiente (mín. 5 cierres) y más de 20 % de
     * cancelaciones propias el conductor deja de recibir solicitudes.
     * @return la tasa de cancelación (%) si está sancionado, o null.
     */
    public Double tasaSancion(Long conductorId) {
        Map<String, Object> t = db.queryForMap(
                "SELECT total_servicios, total_cancelaciones FROM conductores WHERE usuario_id = ?", conductorId);
        long cancelados = ((Number) t.get("total_cancelaciones")).longValue();
        long cerrados = ((Number) t.get("total_servicios")).longValue() + cancelados;
        double tasa = cerrados == 0 ? 0 : cancelados * 100.0 / cerrados;
        return cerrados >= 5 && tasa > 20.0 ? Math.round(tasa * 10) / 10.0 : null;
    }

    /** Conductores libres para el mapa: solo posición, sin nombre ni foto. */
    public List<Map<String, Object>> conductoresLibres(String tipoVehiculo) {
        return db.queryForList("""
                SELECT usuario_id AS conductor_id, lat AS latitud, lng AS longitud, true AS en_linea, tipo_vehiculo
                FROM v_conductores_en_linea
                WHERE NOT ocupado
                  AND actualizado_en > now() - make_interval(secs => fn_config_num('ubicacion_vigencia_seg', 300)::int)
                  AND (CAST(? AS varchar) IS NULL OR tipo_vehiculo = ?)
                """, tipoVehiculo, tipoVehiculo);
    }

    // ═════════════════════════════ Cambios de estado ═════════════════════════════

    @Transactional
    public Map<String, Object> cambiarEstado(Long servicioId, CambioEstadoRequest req) {
        String destino = EstadosServicio.normalizar(req.estado());
        Map<String, Object> s = db.queryForList("""
                SELECT id, tipo_servicio, tipo_vehiculo, estado, cliente_id, conductor_id
                FROM servicios WHERE id = ? FOR UPDATE
                """, servicioId).stream().findFirst()
                .orElseThrow(() -> new RecursoNoEncontradoException("Servicio no encontrado"));
        String actual = String.valueOf(s.get("estado"));
        String tipo = String.valueOf(s.get("tipo_servicio"));
        UsuarioAutenticado yo = sesion.usuario();
        Long cliente = Datos.aLong(s.get("cliente_id"));
        Long conductor = Datos.aLong(s.get("conductor_id"));

        // ¿Quién actúa?
        String quien;
        if (Objects.equals(conductor, yo.id())) {
            quien = ActorBd.CONDUCTOR;
        } else if (Objects.equals(cliente, yo.id())) {
            quien = ActorBd.CLIENTE;
        } else if (yo.tieneRol(Roles.CONDUCTOR) && conductor == null
                && (ASIGNADO.equals(destino) || RECHAZAR.equals(destino))) {
            quien = ActorBd.CONDUCTOR;
        } else if (sesion.esPersonal()) {
            quien = sesion.actorPersonal();
        } else {
            throw new AccesoDenegadoException("No puedes modificar este servicio");
        }
        actor.declarar(yo.id(), quien);

        switch (destino) {
            case RECHAZAR -> {
                db.update("""
                        UPDATE servicio_ofertas SET estado = 'rechazada', respondida_en = now()
                        WHERE servicio_id = ? AND conductor_id = ? AND estado IN ('enviada', 'vista')
                        """, servicioId, yo.id());
                return respuesta(servicioId, tipo, actual, "Servicio rechazado.");
            }
            case SIN_CAMBIO -> {
                return respuesta(servicioId, tipo, actual, "Vas en camino al punto de recogida.");
            }
            case ASIGNADO -> {
                if (!ActorBd.CONDUCTOR.equals(quien)) throw new AccesoDenegadoException("Solo un conductor puede aceptar");
                aceptar(servicioId, yo.id(), String.valueOf(s.get("tipo_vehiculo")));
                avisarAsignado(servicioId, tipo, cliente);
                return respuesta(servicioId, tipo, ASIGNADO, "Servicio aceptado. Ve a recoger al cliente.");
            }
            case CANCELADO -> {
                // El conductor no "cancela" un servicio recién aceptado: lo suelta y vuelve a buscarse otro conductor
                if (ActorBd.CONDUCTOR.equals(quien) && ASIGNADO.equals(actual)) {
                    actualizar(servicioId, actual, BUSCANDO, null);
                    ofertar(servicioId);
                    avisos.avisar(cliente, servicioId, NotificacionServicio.ESTADO, "buscando",
                            "Buscamos otro conductor", "Tu conductor no pudo ir. Ya le avisamos a otros cercanos.");
                    return respuesta(servicioId, tipo, BUSCANDO, "Soltaste el servicio. Buscaremos otro conductor.");
                }
                Short motivo = req.motivoId() != null ? req.motivoId() : MOTIVO_POR_DEFECTO.get(quien);
                if (ActorBd.CONDUCTOR.equals(quien) && MOTIVO_NO_SE_PRESENTO.equals(motivo)) exigirEsperaCumplida(servicioId);
                db.update("""
                        UPDATE servicios SET estado = 'cancelado', motivo_cancelacion_id = ?, detalle_cancelacion = ?,
                                             cancelado_por = ?
                        WHERE id = ? AND estado = ?
                        """, motivo, Datos.vacioANull(req.motivo()), yo.id(), servicioId, actual);
                if (ActorBd.CLIENTE.equals(quien) && conductor != null) {
                    avisos.avisar(conductor, servicioId, NotificacionServicio.ESTADO, "cancelado",
                            "El pasajero canceló", "Ya puedes recibir otras solicitudes.");
                } else if (!ActorBd.CLIENTE.equals(quien)) {
                    avisos.avisar(cliente, servicioId, NotificacionServicio.ESTADO, "cancelado",
                            "Tu servicio fue cancelado", ActorBd.CONDUCTOR.equals(quien)
                                    ? "El conductor canceló. Puedes pedir otro cuando quieras."
                                    : "Soporte de MoviFY canceló el servicio.");
                    // Lo canceló el personal: el conductor asignado también tiene que enterarse
                    if (!ActorBd.CONDUCTOR.equals(quien) && conductor != null) {
                        avisos.avisar(conductor, servicioId, NotificacionServicio.ESTADO, "cancelado",
                                "Soporte canceló el servicio", "Ya puedes recibir otras solicitudes.");
                    }
                }
                return respuesta(servicioId, tipo, CANCELADO, "Servicio cancelado.");
            }
            case BUSCANDO -> throw new ReglaNegocioException(400, "Estado no válido para este cambio");
            default -> {
                // en_origen, en_curso, finalizado: los da el conductor asignado, paso a paso
                if (!ActorBd.CONDUCTOR.equals(quien) && !ActorBd.ADMIN.equals(quien) && !ActorBd.SOPORTE.equals(quien)) {
                    throw new AccesoDenegadoException("Solo el conductor del servicio puede avanzar su estado");
                }
                String anterior = actual;
                for (String paso : EstadosServicio.pasos(actual, destino)) {
                    if (EN_CURSO.equals(paso) && "transporte".equals(tipo)) verificarAbordaje(servicioId, req);
                    if (FINALIZADO.equals(paso)) prepararCierre(servicioId, tipo, req, quien);
                    actualizar(servicioId, anterior, paso, null);
                    anterior = paso;
                }
                avisarAvance(servicioId, tipo, destino, cliente);
                return respuesta(servicioId, tipo, destino, "Estado actualizado.");
            }
        }
    }

    /** "Carlos va por ti" al cliente y, si el viaje es para otra persona, un SMS a quien viaja. */
    private void avisarAsignado(Long servicioId, String tipo, Long cliente) {
        Map<String, Object> d = db.queryForMap("""
                SELECT split_part(trim(u.nombres), ' ', 1) AS conductor, v.placa, v.color,
                       trim(v.marca || ' ' || COALESCE(v.linea, '')) AS modelo, s.tipo_vehiculo,
                       s.pasajero_nombre, s.pasajero_telefono, s.codigo_abordaje,
                       split_part(trim(c.nombres), ' ', 1) AS cliente
                FROM servicios s
                JOIN usuarios u ON u.id = s.conductor_id
                JOIN vehiculos v ON v.id = s.vehiculo_id
                JOIN usuarios c ON c.id = s.cliente_id
                WHERE s.id = ?
                """, servicioId);
        String conductor = String.valueOf(d.get("conductor"));
        String vehiculo = d.get("modelo") + " " + d.get("color") + " · " + d.get("placa");
        String titulo = switch (tipo) {
            case "domicilio" -> conductor + " tomó tu domicilio";
            case "encomienda" -> conductor + " va a recoger tu paquete";
            default -> conductor + " va por ti";
        };
        avisos.avisar(cliente, servicioId, NotificacionServicio.ESTADO, "asignado", titulo, vehiculo);

        String tel = (String) d.get("pasajero_telefono");
        if (tel != null) {
            String texto = "MoviFY: " + d.get("cliente") + " te pidio un " + d.get("tipo_vehiculo") + ". Te recoge "
                    + conductor + " en " + vehiculo + ". Tu PIN para empezar el viaje: " + d.get("codigo_abordaje");
            DespuesDelCommit.ejecutar(() -> {
                try {
                    sms.enviar(tel, texto);
                } catch (RuntimeException e) {
                    log.warn("No se pudo enviar el SMS al pasajero del servicio {}: {}", servicioId, e.getMessage());
                }
            });
        }
    }

    /** Llegó, empezó o terminó: el cliente se entera aunque tenga la app cerrada. */
    private void avisarAvance(Long servicioId, String tipo, String estado, Long cliente) {
        boolean viaje = "transporte".equals(tipo);
        switch (estado) {
            case EN_ORIGEN -> avisos.avisar(cliente, servicioId, NotificacionServicio.ESTADO, "en_origen",
                    viaje ? "Tu conductor llegó" : "El conductor llegó a la recogida",
                    viaje ? "Te está esperando. Ten a mano tu PIN de viaje." : "Está recogiendo tu pedido.");
            case EN_CURSO -> {
                if (!viaje) avisos.avisar(cliente, servicioId, NotificacionServicio.ESTADO, "en_curso",
                        "Tu pedido va en camino", "El conductor ya lo recogió.");
            }
            case FINALIZADO -> avisos.avisar(cliente, servicioId, NotificacionServicio.ESTADO, "finalizado",
                    viaje ? "¡Llegaste!" : "¡Entregado!", "Cuéntanos cómo te fue: califica el servicio.");
            default -> {
                // Sin aviso para otros estados
            }
        }
    }

    /** "No se presentó" solo después del tiempo de espera, para no dejar a nadie tirado. */
    private void exigirEsperaCumplida(Long servicioId) {
        Map<String, Object> e = db.queryForMap("""
                SELECT estado, llegada_origen_en + make_interval(mins => fn_config_num('minutos_espera_conductor', 5)::int)
                       > now() AS falta,
                       to_char((llegada_origen_en + make_interval(mins => fn_config_num('minutos_espera_conductor', 5)::int))
                               AT TIME ZONE 'America/Bogota', 'HH24:MI') AS hora
                FROM servicios WHERE id = ?
                """, servicioId);
        if (!EN_ORIGEN.equals(e.get("estado"))) {
            throw new ReglaNegocioException(400, "Primero marca que llegaste a la recogida.");
        }
        if (Boolean.TRUE.equals(e.get("falta"))) {
            throw new ReglaNegocioException(400, "Espera al pasajero hasta las " + e.get("hora")
                    + ". Si no aparece, ya podrás cancelar sin que te cuente.");
        }
    }

    /** UPDATE condicionado al estado leído: si otro llegó primero, no se pisa (0 filas = conflicto). */
    private void actualizar(Long servicioId, String desde, String hacia, Object extra) {
        int n = db.update("UPDATE servicios SET estado = ? WHERE id = ? AND estado = ?", hacia, servicioId, desde);
        if (n == 0) throw new ReglaNegocioException("El servicio ya no está disponible o cambió de estado");
    }

    private void aceptar(Long servicioId, Long conductorId, String tipoVehiculo) {
        List<Long> vehiculo = db.queryForList(
                "SELECT id FROM vehiculos WHERE conductor_id = ? AND activo AND tipo_vehiculo = ?", Long.class,
                conductorId, tipoVehiculo);
        if (vehiculo.isEmpty()) {
            throw new ReglaNegocioException("Este servicio es para " + tipoVehiculo + " y tu vehículo activo no lo es");
        }
        int n = db.update("""
                UPDATE servicios SET estado = 'asignado', conductor_id = ?, vehiculo_id = ?
                WHERE id = ? AND estado = 'buscando'
                """, conductorId, vehiculo.get(0), servicioId);
        if (n == 0) throw new ReglaNegocioException("Otro conductor ya tomó este servicio o la solicitud venció");
    }

    /**
     * Al iniciar el viaje: si el conductor manda el PIN, tiene que ser el del pasajero (así confirma que lleva
     * a la persona correcta). La app del conductor siempre lo manda; la web todavía no lo pide, por eso sin PIN
     * se deja pasar. Cuando la web también lo pida, aquí se vuelve obligatorio.
     */
    private void verificarAbordaje(Long servicioId, CambioEstadoRequest req) {
        if (req.codigoAbordaje() == null || req.codigoAbordaje().isBlank()) return;
        String pin = db.queryForObject("SELECT codigo_abordaje FROM servicios WHERE id = ?", String.class, servicioId);
        if (pin != null && !pin.equals(req.codigoAbordaje().trim())) {
            throw new ReglaNegocioException(400, "El PIN no coincide. Pídeselo de nuevo al pasajero.");
        }
    }

    /** Antes de finalizar: PIN de la encomienda y valor real de la compra del domicilio. */
    private void prepararCierre(Long servicioId, String tipo, CambioEstadoRequest req, String quien) {
        if ("encomienda".equals(tipo)) {
            encomiendas.prepararEntrega(servicioId, req, ActorBd.ADMIN.equals(quien) || ActorBd.SOPORTE.equals(quien));
        }
        if ("domicilio".equals(tipo)) {
            if (req.valorCompraReal() != null) {
                if (req.valorCompraReal().signum() < 0) throw new ReglaNegocioException(400, "El valor de la factura no es válido");
                db.update("UPDATE servicio_domicilio SET valor_compra_real = ? WHERE servicio_id = ?", req.valorCompraReal(),
                        servicioId);
            } else {
                // La BD no deja cerrar una compra sin el valor real: se pide con un mensaje claro
                Boolean falta = db.queryForObject("""
                        SELECT requiere_compra AND valor_compra_real IS NULL FROM servicio_domicilio WHERE servicio_id = ?
                        """, Boolean.class, servicioId);
                if (Boolean.TRUE.equals(falta)) {
                    throw new ReglaNegocioException(400, "Escribe cuánto pagaste en la tienda (lo que dice la factura)");
                }
            }
        }
    }

    private Map<String, Object> respuesta(Long servicioId, String tipo, String estado, String mensaje) {
        return Map.of("message", mensaje, "servicioId", servicioId, "estado", EstadosServicio.legado(tipo, estado),
                "estado_codigo", estado);
    }

    // ═════════════════════════════ Calificación ═════════════════════════════

    /**
     * Elogios que se pueden dar al calificar (lista cerrada: lo que se muestra en el perfil no puede ser texto libre).
     * Al conductor: Manejó con cuidado, Muy amable, Llegó rápido, Conoce la zona, Me prestó casco, Carro limpio.
     * Al pasajero: Buena conducta, Muy amable, Dio indicaciones precisas, Pagó completo, Conoce la zona.
     */
    static final Set<String> ELOGIOS_AL_CONDUCTOR = Set.of("Manejó con cuidado", "Muy amable", "Llegó rápido",
            "Conoce la zona", "Me prestó casco", "Carro limpio");
    static final Set<String> ELOGIOS_AL_PASAJERO = Set.of("Buena conducta", "Muy amable", "Dio indicaciones precisas",
            "Pagó completo", "Conoce la zona");

    public void calificar(Long servicioId, int puntos, String comentario) {
        calificar(servicioId, puntos, comentario, List.of());
    }

    @Transactional
    public void calificar(Long servicioId, int puntos, String comentario, List<String> elogios) {
        Long yo = sesion.usuarioId();
        Map<String, Object> s = db.queryForList("SELECT cliente_id, conductor_id FROM servicios WHERE id = ?", servicioId)
                .stream().findFirst().orElseThrow(() -> new RecursoNoEncontradoException("Servicio no encontrado"));
        Long cliente = Datos.aLong(s.get("cliente_id"));
        Long conductor = Datos.aLong(s.get("conductor_id"));
        Long calificado;
        if (Objects.equals(cliente, yo)) calificado = conductor;
        else if (Objects.equals(conductor, yo)) calificado = cliente;
        else throw new AccesoDenegadoException("Solo puedes calificar tus propios servicios");
        if (calificado == null) throw new ReglaNegocioException("Este servicio no tuvo conductor");
        Set<String> permitidos = Objects.equals(cliente, yo) ? ELOGIOS_AL_CONDUCTOR : ELOGIOS_AL_PASAJERO;
        List<String> lista = elogios == null ? List.of() : elogios.stream().filter(Objects::nonNull).map(String::trim).distinct().toList();
        if (!permitidos.containsAll(lista)) throw new ReglaNegocioException(400, "Ese elogio no está en la lista");
        // El trigger revisa que esté finalizado y dentro del plazo, y actualiza el promedio
        db.update("""
                INSERT INTO calificaciones (servicio_id, calificador_id, calificado_id, puntuacion, comentario, elogios)
                VALUES (?, ?, ?, ?, ?, CASE WHEN ? = '' THEN '{}'::varchar[] ELSE string_to_array(?, '|')::varchar[] END)
                """, servicioId, yo, calificado, puntos, Datos.vacioANull(comentario), String.join("|", lista), String.join("|", lista));
    }

    // ═════════════════════════════ Utilidades ═════════════════════════════

    // ═════════════════════════════ Nota de voz ═════════════════════════════

    /** Solo quien pidió el servicio, mientras se busca conductor. Reemplaza la anterior si había. */
    @Transactional
    public Map<String, Object> guardarNotaVoz(Long servicioId, ArchivoServicio archivos, MultipartFile audio, int segundos) {
        Map<String, Object> s = db.queryForList(
                "SELECT cliente_id, estado, nota_voz_archivo FROM servicios WHERE id = ? FOR UPDATE", servicioId)
                .stream().findFirst().orElseThrow(() -> new RecursoNoEncontradoException("Servicio no encontrado"));
        if (!Objects.equals(Datos.aLong(s.get("cliente_id")), sesion.usuarioId())) {
            throw new AccesoDenegadoException("Solo quien pidió el servicio puede agregar la nota de voz");
        }
        if (!BUSCANDO.equals(s.get("estado")) && !ASIGNADO.equals(s.get("estado"))) {
            throw new ReglaNegocioException(400, "El servicio ya no admite notas de voz");
        }
        ArchivoServicio.Guardado g = archivos.guardarAudio(audio, "nota_voz");
        db.update("UPDATE servicios SET nota_voz_archivo = ?, nota_voz_segundos = ? WHERE id = ?",
                g.nombre(), Math.max(1, Math.min(60, segundos)), servicioId);
        archivos.borrar((String) s.get("nota_voz_archivo"));
        return Map.of("message", "Nota de voz guardada", "segundos", Math.max(1, Math.min(60, segundos)));
    }

    /** La escuchan el cliente, el conductor asignado y los conductores a los que se les ofreció (mismas reglas que obtener). */
    public ArchivoServicio.Archivo notaVoz(Long servicioId, ArchivoServicio archivos) {
        obtener(servicioId); // valida que pueda verlo
        String nombre = db.queryForObject("SELECT nota_voz_archivo FROM servicios WHERE id = ?", String.class, servicioId);
        if (nombre == null) throw new RecursoNoEncontradoException("Este servicio no tiene nota de voz");
        return archivos.obtener(nombre);
    }

    Map<String, Object> fila(Long id) {
        return db.queryForList("SELECT * FROM v_servicios_api WHERE id = ?", id).stream().findFirst()
                .orElseThrow(() -> new RecursoNoEncontradoException("Servicio no encontrado"));
    }

    private static String direccion(String texto, double lat, double lng) {
        String t = Datos.vacioANull(texto);
        return t != null ? Datos.recortar(t, 200) : String.format(Locale.US, "Punto en el mapa (%.5f, %.5f)", lat, lng);
    }

    static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a.trim() : (b != null && !b.isBlank() ? b.trim() : null);
    }
}
