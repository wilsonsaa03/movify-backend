package com.movify.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Prueba de punta a punta contra un PostgreSQL REAL (con las migraciones V1–V4 y los triggers del DER).
 * Solo corre si existe la variable MOVIFY_IT=true (en GitHub Actions hay un servicio de PostgreSQL).
 *
 *   cliente se registra con SMS → conductor se registra y sube documentos → admin lo aprueba →
 *   conductor se conecta → cliente pide moto → conductor acepta y finaliza → cliente califica;
 *   encomienda con PIN de entrega; login de la web con correo.
 */
@SpringBootTest(properties = {
        "app.sms.consola=true",
        "app.tareas.habilitadas=false",
        "app.demo.simulacion=false",
        "app.osrm.url=http://127.0.0.1:9",
        "app.admin.secret-code=CODIGO-PRUEBA-123",
        "app.jwt.secret=secreto-de-pruebas-de-integracion-movify-0123456789",
        "app.documentos.dir=target/archivos-prueba" })
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "MOVIFY_IT", matches = "true")
class FlujoCompletoTest {

    private static final byte[] PNG = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0 };
    // Centro de Buenaventura (dentro de la cobertura de 15 km)
    private static final double LAT = 3.8801, LNG = -77.0312;

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate db;
    final ObjectMapper json = new ObjectMapper();

    static String tokenCliente, tokenConductor, tokenAdmin, refreshConductor;
    static long idCliente, idConductor, idServicio;

    // ───────────────────────── Registro con SMS ─────────────────────────

    @Test
    @Order(1)
    void clienteSeRegistraConCodigoSms() throws Exception {
        limpiar();
        Map<String, Object> r = registrar("300 111 2233", "cliente", "Ana María", "Riascos", "2468");
        tokenCliente = (String) r.get("token");
        idCliente = ((Number) r.get("id")).longValue();
        assertThat(r.get("rol")).isEqualTo("cliente");
        assertThat(r.get("telefonoVerificado")).isEqualTo(true);
        assertThat(r.get("refreshToken")).isNotNull();
        assertThat(db.queryForObject("SELECT count(*) FROM aceptaciones_legales WHERE usuario_id = ?", Integer.class, idCliente))
                .isEqualTo(3);

        // El mismo número no se puede registrar dos veces
        var otra = llamar(post("/api/auth/codigo"), null, Map.of("celular", "3001112233", "proposito", "registro"));
        assertThat(otra.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    @Order(2)
    void loginConCelularYClave() throws Exception {
        var mal = llamar(post("/api/auth/login-celular"), null, Map.of("celular", "3001112233", "clave", "9999"));
        assertThat(mal.getResponse().getStatus()).isEqualTo(401);
        Map<String, Object> ok = ok(post("/api/auth/login-celular"), null, Map.of("celular", "+57 300 111 2233", "clave", "2468"));
        assertThat(ok.get("nombre")).isEqualTo("Ana María Riascos");
        assertThat(db.queryForObject("SELECT count(*) FROM registro_accesos WHERE usuario_id = ?", Integer.class, idCliente))
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    @Order(3)
    void claveFacilSeRechaza() throws Exception {
        String tel = "3009998877";
        Map<String, Object> c = ok(post("/api/auth/codigo"), null, Map.of("celular", tel, "proposito", "registro"));
        Map<String, Object> v = ok(post("/api/auth/codigo/verificar"), null,
                Map.of("celular", tel, "proposito", "registro", "codigo", c.get("codigoDesarrollo")));
        var r = llamar(post("/api/auth/registro-movil"), null, Map.of("verificacionToken", v.get("verificacionToken"),
                "rol", "cliente", "nombres", "Pedro", "apellidos", "Prueba", "clave", "1234", "aceptaTerminos", true));
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
    }

    // ───────────────────────── Conductor ─────────────────────────

    @Test
    @Order(10)
    void conductorCreaPerfilVehiculoYDocumentos() throws Exception {
        Map<String, Object> r = registrar("3004445566", "conductor", "luis", "mina", "9051");
        idConductor = ((Number) r.get("id")).longValue();
        assertThat(r.get("siguientePaso")).isEqualTo("documentos_conductor");
        String t = (String) r.get("token");

        Map<String, Object> p = ok(post("/api/conductor/perfil"), t, Map.of("tipoIdentificacion", "CC",
                "numeroIdentificacion", "1.111.222.333", "fechaNacimiento", "1990-05-10", "categoriasLicencia", List.of("A2")));
        assertThat(p.get("requiereNuevoToken")).isEqualTo(false);

        // Aspirante en revisión: sin roles en la BD (ni cliente ni conductor), pero la app lo lleva a su registro
        Map<String, Object> nuevo = ok(post("/api/auth/refresh"), null, Map.of("refreshToken", r.get("refreshToken")));
        tokenConductor = (String) nuevo.get("token");
        refreshConductor = (String) nuevo.get("refreshToken");
        assertThat((List<Object>) nuevo.get("roles")).isEmpty();
        assertThat(nuevo.get("rol")).isEqualTo("conductor");
        assertThat(db.queryForObject("SELECT count(*) FROM usuario_roles WHERE usuario_id = ?", Integer.class, idConductor))
                .isZero();

        // Placa de taxi en una moto: la BD la rechaza
        var mala = llamar(post("/api/conductor/vehiculos"), tokenConductor, Map.of("tipoVehiculo", "moto", "placa", "ABC123",
                "marca", "Yamaha", "modeloAnio", 2022, "color", "Negro"));
        assertThat(mala.getResponse().getStatus()).isEqualTo(400);
        ok(post("/api/conductor/vehiculos"), tokenConductor, Map.of("tipoVehiculo", "moto", "placa", "abc-12d",
                "marca", "Yamaha", "linea", "FZ 2.0", "modeloAnio", 2022, "color", "Negro"));

        Map<String, Object> docs = ok(get("/api/conductor/documentos"), tokenConductor, null);
        List<Map<String, Object>> faltan = cast(docs.get("faltantes"));
        assertThat(faltan).isNotEmpty();
        for (Map<String, Object> f : faltan) {
            var req = multipart("/api/conductor/documentos")
                    .file(new MockMultipartFile("archivo", f.get("codigo") + ".png", "image/png", PNG))
                    .param("tipo", (String) f.get("codigo"));
            if (Boolean.TRUE.equals(f.get("requiere_vencimiento"))) {
                req.param("fechaVencimiento", LocalDate.now().plusYears(1).toString());
            }
            var res = mvc.perform(req.header("Authorization", "Bearer " + tokenConductor)).andReturn();
            assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(200);
        }
        Map<String, Object> despues = ok(get("/api/conductor/documentos"), tokenConductor, null);
        assertThat(cast(despues.get("faltantes"))).isEmpty();

        // Todavía no está aprobado: no se puede conectar
        var conectar = llamar(post("/api/transporte/activar"), tokenConductor, Map.of("activo", true, "lat", LAT, "lng", LNG));
        assertThat(conectar.getResponse().getStatus()).isEqualTo(403);
    }

    /** Registro por pasos de la app (Figma): identidad → licencia → vehículo → documentos → enviar solicitud. */
    @Test
    @Order(15)
    void conductorTaxiRegistroPorPasos() throws Exception {
        Map<String, Object> r = registrar("3007778899", "conductor", "rosa", "caicedo", "8642");
        String t = (String) r.get("token");

        Map<String, Object> p0 = ok(get("/api/conductor/registro?tipo=taxi"), t, null);
        assertThat(p0.get("siguiente")).isEqualTo("identidad");
        assertThat(cast(p0.get("requeridos"))).anyMatch(d -> "foto_interior".equals(d.get("codigo")));

        Map<String, Object> id = ok(post("/api/conductor/identidad"), t, Map.of("tipoIdentificacion", "CC",
                "numeroIdentificacion", "1.222.333.444", "fechaNacimiento", "1988-03-15"));
        assertThat(id.get("requiereNuevoToken")).isEqualTo(false);

        ok(post("/api/conductor/licencia"), t, Map.of("categoriasLicencia", List.of("C1"), "numeroLicencia", "1222333444"));
        ok(post("/api/conductor/vehiculos"), t, Map.of("tipoVehiculo", "taxi", "placa", "TAX 123", "marca", "Chevrolet",
                "linea", "Spark GT", "modeloAnio", LocalDate.now().getYear(), "color", "Amarillo",
                "empresaAfiliadora", "Taxis del Puerto"));

        // Sin todo completo no se puede enviar
        var antes = llamar(post("/api/conductor/solicitud"), t, Map.of("aceptaCondiciones", true));
        assertThat(antes.getResponse().getStatus()).isEqualTo(409);

        Map<String, Object> p1 = ok(get("/api/conductor/registro"), t, null);
        List<Map<String, Object>> faltan = cast(p1.get("faltantes"));
        // Vehículo del año: la técnico-mecánica todavía no se exige
        assertThat(faltan).noneMatch(d -> "tecnomecanica".equals(d.get("codigo")));
        for (Map<String, Object> f : faltan) {
            var req = multipart("/api/conductor/documentos")
                    .file(new MockMultipartFile("archivo", f.get("codigo") + ".png", "image/png", PNG))
                    .param("tipo", (String) f.get("codigo"));
            if (Boolean.TRUE.equals(f.get("requiere_vencimiento"))) {
                req.param("fechaVencimiento", LocalDate.now().plusYears(1).toString());
            }
            var res = mvc.perform(req.header("Authorization", "Bearer " + t)).andReturn();
            assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(200);
        }
        assertThat(ok(get("/api/conductor/registro"), t, null).get("siguiente")).isEqualTo("enviar");

        ok(post("/api/conductor/solicitud"), t, Map.of("aceptaCondiciones", true));
        Map<String, Object> p2 = ok(get("/api/conductor/registro"), t, null);
        assertThat(p2.get("siguiente")).isEqualTo("revision");
        assertThat(db.queryForObject("SELECT solicitud_enviada_en IS NOT NULL FROM conductores WHERE numero_identificacion = '1222333444'",
                Boolean.class)).isTrue();
    }

    @Test
    @Order(20)
    void adminApruebaAlConductor() throws Exception {
        var mal = llamar(post("/api/auth/registro-admin"), null, Map.of("nombre", "Admin Prueba",
                "correo", "admin.prueba@movify.co", "password", "ClaveAdmin2026", "codigoAdmin", "otro"));
        assertThat(mal.getResponse().getStatus()).isEqualTo(403);
        ok(post("/api/auth/registro-admin"), null, Map.of("nombre", "Admin Prueba", "correo", "admin.prueba@movify.co",
                "password", "ClaveAdmin2026", "codigoAdmin", "CODIGO-PRUEBA-123"));
        Map<String, Object> login = ok(post("/api/auth/login"), null,
                Map.of("correo", "admin.prueba@movify.co", "password", "ClaveAdmin2026"));
        tokenAdmin = (String) login.get("token");
        assertThat(login.get("rol")).isEqualTo("admin");

        List<Map<String, Object>> lista = lista(get("/api/transporte/admin/conductores"), tokenAdmin);
        assertThat(lista).anyMatch(c -> ((Number) c.get("conductor_id")).longValue() == idConductor);
        Map<String, Object> detalle = ok(get("/api/transporte/admin/conductor/" + idConductor + "/detalle"), tokenAdmin, null);
        assertThat(detalle.get("placa")).isEqualTo("ABC12D");
        assertThat(detalle.get("licencia")).isNotNull();

        // El cliente no puede entrar al panel
        assertThat(llamar(get("/api/transporte/admin/conductores"), tokenCliente, null).getResponse().getStatus()).isEqualTo(403);

        ok(patch("/api/transporte/admin/conductor/" + idConductor + "/estado"), tokenAdmin, Map.of("estado", "aprobado"));
        assertThat(db.queryForObject("SELECT estado FROM conductores WHERE usuario_id = ?", String.class, idConductor))
                .isEqualTo("aprobado");

        // Al aprobarlo la BD le da el rol conductor (solo ese); con el refresh el JWT ya lo trae
        assertThat(db.queryForList("SELECT rol FROM usuario_roles WHERE usuario_id = ?", String.class, idConductor))
                .containsExactly("conductor");
        Map<String, Object> nuevo = ok(post("/api/auth/refresh"), null, Map.of("refreshToken", refreshConductor));
        tokenConductor = (String) nuevo.get("token");
        refreshConductor = (String) nuevo.get("refreshToken");
        assertThat((List<Object>) nuevo.get("roles")).containsExactly("conductor");
    }

    // ───────────────────────── Viaje en moto ─────────────────────────

    @Test
    @Order(30)
    void viajeCompletoEnMoto() throws Exception {
        ok(post("/api/transporte/activar"), tokenConductor, Map.of("activo", true, "lat", LAT + 0.002, "lng", LNG));

        Map<String, Object> cot = ok(get("/api/tarifas/cotizar?origen_lat=" + LAT + "&origen_lng=" + LNG
                + "&destino_lat=3.8900&destino_lng=-77.0200"), null, null);
        assertThat(cast(cot.get("opciones"))).hasSize(2); // moto y taxi

        Map<String, Object> s = ok(post("/api/transporte/solicitar"), tokenCliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.89, "destino_lng", -77.02, "tipo", "TRANSPORTE", "tipo_vehiculo", "moto",
                "origen", "Parque Néstor Urbano Tenorio", "destino", "Barrio Pueblo Nuevo"));
        idServicio = ((Number) s.get("id")).longValue();
        assertThat(((Number) s.get("conductores_notificados")).intValue()).isEqualTo(1);
        assertThat(((Number) s.get("tarifa")).doubleValue()).isPositive();

        // No puede pedir otro viaje mientras tiene uno activo
        var otro = llamar(post("/api/transporte/solicitar"), tokenCliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.89, "destino_lng", -77.02));
        assertThat(otro.getResponse().getStatus()).isEqualTo(409);

        List<Map<String, Object>> pendientes = lista(get("/api/transporte/solicitudes-pendientes/" + idConductor), tokenConductor);
        assertThat(pendientes).anyMatch(p -> ((Number) p.get("servicio_id")).longValue() == idServicio);
        // El celular del cliente no se muestra antes de aceptar
        Map<String, Object> antes = ok(get("/api/transporte/servicio/" + idServicio), tokenConductor, null);
        assertThat(antes).doesNotContainKey("usuario_telefono");

        ok(patch("/api/transporte/servicio/" + idServicio + "/estado"), tokenConductor, Map.of("estado", "ACEPTADO"));
        Map<String, Object> visto = ok(get("/api/transporte/servicio/" + idServicio), tokenCliente, null);
        assertThat(visto.get("estado")).isEqualTo("ACEPTADO");
        assertThat(visto.get("conductor_placa")).isEqualTo("ABC12D");

        // La web salta directo a EN_VIAJE: el backend pasa por en_origen
        ok(patch("/api/transporte/servicio/" + idServicio + "/estado"), tokenConductor, Map.of("estado", "EN_VIAJE"));
        ok(post("/api/transporte/ubicacion"), tokenConductor, Map.of("lat", 3.885, "lng", -77.025));
        ok(patch("/api/transporte/servicio/" + idServicio + "/estado"), tokenConductor, Map.of("estado", "FINALIZADO"));

        Map<String, Object> fin = ok(get("/api/transporte/servicio/" + idServicio), tokenCliente, null);
        assertThat(fin.get("estado")).isEqualTo("FINALIZADO");
        assertThat(db.queryForObject("SELECT count(*) FROM historial_estados WHERE ambito = 'servicio' AND registro_id = ?",
                Integer.class, idServicio)).isEqualTo(5);
        assertThat(db.queryForObject("SELECT count(*) FROM seguimiento_ubicaciones WHERE servicio_id = ?", Integer.class, idServicio))
                .isEqualTo(1);

        ok(post("/api/transporte/calificar"), tokenCliente, Map.of("servicio_id", idServicio, "puntos", 5, "comentario", "Muy bien"));
        var doble = llamar(post("/api/transporte/calificar"), tokenCliente, Map.of("servicio_id", idServicio, "puntos", 4));
        assertThat(doble.getResponse().getStatus()).isEqualTo(409);

        Map<String, Object> stats = ok(get("/api/transporte/ganancias-stats/" + idConductor), tokenConductor, null);
        assertThat(((Number) stats.get("ganancias_hoy")).doubleValue()).isPositive();
        Map<String, Object> hist = ok(get("/api/transporte/historial-conductor/" + idConductor), tokenConductor, null);
        assertThat(((Number) hist.get("total")).intValue()).isEqualTo(1);
        assertThat(lista(get("/api/transporte/servicios/usuario/" + idCliente), tokenCliente)).hasSize(1);
        // Un usuario no puede ver el historial de otro
        assertThat(llamar(get("/api/transporte/servicios/usuario/" + idConductor), tokenCliente, null).getResponse().getStatus())
                .isEqualTo(403);

        // Zonas con más pedidos (mapa del conductor): el viaje de hoy cuenta; un cliente no puede verlas
        List<Map<String, Object>> zonas = lista(get("/api/conductor/zonas-demanda"), tokenConductor);
        assertThat(zonas).isNotEmpty();
        assertThat(((Number) zonas.get(0).get("pedidos")).intValue()).isPositive();
        assertThat(llamar(get("/api/conductor/zonas-demanda"), tokenCliente, null).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @Order(40)
    void encomiendaExigeElPinDeEntrega() throws Exception {
        Map<String, Object> s = ok(post("/api/transporte/solicitar"), tokenCliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.87, "destino_lng", -77.04, "tipo", "ENCOMIENDA", "descripcion", "Sobre con documentos",
                "nombre_destinatario", "Carmen Valencia", "telefono_destinatario", "3105556677",
                "tipo_paquete", "documento", "peso_kg", "1"));
        long id = ((Number) s.get("id")).longValue();
        String pin = (String) s.get("codigo_entrega");
        assertThat(pin).matches("\\d{4}");

        ok(patch("/api/transporte/servicio/" + id + "/estado"), tokenConductor, Map.of("estado", "ACEPTADO"));
        ok(patch("/api/transporte/servicio/" + id + "/estado"), tokenConductor, Map.of("estado", "PAQUETE_RECOGIDO"));
        var sinPin = llamar(patch("/api/transporte/servicio/" + id + "/estado"), tokenConductor, Map.of("estado", "FINALIZADO"));
        assertThat(sinPin.getResponse().getStatus()).isEqualTo(400);
        ok(patch("/api/transporte/servicio/" + id + "/estado"), tokenConductor,
                Map.of("estado", "FINALIZADO", "codigo_entrega", pin));
        assertThat(db.queryForObject("SELECT entregado_a FROM servicio_encomienda WHERE servicio_id = ?", String.class, id))
                .isEqualTo("Carmen Valencia");
    }

    @Test
    @Order(50)
    void clienteCancelaYNadieMas() throws Exception {
        Map<String, Object> s = ok(post("/api/servicios"), tokenCliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.89, "destino_lng", -77.02, "tipo_vehiculo", "moto"));
        long id = ((Number) s.get("id")).longValue();
        Map<String, Object> c = ok(post("/api/servicios/" + id + "/estado"), tokenCliente, Map.of("estado", "cancelado"));
        assertThat(c.get("estado_codigo")).isEqualTo("cancelado");
        assertThat(db.queryForObject("SELECT motivo_cancelacion_id FROM servicios WHERE id = ?", Integer.class, id)).isEqualTo(1);
    }

    // ───────────────────────── Web ─────────────────────────

    @Test
    @Order(60)
    void registroYLoginDeLaWeb() throws Exception {
        ok(post("/api/auth/registro"), null, Map.of("nombre", "maría fernanda angulo", "correo", "Maria@Correo.com",
                "password", "ClaveSegura1", "telefono", "311 222 3344"));
        Map<String, Object> login = ok(post("/api/auth/login"), null, Map.of("correo", "maria@correo.com", "password", "ClaveSegura1"));
        assertThat(login.get("rol")).isEqualTo("cliente");
        Map<String, Object> perfil = ok(get("/api/usuarios/perfil"), (String) login.get("token"), null);
        assertThat(perfil.get("nombres")).isEqualTo("María");
        assertThat(perfil.get("apellidos")).isEqualTo("Fernanda Angulo");
        assertThat(perfil.get("telefono_verificado")).isEqualTo(false);

        // Sin celular verificado no puede pedir servicios (regla del DER)
        var pedir = llamar(post("/api/transporte/solicitar"), (String) login.get("token"), Map.of("origen_lat", LAT,
                "origen_lng", LNG, "destino_lat", 3.89, "destino_lng", -77.02));
        assertThat(pedir.getResponse().getStatus()).isEqualTo(409);

        assertThat(lista(get("/api/auth/usuarios"), tokenAdmin)).hasSizeGreaterThanOrEqualTo(4);
        Map<String, Object> resumen = ok(get("/api/admin/resumen"), tokenAdmin, null);
        assertThat(resumen).containsKey("totales");
        assertThat(mvc.perform(get("/api/test/ping")).andReturn().getResponse().getContentAsString()).startsWith("pong");
        assertThat(ok(get("/api/catalogos"), null, null)).containsKeys("estados", "tipos_documento");
    }

    @Test
    @Order(70)
    void recuperarLaClaveConSms() throws Exception {
        Map<String, Object> c = ok(post("/api/auth/codigo"), null, Map.of("celular", "3001112233", "proposito", "login"));
        var mal = llamar(post("/api/auth/codigo/verificar"), null,
                Map.of("celular", "3001112233", "proposito", "login", "codigo", "000000"));
        assertThat(mal.getResponse().getStatus()).isEqualTo(400);
        Map<String, Object> v = ok(post("/api/auth/codigo/verificar"), null,
                Map.of("celular", "3001112233", "proposito", "login", "codigo", c.get("codigoDesarrollo")));
        ok(post("/api/auth/clave/restablecer"), null, Map.of("verificacionToken", v.get("verificacionToken"), "clave", "5082"));
        ok(post("/api/auth/login-celular"), null, Map.of("celular", "3001112233", "clave", "5082"));
        // El código ya se usó: no sirve otra vez
        var reuso = llamar(post("/api/auth/codigo/verificar"), null,
                Map.of("celular", "3001112233", "proposito", "login", "codigo", c.get("codigoDesarrollo")));
        assertThat(reuso.getResponse().getStatus()).isEqualTo(400);
    }

    // ───────────────────────── Utilidades ─────────────────────────

    private Map<String, Object> registrar(String celular, String rol, String nombres, String apellidos, String clave)
            throws Exception {
        Map<String, Object> c = ok(post("/api/auth/codigo"), null, Map.of("celular", celular, "proposito", "registro"));
        assertThat(c.get("codigoDesarrollo")).isNotNull();
        Map<String, Object> v = ok(post("/api/auth/codigo/verificar"), null,
                Map.of("celular", celular, "proposito", "registro", "codigo", c.get("codigoDesarrollo")));
        return ok(post("/api/auth/registro-movil"), null, Map.of("verificacionToken", v.get("verificacionToken"),
                "rol", rol, "nombres", nombres, "apellidos", apellidos, "clave", clave, "aceptaTerminos", true));
    }

    private MvcResult llamar(MockHttpServletRequestBuilder req, String token, Object cuerpo) throws Exception {
        req.header("X-Plataforma", "android");
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (cuerpo != null) req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(cuerpo));
        return mvc.perform(req).andReturn();
    }

    private Map<String, Object> ok(MockHttpServletRequestBuilder req, String token, Object cuerpo) throws Exception {
        MvcResult r = llamar(req, token, cuerpo);
        String texto = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(texto).isEqualTo(200);
        return json.readValue(texto, new TypeReference<>() {
        });
    }

    private List<Map<String, Object>> lista(MockHttpServletRequestBuilder req, String token) throws Exception {
        MvcResult r = llamar(req, token, null);
        String texto = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(texto).isEqualTo(200);
        return json.readValue(texto, new TypeReference<>() {
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cast(Object o) {
        return (List<Map<String, Object>>) o;
    }

    /**
     * Base limpia para la prueba (solo datos; el esquema y los catálogos quedan).
     * OJO: rol_permisos y tarifas tienen FK a usuarios (asignado_por / creado_por), así que
     * TRUNCATE ... CASCADE también las vaciaría: se copian antes y se devuelven después.
     */
    private void limpiar() {
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
    }
}
