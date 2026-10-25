package com.movify.backend.servicios;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.movify.backend.comun.Celular;
import com.movify.backend.comun.Datos;
import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.comun.ReglaNegocioException;
import com.movify.backend.notificaciones.NotificacionServicio;
import com.movify.backend.seguridad.SesionActual;
import com.movify.backend.servicios.ServicioDtos.ProgramadoRequest;
import com.movify.backend.servicios.ServicioDtos.SolicitudRequest;

/**
 * Viajes en moto o taxi para más tarde ("Mañana a las 5:30 a. m. al aeropuerto").
 * Se guardan en viajes_programados y, unos minutos antes de la hora (minutos_anticipo_programado),
 * una tarea los convierte en un servicio normal: desde ahí es el mismo flujo de siempre.
 */
@Service
public class ProgramadoServicio {

    private static final Logger log = LoggerFactory.getLogger(ProgramadoServicio.class);

    private final JdbcTemplate db;
    private final SesionActual sesion;
    private final TarifaServicio tarifas;
    private final ViajeServicio viajes;
    private final NotificacionServicio avisos;
    private final TransactionTemplate tx;

    public ProgramadoServicio(JdbcTemplate db, SesionActual sesion, TarifaServicio tarifas, ViajeServicio viajes,
            NotificacionServicio avisos, TransactionTemplate tx) {
        this.db = db;
        this.sesion = sesion;
        this.tarifas = tarifas;
        this.viajes = viajes;
        this.avisos = avisos;
        this.tx = tx;
    }

    public Map<String, Object> programar(ProgramadoRequest r) {
        Long cliente = sesion.usuarioId();
        String vehiculo = r.tipoVehiculo().trim().toLowerCase(Locale.ROOT);
        if (!"moto".equals(vehiculo) && !"taxi".equals(vehiculo)) {
            throw new ReglaNegocioException(400, "Programa un viaje en moto o en taxi.");
        }
        Map<String, Object> v = db.queryForMap("""
                SELECT ?::timestamptz >= now() + make_interval(mins => fn_config_num('min_minutos_programar', 30)::int) AS desde_ok,
                       ?::timestamptz <= now() + make_interval(days => fn_config_num('max_dias_programar', 7)::int) AS hasta_ok,
                       fn_config_num('min_minutos_programar', 30)::int AS min_minutos,
                       fn_config_num('max_dias_programar', 7)::int AS max_dias,
                       fn_config_num('max_programados_cliente', 5)::int AS max_pendientes,
                       (SELECT count(*) FROM viajes_programados WHERE cliente_id = ? AND estado = 'pendiente') AS pendientes
                """, r.programadoPara(), r.programadoPara(), cliente);
        if (!Boolean.TRUE.equals(v.get("desde_ok"))) {
            throw new ReglaNegocioException(400, "Programa tu viaje con al menos " + v.get("min_minutos") + " minutos de anticipación.");
        }
        if (!Boolean.TRUE.equals(v.get("hasta_ok"))) {
            throw new ReglaNegocioException(400, "Puedes programar hasta " + v.get("max_dias") + " días adelante.");
        }
        if (((Number) v.get("pendientes")).intValue() >= ((Number) v.get("max_pendientes")).intValue()) {
            throw new ReglaNegocioException(400, "Ya tienes " + v.get("max_pendientes") + " viajes programados. Cancela alguno para agregar otro.");
        }
        int pasajeros = r.pasajeros() == null ? 1 : r.pasajeros();
        if ("moto".equals(vehiculo) && pasajeros > 1) {
            throw new ReglaNegocioException(400, "En moto viaja 1 pasajero. Para más personas programa un taxi.");
        }
        boolean casco = Boolean.TRUE.equals(r.necesitaCasco());
        if (casco && !"moto".equals(vehiculo)) {
            throw new ReglaNegocioException(400, "El casco para el pasajero solo aplica en viajes en moto.");
        }
        String nombre = Datos.vacioANull(r.pasajeroNombre());
        String tel = Datos.vacioANull(r.pasajeroTelefono()) == null ? null : Celular.exigir(r.pasajeroTelefono());
        if ((nombre == null) != (tel == null)) {
            throw new ReglaNegocioException(400, "Escribe el nombre y el celular de quien va a viajar.");
        }
        // Precio de referencia (puede cambiar si a la hora del viaje aplica recargo nocturno o cambió la tarifa)
        BigDecimal cotizado = tarifas.cotizar("transporte", vehiculo,
                tarifas.ruta(r.origenLat(), r.origenLng(), r.destinoLat(), r.destinoLng()));

        Long id = db.queryForObject("""
                INSERT INTO viajes_programados (cliente_id, tipo_vehiculo, origen_direccion, origen_barrio, origen_referencia,
                    origen_lat, origen_lng, destino_direccion, destino_barrio, destino_lat, destino_lng, pasajeros,
                    necesita_casco, lleva_equipaje, lleva_mascota, pasajero_nombre, pasajero_telefono, valor_cotizado,
                    programado_para)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, cliente, vehiculo, texto(r.origen(), "Punto de recogida"), Datos.vacioANull(r.origenBarrio()),
                Datos.vacioANull(r.origenReferencia()), r.origenLat(), r.origenLng(), texto(r.destino(), "Destino"),
                Datos.vacioANull(r.destinoBarrio()), r.destinoLat(), r.destinoLng(), pasajeros, casco,
                "taxi".equals(vehiculo) && Boolean.TRUE.equals(r.llevaEquipaje()),
                "taxi".equals(vehiculo) && Boolean.TRUE.equals(r.llevaMascota()), Datos.recortar(nombre, 80), tel, cotizado,
                r.programadoPara());
        return Map.of("id", id, "valor_cotizado", cotizado,
                "message", "Listo. Empezaremos a buscar tu " + vehiculo + " unos minutos antes.");
    }

    public List<Map<String, Object>> mios() {
        return db.queryForList("""
                SELECT id, tipo_vehiculo, origen_direccion AS origen, origen_barrio, destino_direccion AS destino,
                       destino_barrio, pasajeros, necesita_casco, lleva_equipaje, lleva_mascota, pasajero_nombre,
                       valor_cotizado, programado_para, estado, servicio_id, motivo_fallo
                FROM viajes_programados
                WHERE cliente_id = ? AND (estado = 'pendiente' OR programado_para > now() - interval '1 day')
                ORDER BY estado <> 'pendiente', programado_para
                """, sesion.usuarioId());
    }

    public void cancelar(Long id) {
        int n = db.update("""
                UPDATE viajes_programados SET estado = 'cancelado'
                WHERE id = ? AND cliente_id = ? AND estado = 'pendiente'
                """, id, sesion.usuarioId());
        if (n == 0) throw new RecursoNoEncontradoException("Ese viaje programado ya no está pendiente");
    }

    /**
     * Tarea: lanza la búsqueda de los programados cuya hora se acerca. Cada uno en su propia transacción,
     * así uno que falle (p. ej. el cliente ya tiene otro viaje activo) no frena a los demás.
     */
    public int lanzarPendientes() {
        List<Long> ids = db.queryForList("""
                SELECT id FROM viajes_programados
                WHERE estado = 'pendiente'
                  AND programado_para <= now() + make_interval(mins => fn_config_num('minutos_anticipo_programado', 10)::int)
                ORDER BY programado_para LIMIT 50
                """, Long.class);
        int lanzados = 0;
        for (Long id : ids) {
            try {
                Boolean ok = tx.execute(estado -> lanzar(id));
                if (Boolean.TRUE.equals(ok)) lanzados++;
            } catch (RuntimeException e) {
                String motivo = e instanceof ReglaNegocioException ? e.getMessage() : "No se pudo pedir el viaje a tiempo";
                log.warn("Viaje programado {} no se pudo lanzar: {}", id, e.getMessage());
                tx.executeWithoutResult(estado -> fallido(id, motivo));
            }
        }
        return lanzados;
    }

    private boolean lanzar(Long id) {
        Map<String, Object> p = db.queryForList("SELECT * FROM viajes_programados WHERE id = ? AND estado = 'pendiente' FOR UPDATE SKIP LOCKED", id)
                .stream().findFirst().orElse(null);
        if (p == null) return false;
        Long cliente = Datos.aLong(p.get("cliente_id"));
        // La hora ya pasó hace rato (servidor caído, por ejemplo): no se pide un viaje que ya no sirve
        Boolean vencido = db.queryForObject("SELECT ?::timestamptz < now() - interval '20 minutes'", Boolean.class,
                p.get("programado_para"));
        if (Boolean.TRUE.equals(vencido)) {
            fallido(id, "La hora del viaje ya pasó");
            return false;
        }
        SolicitudRequest s = new SolicitudRequest(Datos.num(p.get("origen_lat")), Datos.num(p.get("origen_lng")),
                Datos.num(p.get("destino_lat")), Datos.num(p.get("destino_lng")), "transporte",
                (String) p.get("tipo_vehiculo"), (String) p.get("origen_direccion"), (String) p.get("origen_barrio"),
                (String) p.get("origen_referencia"), (String) p.get("destino_direccion"), (String) p.get("destino_barrio"),
                null, null, "Viaje programado", ((Number) p.get("pasajeros")).intValue(), null, "efectivo", null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                (Boolean) p.get("necesita_casco"), (Boolean) p.get("lleva_equipaje"), (Boolean) p.get("lleva_mascota"),
                (String) p.get("pasajero_nombre"), (String) p.get("pasajero_telefono"), null, null, null, null);
        Map<String, Object> creado = viajes.crearSolicitud(cliente, s);
        Long servicio = Datos.aLong(creado.get("id"));
        db.update("UPDATE viajes_programados SET estado = 'enviado', servicio_id = ? WHERE id = ?", servicio, id);
        avisos.avisar(cliente, servicio, NotificacionServicio.ESTADO, "buscando",
                "Buscando tu " + p.get("tipo_vehiculo") + " programada",
                "Ya les avisamos a los conductores cercanos. Abre MoviFY para seguirla.");
        return true;
    }

    private void fallido(Long id, String motivo) {
        Long cliente = db.queryForList("""
                UPDATE viajes_programados SET estado = 'fallido', motivo_fallo = ?
                WHERE id = ? AND estado = 'pendiente' RETURNING cliente_id
                """, Long.class, Datos.recortar(motivo, 200), id).stream().findFirst().orElse(null);
        avisos.avisar(cliente, null, NotificacionServicio.ESTADO, "programado",
                "No pudimos pedir tu viaje programado", Datos.recortar(motivo, 200) + ". Puedes pedirlo ahora desde MoviFY.");
    }

    private static String texto(String s, String siNo) {
        String t = Datos.vacioANull(s);
        return t == null ? siNo : Datos.recortar(t, 200);
    }
}
