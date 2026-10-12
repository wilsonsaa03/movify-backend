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

import com.movify.backend.comun.AccesoDenegadoException;
import com.movify.backend.comun.ActorBd;
import com.movify.backend.comun.Celular;
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

    /** Motivo de cancelación por defecto según quién cancela (motivos_cancelacion). */
    private static final Map<String, Short> MOTIVO_POR_DEFECTO = Map.of(
            ActorBd.CLIENTE, (short) 1, ActorBd.CONDUCTOR, (short) 11, ActorBd.ADMIN, (short) 20,
            ActorBd.SOPORTE, (short) 20);

    private final JdbcTemplate db;
    private final SesionActual sesion;
    private final TarifaServicio tarifas;
    private final ActorBd actor;
    private final ObjectProvider<SimulacionDemo> simulacion;

    public ViajeServicio(JdbcTemplate db, SesionActual sesion, TarifaServicio tarifas, ActorBd actor,
            ObjectProvider<SimulacionDemo> simulacion) {
        this.db = db;
        this.sesion = sesion;
        this.tarifas = tarifas;
        this.actor = actor;
        this.simulacion = simulacion;
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
        switch (tipo) {
            case "domicilio" -> crearDomicilio(servicioId, s);
            case "encomienda" -> codigoEntrega = crearEncomienda(servicioId, s, clienteId);
            default -> {
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
        boolean compra = Boolean.TRUE.equals(s.requiereCompra());
        if (compra && s.valorProductosAprox() == null) {
            throw new ReglaNegocioException(400, "Indica cuánto cuestan más o menos los productos");
        }
        String tel = s.telefonoContacto() == null || s.telefonoContacto().isBlank() ? null
                : Celular.normalizarContacto(s.telefonoContacto());
        db.update("""
                INSERT INTO servicio_domicilio (servicio_id, establecimiento, descripcion_pedido, requiere_compra,
                                                valor_productos_aprox, telefono_contacto)
                VALUES (?, ?, ?, ?, ?, ?)
                """, servicioId, Datos.vacioANull(s.establecimiento()), descripcion, compra, s.valorProductosAprox(), tel);
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

    /** @return el PIN de 4 dígitos que el destinatario le dará al conductor al recibir. */
    private String crearEncomienda(Long servicioId, SolicitudRequest s, Long clienteId) {
        Map<String, Object> cli = db.queryForMap(
                "SELECT trim(nombres || ' ' || COALESCE(apellidos, '')) AS nombre, telefono FROM usuarios WHERE id = ?",
                clienteId);
        String remitente = firstNonBlank(s.nombreRemitente(), Datos.texto(cli.get("nombre")));
        String telRemitente = Celular.normalizarContacto(firstNonBlank(s.telefonoRemitente(), Datos.texto(cli.get("telefono"))));
        String telDestinatario = Celular.normalizarContacto(s.telefonoDestinatario());
        if (telRemitente == null) throw new ReglaNegocioException(400, "Escribe un teléfono válido para quien envía");
        if (Datos.vacioANull(s.nombreDestinatario()) == null) throw new ReglaNegocioException(400, "¿A quién le llega el paquete?");
        if (telDestinatario == null) throw new ReglaNegocioException(400, "Escribe un teléfono válido para quien recibe");
        String descripcion = Datos.vacioANull(s.descripcion());
        if (descripcion == null) throw new ReglaNegocioException(400, "Describe qué vas a enviar");

        BigDecimal peso;
        try {
            peso = new BigDecimal(firstNonBlank(s.pesoKg(), "1").replace(",", ".").replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException e) {
            throw new ReglaNegocioException(400, "El peso no es válido");
        }
        String pin = String.format("%04d", RANDOM.nextInt(10_000));
        db.update("""
                INSERT INTO servicio_encomienda (servicio_id, remitente_nombre, remitente_telefono, destinatario_nombre,
                    destinatario_telefono, descripcion_paquete, tamano, peso_kg, fragil, valor_declarado, codigo_entrega)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, servicioId, Datos.recortar(remitente, 120), telRemitente, Datos.recortar(s.nombreDestinatario(), 120),
                telDestinatario, Datos.recortar(descripcion, 300), tamano(s.tamano(), s.tipoPaquete()), peso,
                Boolean.TRUE.equals(s.fragil()), s.valorDeclarado(), pin);
        return pin;
    }

    /** "documento", "caja-pequeña"... (web) → pequeno | mediano | grande. */
    static String tamano(String tamano, String tipoPaquete) {
        String t = firstNonBlank(tamano, tipoPaquete);
        t = t == null ? "pequeno" : t.toLowerCase(Locale.ROOT).replace("ñ", "n");
        if (t.contains("grande")) return "grande";
        if (t.contains("median")) return "mediano";
        return "pequeno";
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
                       v.usuario_nombre, v.expira_busqueda_en, v.estado
                FROM servicio_ofertas o
                JOIN v_servicios_api v ON v.id = o.servicio_id
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
            s.put("codigo_entrega", db.queryForObject(
                    "SELECT codigo_entrega FROM servicio_encomienda WHERE servicio_id = ?", String.class, servicioId));
        }
        if ("domicilio".equals(s.get("tipo_servicio"))) {
            s.put("items", db.queryForList("""
                    SELECT descripcion, cantidad, precio_unitario_aprox, notas FROM servicio_domicilio_items
                    WHERE servicio_id = ? ORDER BY id
                    """, servicioId));
        }
        // El celular del cliente solo lo ve el conductor asignado (y el personal), no quien recibe la oferta
        if (!soyCliente && !sesion.esPersonal() && !Objects.equals(conductorId, yo.id())) s.remove("usuario_telefono");
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

    /** Antes de finalizar: PIN de la encomienda y valor real de la compra del domicilio. */
    private void prepararCierre(Long servicioId, String tipo, CambioEstadoRequest req, String quien) {
        if ("encomienda".equals(tipo)) {
            Map<String, Object> e = db.queryForMap(
                    "SELECT codigo_entrega, destinatario_nombre FROM servicio_encomienda WHERE servicio_id = ?", servicioId);
            boolean personal = ActorBd.ADMIN.equals(quien) || ActorBd.SOPORTE.equals(quien);
            if (!personal && !String.valueOf(e.get("codigo_entrega")).equals(req.codigoEntrega())) {
                throw new ReglaNegocioException(400, "El código de entrega no coincide. Pídeselo a quien recibe el paquete.");
            }
            db.update("UPDATE servicio_encomienda SET entregado_a = ? WHERE servicio_id = ?",
                    firstNonBlank(req.entregadoA(), Datos.texto(e.get("destinatario_nombre"))), servicioId);
        }
        if ("domicilio".equals(tipo) && req.valorCompraReal() != null) {
            db.update("UPDATE servicio_domicilio SET valor_compra_real = ? WHERE servicio_id = ?", req.valorCompraReal(),
                    servicioId);
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
