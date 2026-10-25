package com.movify.backend.servicios;

import static com.movify.backend.servicios.EstadosServicio.BUSCANDO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.movify.backend.comun.AccesoDenegadoException;
import com.movify.backend.comun.ActorBd;
import com.movify.backend.comun.ArchivoServicio;
import com.movify.backend.comun.Datos;
import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.comun.ReglaNegocioException;
import com.movify.backend.notificaciones.NotificacionServicio;
import com.movify.backend.seguridad.SesionActual;
import com.movify.backend.servicios.ServicioDtos.AlertaViajeRequest;
import com.movify.backend.servicios.ServicioDtos.CambioDestinoRequest;

/**
 * Lo que el pasajero puede hacer con su viaje además de pedirlo y cancelarlo (como en Uber, DiDi e inDrive):
 * subir la tarifa si nadie acepta, cambiar el destino o la parada, compartir el viaje en vivo, responder
 * "¿Todo bien?", marcar al conductor como favorito o bloquearlo y ver la foto del vehículo.
 */
@Service
public class ViajeOpcionesServicio {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final BigDecimal CIEN = new BigDecimal("100");
    private static final Set<String> ACTIVOS = Set.of("asignado", "en_origen", "en_curso");
    private static final String NO_ENCONTRADO = "Servicio no encontrado";

    private final JdbcTemplate db;
    private final SesionActual sesion;
    private final ActorBd actor;
    private final TarifaServicio tarifas;
    private final ViajeServicio viajes;
    private final NotificacionServicio avisos;

    @Value("${app.frontend.url:https://movify.ds2.eleueleo.com}")
    private String urlWeb;

    public ViajeOpcionesServicio(JdbcTemplate db, SesionActual sesion, ActorBd actor, TarifaServicio tarifas,
            ViajeServicio viajes, NotificacionServicio avisos) {
        this.db = db;
        this.sesion = sesion;
        this.actor = actor;
        this.tarifas = tarifas;
        this.viajes = viajes;
        this.avisos = avisos;
    }

    // ═════════════════════════════ Subir la tarifa ═════════════════════════════

    /**
     * Nadie acepta: el pasajero ofrece más. La búsqueda se reinicia y los conductores que ya la habían
     * rechazado la vuelven a ver con el precio nuevo. Tope: max_aumento_tarifa_pct sobre lo cotizado.
     */
    @Transactional
    public Map<String, Object> subirTarifa(Long servicioId, BigDecimal valor) {
        Map<String, Object> s = servicioDelCliente(servicioId);
        if (!BUSCANDO.equals(s.get("estado"))) {
            throw new ReglaNegocioException(400, "Ya no estamos buscando conductor para este servicio.");
        }
        int veces = ((Number) s.get("aumentos_tarifa")).intValue();
        int maximo = db.queryForObject("SELECT fn_config_num('max_aumentos_tarifa', 3)::int", Integer.class);
        if (veces >= maximo) {
            throw new ReglaNegocioException(400, "Ya subiste la tarifa " + maximo + " veces. Prueba cancelar y pedir de nuevo.");
        }
        BigDecimal actual = (BigDecimal) s.get("valor_estimado");
        BigDecimal sugerido = s.get("valor_sugerido") == null ? actual : (BigDecimal) s.get("valor_sugerido");
        BigDecimal nuevo = valor.divide(CIEN, 0, RoundingMode.CEILING).multiply(CIEN);
        if (nuevo.compareTo(actual) <= 0) {
            throw new ReglaNegocioException(400, "La nueva tarifa tiene que ser mayor a " + ViajeServicio.pesos(actual) + ".");
        }
        BigDecimal tope = tarifas.maximoOfrecible(sugerido);
        if (nuevo.compareTo(tope) > 0) {
            throw new ReglaNegocioException(400, "Puedes ofrecer hasta " + ViajeServicio.pesos(tope) + " por este viaje.");
        }

        actor.declarar(sesion.usuarioId(), ActorBd.CLIENTE);
        Map<String, Object> f = db.queryForMap("""
                UPDATE servicios
                   SET valor_estimado = ?, aumentos_tarifa = aumentos_tarifa + 1,
                       expira_busqueda_en = now() + make_interval(secs => fn_config_num('tiempo_busqueda_seg', 120)::int)
                 WHERE id = ? AND estado = 'buscando'
                RETURNING valor_estimado, expira_busqueda_en
                """, nuevo, servicioId);
        // Los que la rechazaron con el precio anterior la vuelven a recibir
        List<Long> otraVez = db.queryForList("""
                UPDATE servicio_ofertas SET estado = 'enviada', respondida_en = NULL, enviada_en = now()
                 WHERE servicio_id = ? AND estado IN ('rechazada', 'expirada')
                RETURNING conductor_id
                """, Long.class, servicioId);
        if (!otraVez.isEmpty()) {
            avisos.avisarVarios(otraVez, servicioId, NotificacionServicio.SOLICITUD, "solicitud", "¡Subieron la tarifa!",
                    "Ahora pagan " + ViajeServicio.pesos(nuevo) + ". ¿La tomas?");
        }
        int nuevos = viajes.ofertar(servicioId);

        Map<String, Object> r = new HashMap<>(f);
        r.put("tarifa", f.get("valor_estimado"));
        r.put("conductores_notificados", otraVez.size() + nuevos);
        r.put("aumentos_tarifa", veces + 1);
        r.put("message", "Listo, ofreces " + ViajeServicio.pesos(nuevo) + ". Seguimos buscando.");
        return r;
    }

    // ═════════════════════════════ Cambiar el destino ═════════════════════════════

    /**
     * Con el viaje andando el pasajero cambia el destino o la parada. El precio se recalcula con la tarifa
     * activa (más lo que haya ofrecido de más) y el conductor recibe un aviso.
     */
    @Transactional
    public Map<String, Object> cambiarDestino(Long servicioId, CambioDestinoRequest r) {
        Map<String, Object> s = servicioDelCliente(servicioId);
        if (!"transporte".equals(s.get("tipo_servicio"))) {
            throw new ReglaNegocioException(400, "El destino solo se puede cambiar en los viajes.");
        }
        if (!ACTIVOS.contains(String.valueOf(s.get("estado")))) {
            throw new ReglaNegocioException(400, "El destino se cambia cuando el conductor ya va por ti o estás en el viaje.");
        }
        if ((r.paradaLat() == null) != (r.paradaLng() == null)) {
            throw new ReglaNegocioException(400, "Marca la parada en el mapa");
        }
        double oLat = Datos.num(s.get("origen_lat"));
        double oLng = Datos.num(s.get("origen_lng"));
        if (oLat == r.destinoLat() && oLng == r.destinoLng()) {
            throw new ReglaNegocioException(400, "El destino no puede ser el mismo punto de recogida.");
        }
        String vehiculo = String.valueOf(s.get("tipo_vehiculo"));
        TarifaServicio.Ruta ruta = tarifas.ruta(oLat, oLng, r.paradaLat(), r.paradaLng(), r.destinoLat(), r.destinoLng());
        BigDecimal cotizado = tarifas.cotizar("transporte", vehiculo, ruta);
        BigDecimal ofrecidoDeMas = ((BigDecimal) s.get("valor_estimado"))
                .subtract(s.get("valor_sugerido") == null ? (BigDecimal) s.get("valor_estimado") : (BigDecimal) s.get("valor_sugerido"))
                .max(BigDecimal.ZERO);
        BigDecimal nuevo = cotizado.add(ofrecidoDeMas);
        String destino = Datos.recortar(r.destino(), 200);
        String parada = r.paradaLat() == null ? null
                : Datos.vacioANull(r.parada()) != null ? Datos.recortar(r.parada(), 200) : "Parada en el mapa";

        actor.declarar(sesion.usuarioId(), ActorBd.CLIENTE);
        db.update("""
                UPDATE servicios
                   SET destino_direccion = ?, destino_barrio = ?, destino_lat = ?, destino_lng = ?,
                       parada_direccion = ?, parada_lat = ?, parada_lng = ?,
                       distancia_km = ?, duracion_estimada_min = ?, valor_sugerido = ?, valor_estimado = ?,
                       destino_cambiado_en = now()
                 WHERE id = ? AND estado IN ('asignado', 'en_origen', 'en_curso')
                """, destino, Datos.vacioANull(r.destinoBarrio()), r.destinoLat(), r.destinoLng(), parada, r.paradaLat(),
                r.paradaLng(), ruta.distanciaKm(), ruta.duracionMin(), cotizado, nuevo, servicioId);

        avisos.avisar(Datos.aLong(s.get("conductor_id")), servicioId, NotificacionServicio.ESTADO, "destino",
                "El pasajero cambió el destino", "Ahora va a " + destino.split(",")[0] + " · "
                        + ViajeServicio.pesos(nuevo) + (parada != null ? " · con una parada" : ""));
        return Map.of("tarifa", nuevo, "distancia_km", ruta.distanciaKm(), "duracion_min", ruta.duracionMin(),
                "message", "Destino actualizado. Le avisamos a tu conductor.");
    }

    // ═════════════════════════════ Compartir el viaje en vivo ═════════════════════════════

    /** Enlace para que la familia vea el viaje en el mapa (sin cuenta). Si ya hay uno vigente, se reutiliza. */
    @Transactional
    public Map<String, Object> compartir(Long servicioId) {
        Map<String, Object> s = servicioDelCliente(servicioId);
        String estado = String.valueOf(s.get("estado"));
        if (!BUSCANDO.equals(estado) && !ACTIVOS.contains(estado)) {
            throw new ReglaNegocioException(400, "Este viaje ya terminó.");
        }
        Map<String, Object> e = db.queryForList("""
                SELECT token, expira_en FROM viajes_compartidos
                WHERE servicio_id = ? AND revocado_en IS NULL AND expira_en > now() + interval '30 minutes'
                ORDER BY creado_en DESC LIMIT 1
                """, servicioId).stream().findFirst().orElse(null);
        if (e == null) {
            byte[] b = new byte[32];
            RANDOM.nextBytes(b);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
            e = db.queryForMap("""
                    INSERT INTO viajes_compartidos (token, servicio_id, usuario_id, expira_en)
                    VALUES (?, ?, ?, now() + make_interval(hours => fn_config_num('horas_enlace_compartido', 6)::int))
                    RETURNING token, expira_en
                    """, token, servicioId, sesion.usuarioId());
        }
        return Map.of("url", urlWeb.replaceAll("/+$", "") + "/seguir/" + e.get("token"), "token", e.get("token"),
                "expira_en", e.get("expira_en"));
    }

    /**
     * Lo que ve quien abre el enlace: estado, primer nombre del conductor, vehículo, placa y dónde va.
     * Nunca celulares ni apellidos. La ubicación solo mientras el viaje está activo.
     */
    @Transactional
    public Map<String, Object> verCompartido(String token) {
        if (token == null || !token.matches("^[A-Za-z0-9_-]{32,43}$")) {
            throw new RecursoNoEncontradoException("Este enlace no existe");
        }
        Map<String, Object> c = db.queryForList("""
                SELECT servicio_id, (revocado_en IS NOT NULL OR expira_en < now()) AS vencido
                FROM viajes_compartidos WHERE token = ?
                """, token).stream().findFirst()
                .orElseThrow(() -> new RecursoNoEncontradoException("Este enlace no existe"));
        if (Boolean.TRUE.equals(c.get("vencido"))) throw new ReglaNegocioException(410, "Este enlace ya venció");
        db.update("UPDATE viajes_compartidos SET vistas = vistas + 1 WHERE token = ?", token);
        Map<String, Object> v = new HashMap<>(db.queryForMap("""
                SELECT s.estado AS estado_codigo, e.nombre AS estado_nombre, s.tipo_vehiculo,
                       COALESCE(split_part(trim(s.pasajero_nombre), ' ', 1), split_part(trim(cli.nombres), ' ', 1)) AS viajero,
                       split_part(trim(con.nombres), ' ', 1) AS conductor, ve.placa,
                       trim(ve.marca || ' ' || COALESCE(ve.linea, '')) AS modelo, ve.color,
                       COALESCE(s.origen_barrio, split_part(s.origen_direccion, ',', 1)) AS origen,
                       split_part(s.destino_direccion, ',', 1) AS destino, s.destino_barrio,
                       s.origen_lat, s.origen_lng, s.destino_lat, s.destino_lng,
                       split_part(s.parada_direccion, ',', 1) AS parada, s.parada_lat, s.parada_lng,
                       s.duracion_estimada_min, s.iniciado_en, s.finalizado_en,
                       CASE WHEN s.estado IN ('asignado', 'en_origen', 'en_curso') THEN ub.lat END AS conductor_lat,
                       CASE WHEN s.estado IN ('asignado', 'en_origen', 'en_curso') THEN ub.lng END AS conductor_lng,
                       CASE WHEN s.estado IN ('asignado', 'en_origen', 'en_curso') THEN ub.actualizado_en END
                           AS ubicacion_en
                FROM servicios s
                JOIN estados e ON e.ambito = 'servicio' AND e.codigo = s.estado
                JOIN usuarios cli ON cli.id = s.cliente_id
                LEFT JOIN usuarios con ON con.id = s.conductor_id
                LEFT JOIN vehiculos ve ON ve.id = s.vehiculo_id
                LEFT JOIN ubicacion_conductores ub ON ub.conductor_id = s.conductor_id
                WHERE s.id = ?
                """, c.get("servicio_id")));
        return Datos.sinNulos(v);
    }

    // ═════════════════════════════ ¿Todo bien? ═════════════════════════════

    /** La app vio un desvío o una parada larga y el pasajero respondió (o no respondió). */
    public Map<String, Object> alerta(Long servicioId, AlertaViajeRequest r) {
        Map<String, Object> s = servicioDelCliente(servicioId);
        if (!ACTIVOS.contains(String.valueOf(s.get("estado")))) {
            throw new ReglaNegocioException(400, "El viaje ya no está activo.");
        }
        Long id = db.queryForObject("""
                INSERT INTO alertas_viaje (servicio_id, usuario_id, tipo, respuesta, lat, lng)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, servicioId, sesion.usuarioId(), r.tipo(), r.respuesta(), r.lat(), r.lng());
        return Map.of("id", id, "message", "estoy_bien".equals(r.respuesta())
                ? "Gracias. Seguimos pendientes de tu viaje." : "Soporte de MoviFY ya está revisando tu viaje.");
    }

    // ═════════════════════════════ Favorito y bloqueo ═════════════════════════════

    @Transactional
    public Map<String, Object> favorito(Long servicioId, boolean marcar) {
        Long conductor = conductorDelServicio(servicioId);
        if (marcar) {
            db.update("""
                    INSERT INTO conductores_favoritos (cliente_id, conductor_id) VALUES (?, ?) ON CONFLICT DO NOTHING
                    """, sesion.usuarioId(), conductor);
            db.update("DELETE FROM usuarios_bloqueados WHERE usuario_id = ? AND bloqueado_id = ?", sesion.usuarioId(), conductor);
        } else {
            db.update("DELETE FROM conductores_favoritos WHERE cliente_id = ? AND conductor_id = ?", sesion.usuarioId(),
                    conductor);
        }
        return Map.of("es_favorito", marcar, "message", marcar
                ? "Guardado en tus favoritos: le ofreceremos tus viajes primero." : "Ya no está en tus favoritos.");
    }

    /** "No volver a emparejarme con este conductor" (la BD lo respeta al ofrecer y al aceptar). */
    @Transactional
    public Map<String, Object> bloquear(Long servicioId, String motivo) {
        Long conductor = conductorDelServicio(servicioId);
        db.update("""
                INSERT INTO usuarios_bloqueados (usuario_id, bloqueado_id, motivo) VALUES (?, ?, ?)
                ON CONFLICT (usuario_id, bloqueado_id) DO UPDATE SET motivo = EXCLUDED.motivo
                """, sesion.usuarioId(), conductor, Datos.recortar(Datos.vacioANull(motivo), 200));
        db.update("DELETE FROM conductores_favoritos WHERE cliente_id = ? AND conductor_id = ?", sesion.usuarioId(), conductor);
        return Map.of("message", "Listo. No te volveremos a emparejar con este conductor.");
    }

    // ═════════════════════════════ Foto del vehículo ═════════════════════════════

    /** Foto de frente del vehículo asignado: el pasajero reconoce el carro o la moto antes de subirse. */
    public ArchivoServicio.Archivo fotoVehiculo(Long servicioId, ArchivoServicio archivos) {
        Map<String, Object> s = db.queryForList("SELECT cliente_id, conductor_id, vehiculo_id FROM servicios WHERE id = ?",
                servicioId).stream().findFirst().orElseThrow(() -> new RecursoNoEncontradoException(NO_ENCONTRADO));
        Long yo = sesion.usuarioId();
        if (!Objects.equals(Datos.aLong(s.get("cliente_id")), yo) && !Objects.equals(Datos.aLong(s.get("conductor_id")), yo)
                && !sesion.esPersonal()) {
            throw new AccesoDenegadoException("No puedes ver este vehículo");
        }
        String ruta = db.queryForList("""
                SELECT ruta_archivo FROM documentos_conductor
                WHERE vehiculo_id = ? AND tipo_documento = 'foto_frente' AND vigente AND mime_type LIKE 'image/%'
                """, String.class, s.get("vehiculo_id")).stream().findFirst()
                .orElseThrow(() -> new RecursoNoEncontradoException("Este vehículo no tiene foto"));
        return archivos.obtener(ruta);
    }

    // ═════════════════════════════ Conductores libres en el mapa ═════════════════════════════

    /**
     * Los carritos del mapa antes de pedir. Posición redondeada (~100 m) y sin datos del conductor:
     * se ve que hay oferta cerca sin poder seguir a nadie.
     */
    public List<Map<String, Object>> cercanos(double lat, double lng, String tipoVehiculo) {
        return db.queryForList("""
                SELECT round(lat::numeric, 3) AS lat, round(lng::numeric, 3) AS lng, tipo_vehiculo
                FROM v_conductores_en_linea
                WHERE NOT ocupado
                  AND actualizado_en > now() - make_interval(secs => fn_config_num('ubicacion_vigencia_seg', 300)::int)
                  AND (CAST(? AS varchar) IS NULL OR tipo_vehiculo = ?)
                  AND fn_distancia_km(?, ?, lat, lng) <= 3
                ORDER BY fn_distancia_km(?, ?, lat, lng)
                LIMIT 15
                """, tipoVehiculo, tipoVehiculo, lat, lng, lat, lng);
    }

    // ═════════════════════════════ Utilidades ═════════════════════════════

    private Map<String, Object> servicioDelCliente(Long servicioId) {
        Map<String, Object> s = db.queryForList("""
                SELECT id, tipo_servicio, tipo_vehiculo, estado, cliente_id, conductor_id, origen_lat, origen_lng,
                       valor_estimado, valor_sugerido, aumentos_tarifa
                FROM servicios WHERE id = ? FOR UPDATE
                """, servicioId).stream().findFirst().orElseThrow(() -> new RecursoNoEncontradoException(NO_ENCONTRADO));
        if (!Objects.equals(Datos.aLong(s.get("cliente_id")), sesion.usuarioId())) {
            throw new AccesoDenegadoException("Solo quien pidió el servicio puede hacer esto");
        }
        return s;
    }

    private Long conductorDelServicio(Long servicioId) {
        Map<String, Object> s = db.queryForList("SELECT cliente_id, conductor_id FROM servicios WHERE id = ?", servicioId)
                .stream().findFirst().orElseThrow(() -> new RecursoNoEncontradoException(NO_ENCONTRADO));
        if (!Objects.equals(Datos.aLong(s.get("cliente_id")), sesion.usuarioId())) {
            throw new AccesoDenegadoException("Solo quien pidió el servicio puede hacer esto");
        }
        Long conductor = Datos.aLong(s.get("conductor_id"));
        if (conductor == null) throw new ReglaNegocioException(400, "Este servicio no tuvo conductor.");
        return conductor;
    }
}
