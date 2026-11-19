package com.movify.backend.servicios.simulacion;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movify.backend.comun.AccesoDenegadoException;
import com.movify.backend.comun.Datos;
import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.comun.ReglaNegocioException;
import com.movify.backend.tiemporeal.TiempoReal;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Motor ÚNICO de simulación de viajes (moto, taxi, domicilio y encomienda).
 *
 * Fuente de verdad: la tabla simulacion_viajes (ruta OSRM + metros recorridos + velocidad + estado).
 * Cada segundo:
 *   1. Reconciliar: cada servicio activo tiene la simulación de su fase.
 *        asignado   → fase "recogida": del conductor al origen (pasajero, tienda o remitente).
 *        en_origen  → el vehículo se queda en el origen.
 *        en_curso   → fase "destino": del origen a la entrega (pasando por la parada si hay).
 *      Si el pasajero cambia el destino, la ruta se recalcula desde donde va el vehículo.
 *   2. Avanzar: calcula la posición sobre la polilínea y la copia a ubicacion_conductores
 *      (la web, el panel admin y el enlace compartido la ven sin cambios). Al terminar la ruta → "llego".
 *
 * Los cambios de estado del servicio (Llegué, PIN, Finalizar) siguen siendo del conductor, como siempre.
 * La única excepción es el conductor demo (SimulacionDemo), que avanza solo.
 *
 * Se activa con app.simulacion.viajes=true (todos los viajes) o app.demo.simulacion=true (solo el conductor demo).
 */
@Service
public class SimulacionViajes {

    private static final Logger log = LoggerFactory.getLogger(SimulacionViajes.class);

    public static final String RECOGIDA = "recogida";
    public static final String DESTINO = "destino";
    public static final String LISTA = "lista";
    public static final String CORRIENDO = "corriendo";
    public static final String PAUSADA = "pausada";
    public static final String LLEGO = "llego";

    /** A menos de esto de la parada se da por hecha (para no volver a pasar si cambian el destino). */
    private static final double PARADA_HECHA_M = 60;
    /** Si OSRM falla, se vuelve a intentar a los 15 s (nunca se usa línea recta). */
    private static final long REINTENTO_MS = 15_000;
    /** Cada cuántos pasos queda un punto en el rastro del viaje (seguimiento_ubicaciones). */
    private static final int RASTRO_CADA = 5;

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final MotorRutas rutas;
    private final TiempoReal tiempoReal;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<Long, RutaCacheada> cache = new ConcurrentHashMap<>();
    private final Map<Long, Long> reintentarDesde = new ConcurrentHashMap<>();
    private ScheduledExecutorService reloj;
    private long pasos;

    @Value("${app.simulacion.viajes:false}")
    private boolean todos;
    @Value("${app.demo.simulacion:false}")
    private boolean demo;
    @Value("${app.simulacion.auto-iniciar:true}")
    private boolean autoIniciar;
    /** Velocidades realistas: con ellas se calcula el tiempo que muestra la app. */
    @Value("${app.simulacion.velocidad-moto-kmh:32}")
    private double velocidadMoto;
    @Value("${app.simulacion.velocidad-taxi-kmh:28}")
    private double velocidadTaxi;
    /** Tope de duración de cada tramo (s): el vehículo va más rápido por la misma ruta. 0 = sin tope. */
    @Value("${app.simulacion.max-segundos:60}")
    private double maxSegundos;
    @Value("${app.simulacion.paso-ms:1000}")
    private long pasoMs;
    /** false = no arranca el reloj (las pruebas llaman paso() a mano). */
    @Value("${app.simulacion.reloj:true}")
    private boolean conReloj;

    private record RutaCacheada(int version, RutaSimulada ruta) {
    }

    /** Estado calculado en un instante: lo que ven el conductor, el pasajero y el backend. */
    public record Estado(long servicioId, long conductorId, String fase, String estado, int version,
            double distancia, double recorrido, double velocidadKmh, double velocidadMostradaKmh,
            RutaSimulada.Posicion posicion) {

        public double restante() {
            return Math.max(0, distancia - recorrido);
        }

        /**
         * Segundos que faltan, los que ve la gente: a la velocidad realista (aunque en pruebas el vehículo
         * vaya más rápido para cumplir el tope de tiempo). Sin velocidad realista, a la velocidad actual.
         */
        public double etaSegundos() {
            double v = velocidadMostradaKmh > 0 ? velocidadMostradaKmh : velocidadKmh;
            return v <= 0 ? 0 : restante() / (v / 3.6);
        }
    }

    /** Lo máximo que acepta la base (V26). */
    static final double VELOCIDAD_MAXIMA_KMH = 5000;
    /** Rango de los controles del conductor. */
    static final double VELOCIDAD_CONTROL_MIN_KMH = 5;
    static final double VELOCIDAD_CONTROL_MAX_KMH = 120;

    /**
     * Velocidad con la que avanza el vehículo: la base, o más si hace falta para recorrer la ruta en `maxSegundos`.
     * Ej.: 3,2 km con tope de 60 s → 192 km/h. La ruta es la misma; solo se acorta la espera.
     */
    static double velocidadConTope(double baseKmh, double distanciaM, double maxSegundos) {
        if (maxSegundos <= 0 || distanciaM <= 0) return baseKmh;
        double necesaria = Math.ceil(distanciaM / maxSegundos * 3.6 * 10) / 10; // redondeo hacia arriba (NUMERIC 5,1)
        return Math.min(VELOCIDAD_MAXIMA_KMH, Math.max(baseKmh, necesaria));
    }

    public SimulacionViajes(JdbcTemplate db, TransactionTemplate tx, MotorRutas rutas, TiempoReal tiempoReal) {
        this.db = db;
        this.tx = tx;
        this.rutas = rutas;
        this.tiempoReal = tiempoReal;
    }

    @PostConstruct
    void arrancar() {
        if (!habilitada() || !conReloj) return;
        log.warn("SIMULACIÓN DE VIAJES ACTIVA ({}): los vehículos se mueven por la ruta OSRM, no por el GPS",
                todos ? "todos los viajes" : "solo el conductor demo");
        reloj = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "simulacion-viajes");
            t.setDaemon(true);
            return t;
        });
        reloj.scheduleWithFixedDelay(this::paso, 2000, Math.max(200, pasoMs), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void apagar() {
        if (reloj != null) reloj.shutdownNow();
    }

    public boolean habilitada() {
        return todos || demo;
    }

    // ═══════════════════════ Cálculo puro (lo mismo hace la app) ═══════════════════════

    /** Metros recorridos en `ahora`: si corre, lo de la marca + velocidad × tiempo; nunca pasa del final. */
    public static double recorridoEn(String estado, double recorridoMarca, long marcaMs, double velocidadKmh,
            double distancia, long ahoraMs) {
        double s = recorridoMarca;
        if (CORRIENDO.equals(estado)) s += velocidadKmh / 3.6 * Math.max(0, ahoraMs - marcaMs) / 1000.0;
        return Math.max(0, Math.min(distancia, s));
    }

    // ═══════════════════════ Paso del reloj ═══════════════════════

    /** Un paso: reconciliar fases y mover. Público para las pruebas (no esperan al reloj). */
    public void paso() {
        try {
            reconciliar();
        } catch (Exception e) {
            log.error("Simulación: error reconciliando", e);
        }
        try {
            avanzar(System.currentTimeMillis());
        } catch (Exception e) {
            log.error("Simulación: error moviendo los vehículos", e);
        }
        pasos++;
    }

    private void reconciliar() {
        List<Map<String, Object>> activos = db.queryForList("""
                SELECT s.id, s.estado, s.conductor_id, s.tipo_vehiculo,
                       s.origen_lat, s.origen_lng, s.destino_lat, s.destino_lng, s.parada_lat, s.parada_lng,
                       s.destino_cambiado_en,
                       sv.fase, sv.estado AS sim_estado, sv.conductor_id AS sim_conductor, sv.destino_version
                FROM servicios s
                JOIN usuarios u ON u.id = s.conductor_id
                LEFT JOIN simulacion_viajes sv ON sv.servicio_id = s.id
                WHERE s.estado IN ('asignado', 'en_origen', 'en_curso')
                  AND (CAST(? AS boolean) OR (CAST(? AS boolean) AND u.email LIKE 'demo%@movify.co'))
                """, todos, demo);

        // Lo que ya terminó (finalizado, cancelado…) deja de simularse
        db.update("""
                DELETE FROM simulacion_viajes sv USING servicios s
                WHERE s.id = sv.servicio_id AND s.estado NOT IN ('asignado', 'en_origen', 'en_curso')
                """);
        Set<Long> vivos = new HashSet<>();
        for (Map<String, Object> s : activos) vivos.add(Datos.aLong(s.get("id")));
        cache.keySet().retainAll(vivos);
        reintentarDesde.keySet().retainAll(vivos);

        for (Map<String, Object> s : activos) {
            Long id = Datos.aLong(s.get("id"));
            String estado = (String) s.get("estado");
            String fase = (String) s.get("fase");
            boolean otroConductor = s.get("sim_conductor") != null
                    && !Objects.equals(Datos.aLong(s.get("sim_conductor")), Datos.aLong(s.get("conductor_id")));
            try {
                switch (estado) {
                    case "asignado" -> {
                        if (fase == null || !RECOGIDA.equals(fase) || otroConductor) crear(s, RECOGIDA);
                    }
                    case "en_origen" -> {
                        // El conductor marcó "Llegué" antes de que el vehículo llegara: se pone en el origen
                        if (RECOGIDA.equals(fase) && !LLEGO.equals(s.get("sim_estado"))) controlarSistema(id, "finalizar");
                    }
                    case "en_curso" -> {
                        boolean destinoCambio = !Objects.equals(instante(s.get("destino_version")),
                                instante(s.get("destino_cambiado_en")));
                        if (!DESTINO.equals(fase) || destinoCambio || otroConductor) crear(s, DESTINO);
                    }
                    default -> {
                    }
                }
            } catch (Exception e) {
                log.warn("Simulación: no se pudo preparar el servicio {}: {}", id, e.getMessage());
            }
        }
    }

    /** Calcula la ruta de la fase con OSRM y deja la simulación lista (o corriendo). */
    private void crear(Map<String, Object> s, String fase) {
        Long id = Datos.aLong(s.get("id"));
        Long espera = reintentarDesde.get(id);
        if (espera != null && System.currentTimeMillis() < espera) return;

        Long conductorId = Datos.aLong(s.get("conductor_id"));
        String vehiculo = (String) s.get("tipo_vehiculo");
        double[] origen = { Datos.num(s.get("origen_lat")), Datos.num(s.get("origen_lng")) };
        double[] destino = { Datos.num(s.get("destino_lat")), Datos.num(s.get("destino_lng")) };
        double[] parada = s.get("parada_lat") == null ? null
                : new double[] { Datos.num(s.get("parada_lat")), Datos.num(s.get("parada_lng")) };

        // Si el admin reasignó el servicio, el conductor nuevo sale desde donde está él, no desde el anterior
        Optional<Estado> previo = estado(id, System.currentTimeMillis())
                .filter(e -> Objects.equals(e.conductorId(), conductorId));
        double[] inicio;
        if (previo.isPresent()) {
            // Sigue desde donde va el vehículo (fin de la recogida o mitad del viaje si cambió el destino)
            inicio = new double[] { previo.get().posicion().lat(), previo.get().posicion().lng() };
        } else if (RECOGIDA.equals(fase)) {
            inicio = ubicacionConductor(conductorId, origen);
        } else {
            inicio = origen;
        }

        List<double[]> puntos = new ArrayList<>();
        puntos.add(inicio);
        if (DESTINO.equals(fase)) {
            if (parada != null && !paradaHecha(id, previo, parada)) puntos.add(parada);
            puntos.add(destino);
        } else {
            puntos.add(origen);
        }

        Optional<MotorRutas.Ruta> r = rutas.ruta(vehiculo, puntos);
        if (r.isEmpty()) {
            reintentarDesde.put(id, System.currentTimeMillis() + REINTENTO_MS);
            return; // el vehículo se queda quieto: nunca se mueve en línea recta
        }
        reintentarDesde.remove(id);
        MotorRutas.Ruta ruta = r.get();

        double realista = "taxi".equals(vehiculo) ? velocidadTaxi : velocidadMoto;
        // Conserva la velocidad que eligió el conductor en sus controles (hasta 120 km/h; una más alta venía del
        // tope del tramo anterior). Con tope de tiempo, sube lo necesario para que este tramo lo cumpla.
        double base = previo.map(Estado::velocidadKmh).filter(v -> v <= VELOCIDAD_CONTROL_MAX_KMH).orElse(realista);
        double velocidad = velocidadConTope(base, ruta.distanciaM(), maxSegundos);
        boolean conductorDemo = Boolean.TRUE.equals(db.queryForObject(
                "SELECT email LIKE 'demo%@movify.co' FROM usuarios WHERE id = ?", Boolean.class, conductorId));
        // Si el conductor la tenía en pausa, sigue en pausa con la ruta nueva
        String inicial = previo.map(Estado::estado).filter(PAUSADA::equals)
                .orElse(autoIniciar || conductorDemo ? CORRIENDO : LISTA);

        String geometria;
        try {
            geometria = json.writeValueAsString(ruta.geometria());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        db.update("""
                INSERT INTO simulacion_viajes (servicio_id, conductor_id, fase, estado, geometria, distancia_m,
                       duracion_ruta_s, recorrido_m, velocidad_kmh, velocidad_mostrada_kmh, marca_en, version,
                       destino_version, perfil)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, 0, ?, ?, ?, 1, ?, ?)
                ON CONFLICT (servicio_id) DO UPDATE SET
                    conductor_id = EXCLUDED.conductor_id, fase = EXCLUDED.fase, estado = EXCLUDED.estado,
                    geometria = EXCLUDED.geometria, distancia_m = EXCLUDED.distancia_m,
                    duracion_ruta_s = EXCLUDED.duracion_ruta_s, recorrido_m = 0,
                    velocidad_kmh = EXCLUDED.velocidad_kmh, velocidad_mostrada_kmh = EXCLUDED.velocidad_mostrada_kmh,
                    marca_en = EXCLUDED.marca_en,
                    version = simulacion_viajes.version + 1, destino_version = EXCLUDED.destino_version,
                    perfil = EXCLUDED.perfil, actualizado_en = now()
                """, id, conductorId, fase, inicial, geometria, ruta.distanciaM(), ruta.duracionS(), velocidad,
                realista, Timestamp.from(Instant.now()), s.get("destino_cambiado_en"), ruta.perfil());
        cache.remove(id);
        // Ruta nueva (otra fase o cambio de destino): las apps la piden enseguida
        tiempoReal.publicar(id, "simulacion", Map.of("estado", inicial, "fase", fase));
        log.info("Simulación: servicio {} fase {} · {} m por {} vértices ({})", id, fase,
                Math.round(ruta.distanciaM()), ruta.geometria().size(), ruta.perfil());
    }

    /** ¿El vehículo ya pasó por la parada en la ruta anterior de esta misma fase? */
    private boolean paradaHecha(Long id, Optional<Estado> previo, double[] parada) {
        if (previo.isEmpty() || !DESTINO.equals(previo.get().fase())) return false;
        RutaSimulada r = ruta(id, previo.get().version());
        if (r == null) return false;
        RutaSimulada.Proyeccion p = r.proyectar(parada[0], parada[1]);
        return p.distancia() < PARADA_HECHA_M && p.recorrido() <= previo.get().recorrido() + 1;
    }

    /** Última ubicación del conductor si es reciente y cercana; si no, un punto a ~700 m del origen. */
    private double[] ubicacionConductor(Long conductorId, double[] origen) {
        List<Map<String, Object>> u = db.queryForList("""
                SELECT lat, lng FROM ubicacion_conductores
                WHERE conductor_id = ? AND actualizado_en > now() - INTERVAL '30 minutes'
                """, conductorId);
        if (!u.isEmpty()) {
            double[] p = { Datos.num(u.get(0).get("lat")), Datos.num(u.get(0).get("lng")) };
            double d = RutaSimulada.metros(p, origen);
            if (d > 80 && d < 15_000) return p;
        }
        // Sin GPS útil: OSRM pega este punto a la calle más cercana
        return new double[] { origen[0] + 0.0045, origen[1] + 0.0035 };
    }

    private void avanzar(long ahora) {
        List<Map<String, Object>> sims = db.queryForList("""
                SELECT sv.*, s.estado AS estado_servicio FROM simulacion_viajes sv
                JOIN servicios s ON s.id = sv.servicio_id
                WHERE s.estado IN ('asignado', 'en_origen', 'en_curso')
                """);
        boolean rastro = pasos % RASTRO_CADA == 0;
        for (Map<String, Object> f : sims) {
            Estado e = calcular(f, ahora);
            if (e == null) continue;
            if (CORRIENDO.equals(e.estado()) && e.recorrido() >= e.distancia()) {
                db.update("""
                        UPDATE simulacion_viajes SET estado = 'llego', recorrido_m = distancia_m, marca_en = ?, actualizado_en = now()
                        WHERE servicio_id = ? AND version = ? AND estado = 'corriendo'
                        """, Timestamp.from(Instant.ofEpochMilli(ahora)), e.servicioId(), e.version());
                log.info("Simulación: servicio {} llegó al final de la fase {}", e.servicioId(), e.fase());
                tiempoReal.publicar(e.servicioId(), "simulacion", Map.of("estado", LLEGO));
            }
            publicarUbicacion(e, rastro && CORRIENDO.equals(e.estado()));
        }
    }

    /** Copia el punto simulado a ubicacion_conductores (y al rastro del viaje). */
    private void publicarUbicacion(Estado e, boolean rastro) {
        RutaSimulada.Posicion p = e.posicion();
        int rumbo = (int) Math.round(p.rumbo()) % 360;
        double vel = CORRIENDO.equals(e.estado()) && e.restante() > 0 ? e.velocidadKmh() : 0;
        db.update("""
                INSERT INTO ubicacion_conductores (conductor_id, lat, lng, rumbo_grados, velocidad_kmh, precision_m, actualizado_en)
                VALUES (?, ?, ?, ?, ?, 0, now())
                ON CONFLICT (conductor_id) DO UPDATE SET lat = EXCLUDED.lat, lng = EXCLUDED.lng,
                    rumbo_grados = EXCLUDED.rumbo_grados, velocidad_kmh = EXCLUDED.velocidad_kmh,
                    precision_m = 0, actualizado_en = now()
                """, e.conductorId(), p.lat(), p.lng(), rumbo, vel);
        if (rastro) {
            db.update("""
                    INSERT INTO seguimiento_ubicaciones (servicio_id, conductor_id, lat, lng, velocidad_kmh, rumbo_grados, precision_m)
                    VALUES (?, ?, ?, ?, ?, ?, 0)
                    """, e.servicioId(), e.conductorId(), p.lat(), p.lng(), vel, rumbo);
        }
    }

    // ═══════════════════════ Consultas ═══════════════════════

    /** Estado de la simulación de un servicio en `ahora` (vacío si no tiene). */
    public Optional<Estado> estado(Long servicioId, long ahora) {
        return db.queryForList("SELECT * FROM simulacion_viajes WHERE servicio_id = ?", servicioId).stream()
                .findFirst().map(f -> calcular(f, ahora));
    }

    /** ¿El vehículo terminó la ruta de esa fase? (lo usa el conductor demo para avanzar solo). */
    public boolean llego(Long servicioId, String fase) {
        return estado(servicioId, System.currentTimeMillis())
                .map(e -> fase.equals(e.fase()) && (LLEGO.equals(e.estado()) || e.restante() <= 0.01))
                .orElse(false);
    }

    /**
     * GET /api/servicios/{id}/simulacion. Solo el cliente, el conductor asignado y el personal.
     * La geometría solo viaja si la app tiene otra versión (`versionApp`), así el sondeo pesa poco.
     */
    public Map<String, Object> consultar(Long servicioId, Long usuarioId, boolean personal, Integer versionApp) {
        exigirParticipante(servicioId, usuarioId, personal, false);
        Estado e = estado(servicioId, System.currentTimeMillis()).orElse(null);
        if (e == null) return null;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("servicio_id", e.servicioId());
        r.put("fase", e.fase());
        r.put("estado", e.estado());
        r.put("version", e.version());
        r.put("distancia_m", redondear(e.distancia()));
        r.put("recorrido_m", redondear(e.recorrido()));
        r.put("restante_m", redondear(e.restante()));
        r.put("eta_s", Math.round(e.etaSegundos()));
        r.put("velocidad_kmh", e.velocidadKmh());
        r.put("progreso", e.distancia() <= 0 ? 1 : redondear(e.recorrido() / e.distancia() * 1000) / 1000);
        r.put("lat", e.posicion().lat());
        r.put("lng", e.posicion().lng());
        r.put("rumbo", Math.round(e.posicion().rumbo()));
        r.put("servidor_ms", System.currentTimeMillis());
        if (versionApp == null || versionApp != e.version()) {
            RutaSimulada ruta = ruta(servicioId, e.version());
            List<double[]> puntos = new ArrayList<>(ruta.vertices());
            for (int i = 0; i < ruta.vertices(); i++) puntos.add(ruta.vertice(i));
            r.put("ruta", puntos);
        }
        return r;
    }

    // ═══════════════════════ Controles ═══════════════════════

    /** iniciar · pausar · reanudar · reiniciar · finalizar · velocidad. Solo el conductor asignado o el personal. */
    public Map<String, Object> controlar(Long servicioId, Long usuarioId, boolean personal, String accion, Double velocidadKmh) {
        exigirParticipante(servicioId, usuarioId, personal, true);
        aplicar(servicioId, accion, velocidadKmh);
        return consultar(servicioId, usuarioId, personal, null);
    }

    private void controlarSistema(Long servicioId, String accion) {
        aplicar(servicioId, accion, null);
    }

    private void aplicar(Long servicioId, String accion, Double velocidadKmh) {
        tx.executeWithoutResult(st -> {
            Map<String, Object> f = db.queryForList("SELECT * FROM simulacion_viajes WHERE servicio_id = ? FOR UPDATE", servicioId)
                    .stream().findFirst()
                    .orElseThrow(() -> new RecursoNoEncontradoException("Este servicio no tiene un viaje simulado"));
            long ahora = System.currentTimeMillis();
            Estado e = calcular(f, ahora);
            Timestamp marca = Timestamp.from(Instant.ofEpochMilli(ahora));
            String nuevo;
            double recorrido = e.recorrido();
            double velocidad = e.velocidadKmh();
            switch (accion) {
                case "iniciar", "reanudar" -> {
                    if (LLEGO.equals(e.estado())) throw new ReglaNegocioException(409, "El vehículo ya llegó: reinicia para repetir el recorrido");
                    nuevo = CORRIENDO;
                }
                case "pausar" -> {
                    if (!CORRIENDO.equals(e.estado())) throw new ReglaNegocioException(409, "El vehículo no está en movimiento");
                    nuevo = PAUSADA;
                }
                case "reiniciar" -> {
                    nuevo = LISTA;
                    recorrido = 0;
                }
                case "finalizar" -> {
                    nuevo = LLEGO;
                    recorrido = e.distancia();
                }
                case "velocidad" -> {
                    if (velocidadKmh == null || velocidadKmh < VELOCIDAD_CONTROL_MIN_KMH || velocidadKmh > VELOCIDAD_CONTROL_MAX_KMH) {
                        throw new ReglaNegocioException(400, "La velocidad debe estar entre 5 y 120 km/h");
                    }
                    nuevo = e.estado();
                    velocidad = velocidadKmh;
                }
                default -> throw new ReglaNegocioException("Acción desconocida: " + accion);
            }
            db.update("""
                    UPDATE simulacion_viajes SET estado = ?, recorrido_m = ?, velocidad_kmh = ?, marca_en = ?, actualizado_en = now()
                    WHERE servicio_id = ?
                    """, nuevo, recorrido, velocidad, marca, servicioId);
            Estado despues = new Estado(e.servicioId(), e.conductorId(), e.fase(), nuevo, e.version(), e.distancia(),
                    recorrido, velocidad, e.velocidadMostradaKmh(), ruta(servicioId, e.version()).en(recorrido));
            publicarUbicacion(despues, false);
            // El pasajero ve la pausa, el cambio de velocidad o el "llegar ya" al instante (WebSocket)
            tiempoReal.publicar(servicioId, "simulacion", Map.of("estado", nuevo, "velocidad_kmh", velocidad));
        });
    }

    private void exigirParticipante(Long servicioId, Long usuarioId, boolean personal, boolean soloConductor) {
        Map<String, Object> s = db.queryForList("SELECT cliente_id, conductor_id FROM servicios WHERE id = ?", servicioId)
                .stream().findFirst().orElseThrow(() -> new RecursoNoEncontradoException("Servicio no encontrado"));
        if (personal) return;
        boolean conductor = Objects.equals(Datos.aLong(s.get("conductor_id")), usuarioId);
        boolean cliente = Objects.equals(Datos.aLong(s.get("cliente_id")), usuarioId);
        if (soloConductor ? !conductor : !(conductor || cliente)) {
            throw new AccesoDenegadoException(soloConductor ? "Solo el conductor del servicio maneja la simulación"
                    : "No puedes ver este servicio");
        }
    }

    // ═══════════════════════ Ayudas ═══════════════════════

    private Estado calcular(Map<String, Object> f, long ahora) {
        Long id = Datos.aLong(f.get("servicio_id"));
        int version = ((Number) f.get("version")).intValue();
        RutaSimulada ruta = ruta(id, version);
        if (ruta == null) return null;
        String estado = (String) f.get("estado");
        double distancia = ruta.largo();
        double velocidad = Datos.num(f.get("velocidad_kmh"));
        long marca = ((Timestamp) f.get("marca_en")).getTime();
        double s = recorridoEn(estado, Datos.num(f.get("recorrido_m")), marca, velocidad, distancia, ahora);
        double mostrada = f.get("velocidad_mostrada_kmh") == null ? 0 : Datos.num(f.get("velocidad_mostrada_kmh"));
        return new Estado(id, Datos.aLong(f.get("conductor_id")), (String) f.get("fase"), estado, version,
                distancia, s, velocidad, mostrada, ruta.en(s));
    }

    /** La geometría se lee una vez por versión (las demás veces sale de la memoria). */
    private RutaSimulada ruta(Long servicioId, int version) {
        RutaCacheada c = cache.get(servicioId);
        if (c != null && c.version() == version) return c.ruta();
        List<Map<String, Object>> f = db.queryForList(
                "SELECT geometria::text AS g, version FROM simulacion_viajes WHERE servicio_id = ?", servicioId);
        if (f.isEmpty()) return null;
        try {
            List<double[]> puntos = json.readValue((String) f.get(0).get("g"), new TypeReference<List<double[]>>() {
            });
            RutaSimulada r = new RutaSimulada(puntos);
            cache.put(servicioId, new RutaCacheada(((Number) f.get(0).get("version")).intValue(), r));
            return r;
        } catch (Exception e) {
            log.error("Simulación: geometría dañada en el servicio {}", servicioId, e);
            return null;
        }
    }

    private static Instant instante(Object o) {
        if (o instanceof Timestamp t) return t.toInstant();
        if (o instanceof java.time.OffsetDateTime t) return t.toInstant();
        return null;
    }

    private static double redondear(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
