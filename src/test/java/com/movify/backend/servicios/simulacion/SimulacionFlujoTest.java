package com.movify.backend.servicios.simulacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Los cuatro servicios simulados de punta a punta contra un PostgreSQL REAL (MOVIFY_IT=true, base de pruebas).
 * El motor de rutas se reemplaza por uno que traza "calles" en L (sin internet), así la prueba es repetible.
 *
 * En cada servicio: el conductor acepta → hay ruta de recogida → conductor y cliente ven exactamente el mismo
 * estado (una sola fuente de verdad) → el GPS del celular no mueve el vehículo → pausar, reanudar, velocidad,
 * reiniciar y finalizar → recogida → ruta al destino → entrega → la simulación se borra.
 */
@SpringBootTest(properties = {
        "app.sms.consola=true",
        "app.tareas.habilitadas=false",
        "app.limites.habilitados=false",
        "app.demo.simulacion=false",
        "app.simulacion.viajes=true",
        "app.simulacion.reloj=false",
        // Sin tope de tiempo: la prueba revisa las velocidades realistas y la que elige el conductor
        "app.simulacion.max-segundos=0",
        "app.osrm.url=http://127.0.0.1:9",
        "app.admin.secret-code=CODIGO-PRUEBA-123",
        "app.jwt.secret=secreto-de-pruebas-de-integracion-movify-0123456789",
        "app.documentos.dir=target/archivos-prueba" })
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "MOVIFY_IT", matches = "true")
class SimulacionFlujoTest {

    private static final double LAT = 3.8801, LNG = -77.0312;

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate db;
    @Autowired
    SimulacionViajes simulacion;
    @MockitoBean
    MotorRutas rutas;

    final ObjectMapper json = new ObjectMapper();
    String cliente, moto, taxi;
    long idMoto, idTaxi;

    @BeforeAll
    void preparar() throws Exception {
        db.execute("""
                DROP TABLE IF EXISTS _rp, _tar;
                CREATE TEMP TABLE _rp AS SELECT * FROM rol_permisos;
                CREATE TEMP TABLE _tar AS SELECT * FROM tarifas;
                UPDATE _rp SET asignado_por = NULL;
                UPDATE _tar SET creado_por = NULL;
                TRUNCATE usuarios, codigos_verificacion, registro_accesos, historial_estados, auditoria_cambios,
                         auditoria_admin RESTART IDENTITY CASCADE;
                INSERT INTO rol_permisos SELECT * FROM _rp;
                INSERT INTO tarifas OVERRIDING SYSTEM VALUE SELECT * FROM _tar;
                SELECT setval(pg_get_serial_sequence('tarifas', 'id'), COALESCE((SELECT max(id) FROM tarifas), 0) + 1, false);
                DROP TABLE _rp, _tar;
                """);
        db.execute(Files.readString(Path.of("scripts/datos_demo.sql"), StandardCharsets.UTF_8));
        cliente = token("3000000001");
        moto = token("3000000002");
        taxi = token("3000000003");
        idMoto = db.queryForObject("SELECT id FROM usuarios WHERE telefono = '+573000000002'", Long.class);
        idTaxi = db.queryForObject("SELECT id FROM usuarios WHERE telefono = '+573000000003'", Long.class);
    }

    @BeforeEach
    void callesEnL() {
        // Cada tramo a → b va primero por la "calle" horizontal y después por la vertical (dobla en una esquina)
        when(rutas.ruta(anyString(), anyList())).thenAnswer(inv -> {
            List<double[]> p = inv.getArgument(1);
            List<double[]> g = new ArrayList<>();
            g.add(p.get(0));
            for (int i = 1; i < p.size(); i++) {
                g.add(new double[] { p.get(i - 1)[0], p.get(i)[1] });
                g.add(p.get(i));
            }
            return Optional.of(new MotorRutas.Ruta(g, new RutaSimulada(g).largo(), 300, "driving"));
        });
    }

    @Test
    void motocicletaRecogeYLlevaAlPasajero() throws Exception {
        Map<String, Object> s = ok(post("/api/servicios"), cliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.8860, "destino_lng", -77.0200, "tipo_vehiculo", "moto"));
        recorrer("MOTOCICLETA", ((Number) s.get("id")).longValue(), moto, idMoto, Map.of("codigo_abordaje", s.get("codigo_abordaje")),
                Map.of(), 32);
    }

    @Test
    void taxiRecogeYLlevaAlPasajero() throws Exception {
        Map<String, Object> s = ok(post("/api/servicios"), cliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.8900, "destino_lng", -77.0180, "tipo_vehiculo", "taxi"));
        recorrer("TAXI", ((Number) s.get("id")).longValue(), taxi, idTaxi, Map.of("codigo_abordaje", s.get("codigo_abordaje")),
                Map.of(), 28);
    }

    @Test
    void encomiendaRecogeYEntregaElPaquete() throws Exception {
        Map<String, Object> s = ok(post("/api/servicios"), cliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.8700, "destino_lng", -77.0400, "tipo", "encomienda", "descripcion", "Caja con ropa",
                "nombre_destinatario", "Carmen Valencia", "telefono_destinatario", "3105556677",
                "tipo_paquete", "caja-mediana", "peso_kg", "3"));
        recorrer("ENCOMIENDA", ((Number) s.get("id")).longValue(), moto, idMoto, Map.of(),
                Map.of("codigo_entrega", s.get("codigo_entrega")), 32);
        assertThat(db.queryForObject("SELECT entregado_a FROM servicio_encomienda WHERE servicio_id = ?", String.class,
                ((Number) s.get("id")).longValue())).isEqualTo("Carmen Valencia");
    }

    @Test
    void domicilioRecogeEnLaTiendaYEntregaEnLaCasa() throws Exception {
        Map<String, Object> s = ok(post("/api/servicios"), cliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.8750, "destino_lng", -77.0150, "tipo", "domicilio",
                "descripcion", "Acetaminofén 500 mg", "establecimiento", "Droguería La Económica", "categoria", "farmacia"));
        recorrer("DOMICILIO", ((Number) s.get("id")).longValue(), moto, idMoto, Map.of(), Map.of(), 32);
    }

    @Test
    void sinSimulacionDelServicioResponde204YElClienteNoManeja() throws Exception {
        Map<String, Object> s = ok(post("/api/servicios"), cliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.8860, "destino_lng", -77.0200, "tipo_vehiculo", "moto"));
        long id = ((Number) s.get("id")).longValue();
        // Buscando conductor: todavía no hay nada que simular
        simulacion.paso();
        assertThat(llamar(get("/api/servicios/" + id + "/simulacion"), cliente, null).getResponse().getStatus()).isEqualTo(204);
        ok(post("/api/servicios/" + id + "/estado"), cliente, Map.of("estado", "cancelado"));
    }

    // ───────────────────────── Recorrido común ─────────────────────────

    private void recorrer(String servicio, long id, String tokenConductor, long idConductor, Map<String, Object> alSubir,
            Map<String, Object> alEntregar, double kmh) throws Exception {
        archivo("/api/conductor/selfie", tokenConductor); // V22: selfie antes de conectarse
        ok(post("/api/transporte/activar"), tokenConductor, Map.of("activo", true, "lat", LAT + 0.004, "lng", LNG + 0.003));
        ok(post("/api/servicios/" + id + "/estado"), tokenConductor, Map.of("estado", "asignado"));

        // 1. Recogida: ruta en L desde donde está el conductor hasta el origen, corriendo sola
        simulacion.paso();
        Map<String, Object> deCliente = sim(id, cliente, null);
        Map<String, Object> deConductor = sim(id, tokenConductor, null);
        assertThat(deCliente.get("fase")).as(servicio).isEqualTo("recogida");
        assertThat(deCliente.get("estado")).isEqualTo("corriendo");
        assertThat(((Number) deCliente.get("velocidad_kmh")).doubleValue()).isEqualTo(kmh);
        List<?> ruta = (List<?>) deCliente.get("ruta");
        assertThat(ruta).hasSize(3); // inicio → esquina → origen: no es una línea recta
        // Una sola fuente de verdad: los dos ven la misma ruta, versión y (casi) el mismo punto
        assertThat(deConductor.get("ruta")).isEqualTo(ruta);
        assertThat(deConductor.get("version")).isEqualTo(deCliente.get("version"));
        assertThat(num(deConductor, "recorrido_m")).isCloseTo(num(deCliente, "recorrido_m"), within(kmh / 3.6 * 0.5 + 0.2));
        // Sin cambio de versión, la geometría no se vuelve a mandar
        assertThat(sim(id, cliente, ((Number) deCliente.get("version")).intValue())).doesNotContainKey("ruta");

        // 2. El GPS del celular no mueve el vehículo simulado
        simulacion.paso();
        double[] antes = ubicacion(idConductor);
        ok(post("/api/conductor/ubicacion"), tokenConductor, Map.of("lat", 3.95, "lng", -77.10));
        assertThat(ubicacion(idConductor)).isEqualTo(antes);

        // 3. Controles
        assertThat(llamar(post("/api/servicios/" + id + "/simulacion/pausar"), cliente, null).getResponse().getStatus())
                .isEqualTo(403);
        Map<String, Object> pausa = ok(post("/api/servicios/" + id + "/simulacion/pausar"), tokenConductor, null);
        assertThat(pausa.get("estado")).isEqualTo("pausada");
        Thread.sleep(400);
        assertThat(num(sim(id, cliente, null), "recorrido_m")).isEqualTo(num(pausa, "recorrido_m"));
        assertThat(ok(post("/api/servicios/" + id + "/simulacion/reanudar"), tokenConductor, null).get("estado")).isEqualTo("corriendo");
        Map<String, Object> rapido = ok(post("/api/servicios/" + id + "/simulacion/velocidad"), tokenConductor, Map.of("velocidad_kmh", 90));
        assertThat(num(rapido, "velocidad_kmh")).isEqualTo(90);
        assertThat(llamar(post("/api/servicios/" + id + "/simulacion/velocidad"), tokenConductor, Map.of("velocidad_kmh", 500))
                .getResponse().getStatus()).isEqualTo(400);
        Map<String, Object> reinicio = ok(post("/api/servicios/" + id + "/simulacion/reiniciar"), tokenConductor, null);
        assertThat(reinicio.get("estado")).isEqualTo("lista");
        assertThat(num(reinicio, "recorrido_m")).isZero();
        assertThat(ok(post("/api/servicios/" + id + "/simulacion/iniciar"), tokenConductor, null).get("estado")).isEqualTo("corriendo");
        Map<String, Object> fin = ok(post("/api/servicios/" + id + "/simulacion/finalizar"), tokenConductor, null);
        assertThat(fin.get("estado")).isEqualTo("llego");
        assertThat(num(fin, "restante_m")).isZero();
        assertThat(num(fin, "eta_s")).isZero();
        // El vehículo quedó en el punto de recogida, y ubicacion_conductores lo dice (web, admin, enlace compartido)
        assertThat(RutaSimulada.metros(ubicacion(idConductor), new double[] { LAT, LNG })).isLessThan(0.5);
        assertThat(simulacion.llego(id, SimulacionViajes.RECOGIDA)).isTrue();

        // 4. Recoge (pasajero, paquete o pedido) y sale para el destino
        ok(post("/api/servicios/" + id + "/estado"), tokenConductor, Map.of("estado", "en_origen"));
        Map<String, Object> enCurso = new HashMap<>(alSubir);
        enCurso.put("estado", "en_curso");
        ok(post("/api/servicios/" + id + "/estado"), tokenConductor, enCurso);
        simulacion.paso();
        Map<String, Object> viaje = sim(id, cliente, null);
        assertThat(viaje.get("fase")).as(servicio).isEqualTo("destino");
        assertThat(viaje.get("estado")).isEqualTo("corriendo");
        assertThat(((Number) viaje.get("version")).intValue()).isGreaterThan(((Number) deCliente.get("version")).intValue());
        // Conserva la velocidad que eligió el conductor
        assertThat(num(viaje, "velocidad_kmh")).isEqualTo(90);
        List<?> rutaDestino = (List<?>) viaje.get("ruta");
        assertThat(rutaDestino).hasSize(3);
        assertThat(num(viaje, "restante_m")).isPositive();
        assertThat(num(viaje, "eta_s")).isPositive();

        // Avanza por la calle: después de un rato está más adelante, sigue sobre la ruta y le falta menos
        Thread.sleep(600);
        simulacion.paso();
        Map<String, Object> despues = sim(id, tokenConductor, null);
        assertThat(num(despues, "recorrido_m")).isGreaterThan(num(viaje, "recorrido_m"));
        assertThat(num(despues, "restante_m")).isLessThan(num(viaje, "restante_m"));
        List<double[]> g = new ArrayList<>();
        for (Object p : rutaDestino) g.add(new double[] { ((Number) ((List<?>) p).get(0)).doubleValue(), ((Number) ((List<?>) p).get(1)).doubleValue() });
        RutaSimulada calle = new RutaSimulada(g);
        assertThat(calle.distanciaA(num(despues, "lat"), num(despues, "lng"))).isLessThan(0.05);
        double[] u = ubicacion(idConductor);
        assertThat(calle.distanciaA(u[0], u[1])).isLessThan(0.05);

        // 5. Llega y entrega
        ok(post("/api/servicios/" + id + "/simulacion/finalizar"), tokenConductor, null);
        assertThat(simulacion.llego(id, SimulacionViajes.DESTINO)).isTrue();
        Map<String, Object> entregar = new HashMap<>(alEntregar);
        entregar.put("estado", "finalizado");
        if ("ENCOMIENDA".equals(servicio) || "DOMICILIO".equals(servicio)) {
            archivo("/api/servicios/" + id + "/evidencias", tokenConductor); // V22: foto de la entrega
        }
        ok(post("/api/servicios/" + id + "/estado"), tokenConductor, entregar);
        simulacion.paso();
        assertThat(db.queryForObject("SELECT count(*) FROM simulacion_viajes WHERE servicio_id = ?", Integer.class, id)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM seguimiento_ubicaciones WHERE servicio_id = ?", Integer.class, id)).isPositive();
        System.out.printf("%-12s servicio %d: recogida %.0f m + destino %.0f m simulados por la ruta%n", servicio, id,
                num(deCliente, "distancia_m"), num(viaje, "distancia_m"));
    }

    // ───────────────────────── Ayudas ─────────────────────────

    private double[] ubicacion(long conductorId) {
        return db.queryForObject("SELECT lat, lng FROM ubicacion_conductores WHERE conductor_id = ?",
                (rs, i) -> new double[] { rs.getDouble(1), rs.getDouble(2) }, conductorId);
    }

    private Map<String, Object> sim(long id, String token, Integer version) throws Exception {
        return ok(get("/api/servicios/" + id + "/simulacion" + (version == null ? "" : "?version=" + version)), token, null);
    }

    private static double num(Map<String, Object> m, String k) {
        return ((Number) m.get(k)).doubleValue();
    }

    private String token(String celular) throws Exception {
        return (String) ok(post("/api/auth/login-celular"), null, Map.of("celular", celular, "clave", "1357")).get("token");
    }

    /** Sube una foto PNG mínima (selfie o foto de entrega). */
    private void archivo(String ruta, String token) throws Exception {
        byte[] png = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0 };
        MvcResult r = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(ruta)
                .file(new org.springframework.mock.web.MockMultipartFile("foto", "foto.png", "image/png", png))
                .header("X-Plataforma", "android").header("Authorization", "Bearer " + token)).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
    }

    private MvcResult llamar(MockHttpServletRequestBuilder req, String token, Object cuerpo) throws Exception {
        req.header("X-Plataforma", "android");
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (cuerpo != null) req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(cuerpo));
        return mvc.perform(req).andReturn();
    }

    private Map<String, Object> ok(MockHttpServletRequestBuilder req, String token, Object cuerpo) throws Exception {
        MvcResult r = llamar(req, token, cuerpo);
        String texto = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(texto).isEqualTo(200);
        return json.readValue(texto, new TypeReference<>() {
        });
    }
}
