package com.movify.backend.servicios;

import static com.movify.backend.servicios.EstadosServicio.*;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.web.multipart.MultipartFile;

import com.movify.backend.comun.AccesoDenegadoException;
import com.movify.backend.comun.ArchivoServicio;
import com.movify.backend.comun.ActorBd;
import com.movify.backend.comun.Celular;
import com.movify.backend.comun.ContenidoProhibido;
import com.movify.backend.comun.Datos;
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
    /** Lo máximo que el domiciliario adelanta de su bolsillo por un pedido (propuesta; ajustar con el negocio). */
    static final BigDecimal TOPE_COMPRA_DOMICILIO = new BigDecimal("500000");

    /** Motivo de cancelación por defecto según quién cancela (motivos_cancelacion). */
    private static final Map<String, Short> MOTIVO_POR_DEFECTO = Map.of(
            ActorBd.CLIENTE, (short) 1, ActorBd.CONDUCTOR, (short) 11, ActorBd.ADMIN, (short) 20,
            ActorBd.SOPORTE, (short) 20);

    private final JdbcTemplate db;
    private final SesionActual sesion;
    private final TarifaServicio tarifas;
    private final ActorBd actor;
    private final ObjectProvider<SimulacionDemo> simulacion;
    private final EncomiendaServicio encomiendas;

    public ViajeServicio(JdbcTemplate db, SesionActual sesion, TarifaServicio tarifas, ActorBd actor,
            ObjectProvider<SimulacionDemo> simulacion, EncomiendaServicio encomiendas) {
        this.db = db;
        this.sesion = sesion;
        this.tarifas = tarifas;
        this.actor = actor;
        this.simulacion = simulacion;
        this.encomiendas = encomiendas;
    }

    // ═════════════════════════════ Solicitud ═════════════════════════════

    @Transactional
    public Map<String, Object> crearSolicitud(SolicitudRequest s) {
        Long clienteId = sesion.usuarioId();
        String tipo = EstadosServicio.tipoServicio(s.tipo());
        String vehiculo = s.tipoVehiculo() == null || s.tipoVehiculo().isBlank()
                ? "moto" : s.tipoVehiculo().trim().toLowerCase(Locale.ROOT);
        if (s.metodoPago() != null && !s.metodoPago().isBlank() && !"efectivo".equalsIgnoreCase(s.metodoPago().trim())) {
            throw new ReglaNegocioException(400, "Por ahora en MoviFY solo se paga en efectivo.");
        }

        TarifaServicio.Ruta ruta = tarifas.ruta(s.origenLat(), s.origenLng(), s.destinoLat(), s.destinoLng());
        actor.declarar(clienteId, ActorBd.CLIENTE);

        Map<String, Object> fila = db.queryForMap("""
                INSERT INTO servicios (tipo_servicio, tipo_vehiculo, cliente_id,
                    origen_direccion, origen_barrio, origen_referencia, origen_lat, origen_lng,
                    destino_direccion, destino_barrio, destino_referencia, destino_lat, destino_lng,
                    distancia_km, duracion_estimada_min, pasajeros, notas_cliente)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id, valor_estimado, expira_busqueda_en
                """, tipo, vehiculo, clienteId,
                direccion(s.origen(), s.origenLat(), s.origenLng()), Datos.vacioANull(s.origenBarrio()),
                Datos.vacioANull(s.origenReferencia()), s.origenLat(), s.origenLng(),
                direccion(s.destino(), s.destinoLat(), s.destinoLng()), Datos.vacioANull(s.destinoBarrio()),
                Datos.vacioANull(s.destinoReferencia()), s.destinoLat(), s.destinoLng(),
                ruta.distanciaKm(), ruta.duracionMin(), s.pasajeros() == null ? 1 : s.pasajeros(),
                Datos.vacioANull("transporte".equals(tipo) ? Datos.recortar(firstNonBlank(s.notas(), s.descripcion()), 300)
                        : s.notas()));
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

        int notificados = ofertar(servicioId, s.origenLat(), s.origenLng(), vehiculo, clienteId);
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

    private int ofertar(Long servicioId, double lat, double lng, String vehiculo, Long clienteId) {
        return db.update("""
                INSERT INTO servicio_ofertas (servicio_id, conductor_id, distancia_km)
                SELECT ?, c.conductor_id, round(c.distancia_km::numeric, 2)
                FROM fn_conductores_cercanos(?, ?, fn_config_num('radio_busqueda_km', 5)::float8, ?, ?) c
                ON CONFLICT DO NOTHING
                """, servicioId, lat, lng, vehiculo, clienteId);
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
        if (compra && valor.compareTo(TOPE_COMPRA_DOMICILIO) > 0) {
            throw new ReglaNegocioException(400, "Por ahora el domiciliario puede pagar pedidos de hasta $"
                    + String.format(Locale.ROOT, "%,d", TOPE_COMPRA_DOMICILIO.longValue()).replace(',', '.')
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
                       v.usuario_nombre, v.expira_busqueda_en, v.estado, s.nota_voz_segundos
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
                           v.placa AS conductor_placa, trim(v.marca || ' ' || COALESCE(v.linea, '')) AS conductor_modelo,
                           v.color AS conductor_color, v.tipo_vehiculo AS conductor_vehiculo
                    FROM conductores c
                    JOIN usuarios u ON u.id = c.usuario_id
                    LEFT JOIN ubicacion_conductores ub ON ub.conductor_id = c.usuario_id
                    LEFT JOIN vehiculos v ON v.id = ?
                    WHERE c.usuario_id = ?
                    """, s.get("vehiculo_id"), conductorId).stream().findFirst().ifPresent(s::putAll);
        }
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
                return respuesta(servicioId, tipo, ASIGNADO, "Servicio aceptado. Ve a recoger al cliente.");
            }
            case CANCELADO -> {
                // El conductor no "cancela" un servicio recién aceptado: lo suelta y vuelve a buscarse otro conductor
                if (ActorBd.CONDUCTOR.equals(quien) && ASIGNADO.equals(actual)) {
                    actualizar(servicioId, actual, BUSCANDO, null);
                    Map<String, Object> o = db.queryForMap(
                            "SELECT origen_lat, origen_lng FROM servicios WHERE id = ?", servicioId);
                    ofertar(servicioId, Datos.num(o.get("origen_lat")), Datos.num(o.get("origen_lng")),
                            String.valueOf(s.get("tipo_vehiculo")), cliente);
                    return respuesta(servicioId, tipo, BUSCANDO, "Soltaste el servicio. Buscaremos otro conductor.");
                }
                Short motivo = req.motivoId() != null ? req.motivoId() : MOTIVO_POR_DEFECTO.get(quien);
                db.update("""
                        UPDATE servicios SET estado = 'cancelado', motivo_cancelacion_id = ?, detalle_cancelacion = ?,
                                             cancelado_por = ?
                        WHERE id = ? AND estado = ?
                        """, motivo, Datos.vacioANull(req.motivo()), yo.id(), servicioId, actual);
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
                return respuesta(servicioId, tipo, destino, "Estado actualizado.");
            }
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

    @Transactional
    public void calificar(Long servicioId, int puntos, String comentario) {
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
        // El trigger revisa que esté finalizado y dentro del plazo, y actualiza el promedio
        db.update("""
                INSERT INTO calificaciones (servicio_id, calificador_id, calificado_id, puntuacion, comentario)
                VALUES (?, ?, ?, ?, ?)
                """, servicioId, yo, calificado, puntos, Datos.vacioANull(comentario));
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
