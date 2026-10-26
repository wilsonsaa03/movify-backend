package com.movify.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import com.movify.backend.servicios.ProgramadoServicio;
import com.movify.backend.servicios.TarifaServicio;

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
    @Autowired
    ProgramadoServicio programados;
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

        // Nota de voz del punto de referencia: la sube el cliente y la escucha el conductor que recibió la oferta
        byte[] webm = { 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 1, 2, 3, 4, 5, 6, 7, 8 };
        var nota = mvc.perform(multipart("/api/servicios/" + idServicio + "/nota-voz")
                .file(new MockMultipartFile("audio", "nota.webm", "audio/webm", webm)).param("segundos", "8")
                .header("Authorization", "Bearer " + tokenCliente)).andReturn();
        assertThat(nota.getResponse().getStatus()).as(nota.getResponse().getContentAsString()).isEqualTo(200);
        var noEsSuya = mvc.perform(multipart("/api/servicios/" + idServicio + "/nota-voz")
                .file(new MockMultipartFile("audio", "nota.webm", "audio/webm", webm))
                .header("Authorization", "Bearer " + tokenConductor)).andReturn();
        assertThat(noEsSuya.getResponse().getStatus()).isEqualTo(403);
        var escuchar = llamar(get("/api/servicios/" + idServicio + "/nota-voz"), tokenConductor, null);
        assertThat(escuchar.getResponse().getStatus()).isEqualTo(200);
        assertThat(escuchar.getResponse().getContentType()).isEqualTo("audio/webm");
        Map<String, Object> conNota = ok(get("/api/servicios/" + idServicio), tokenConductor, null);
        assertThat(((Number) conNota.get("nota_voz_segundos")).intValue()).isEqualTo(8);
        assertThat(conNota).containsKeys("usuario_calificacion", "usuario_verificado");

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
        // Los filtros de "Mis viajes" de la app (antes "hoy" daba 500 por el SQL mal unido)
        for (String filtro : List.of("hoy", "semana", "mes")) {
            Map<String, Object> f = ok(get("/api/transporte/historial-conductor/" + idConductor + "?filtro=" + filtro + "&limite=50"),
                    tokenConductor, null);
            assertThat(((Number) f.get("total")).intValue()).as(filtro).isEqualTo(1);
        }
        assertThat(((Number) ok(get("/api/transporte/historial-conductor/" + idConductor + "?filtro=hoy&busqueda=zzz"),
                tokenConductor, null).get("total")).intValue()).isZero();
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

        // Datos del paquete: el PIN solo lo ve quien pidió la encomienda
        Map<String, Object> paquete = ok(get("/api/servicios/" + id + "/encomienda"), tokenCliente, null);
        assertThat(paquete.get("destinatario_nombre")).isEqualTo("Carmen Valencia");
        assertThat(paquete.get("codigo_entrega")).isEqualTo(pin);

        ok(patch("/api/transporte/servicio/" + id + "/estado"), tokenConductor, Map.of("estado", "ACEPTADO"));
        Map<String, Object> delConductor = ok(get("/api/servicios/" + id + "/encomienda"), tokenConductor, null);
        assertThat(delConductor).doesNotContainKey("codigo_entrega").containsKey("destinatario_telefono");
        ok(patch("/api/transporte/servicio/" + id + "/estado"), tokenConductor, Map.of("estado", "PAQUETE_RECOGIDO"));
        var sinPin = llamar(patch("/api/transporte/servicio/" + id + "/estado"), tokenConductor, Map.of("estado", "FINALIZADO"));
        assertThat(sinPin.getResponse().getStatus()).isEqualTo(400);
        ok(patch("/api/transporte/servicio/" + id + "/estado"), tokenConductor,
                Map.of("estado", "FINALIZADO", "codigo_entrega", pin));
        assertThat(db.queryForObject("SELECT entregado_a FROM servicio_encomienda WHERE servicio_id = ?", String.class, id))
                .isEqualTo("Carmen Valencia");
    }

    /** HU 2.8: sin destinatario o sin entrega no se crea nada; pendiente o aceptada, el cliente la cancela. */
    @Test
    @Order(42)
    void encomiendaSeValidaYSeCancela() throws Exception {
        String pendientes = "/api/transporte/solicitudes-pendientes/" + idConductor;
        int antes = db.queryForObject("SELECT count(*) FROM servicios WHERE cliente_id = ?", Integer.class, idCliente);

        // 1. Sin destinatario: 400 con mensaje y no queda ni el servicio ni la encomienda (todo en una transacción)
        var sinDestinatario = llamar(post("/api/servicios"), tokenCliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.87, "destino_lng", -77.04, "tipo", "encomienda", "descripcion", "Caja con ropa",
                "telefono_destinatario", "3105556677"));
        assertThat(sinDestinatario.getResponse().getStatus()).isEqualTo(400);
        assertThat(sinDestinatario.getResponse().getContentAsString()).contains("le llega el paquete");

        // 2. Sin dirección de entrega: 400 por la validación del destino
        var sinEntrega = llamar(post("/api/servicios"), tokenCliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "tipo", "encomienda", "descripcion", "Caja con ropa", "nombre_destinatario", "Carmen Valencia",
                "telefono_destinatario", "3105556677"));
        assertThat(sinEntrega.getResponse().getStatus()).isEqualTo(400);

        // 3. Paquete demasiado pesado: mensaje claro (no el error crudo de la BD)
        var pesado = llamar(post("/api/servicios"), tokenCliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.87, "destino_lng", -77.04, "tipo", "encomienda", "descripcion", "Nevera",
                "nombre_destinatario", "Carmen Valencia", "telefono_destinatario", "3105556677", "peso_kg", "80",
                "tipo_vehiculo", "taxi"));
        assertThat(pesado.getResponse().getStatus()).isEqualTo(400);
        assertThat(pesado.getResponse().getContentAsString()).contains("30 kg");

        // Contenido delictivo: se rechaza y no se crea nada
        var prohibido = llamar(post("/api/servicios"), tokenCliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.87, "destino_lng", -77.04, "tipo", "encomienda", "descripcion", "Medio kilo de marihuana",
                "nombre_destinatario", "Carmen Valencia", "telefono_destinatario", "3105556677"));
        assertThat(prohibido.getResponse().getStatus()).isEqualTo(400);
        assertThat(prohibido.getResponse().getContentAsString()).contains("drogas");
        assertThat(db.queryForObject("SELECT count(*) FROM servicios WHERE cliente_id = ?", Integer.class, idCliente))
                .isEqualTo(antes);

        // 4. Completa: queda pendiente y se le ofrece al conductor; el cliente la cancela y deja de verla
        Map<String, Object> datos = Map.of("origen_lat", LAT, "origen_lng", LNG, "destino_lat", 3.87, "destino_lng", -77.04,
                "tipo", "encomienda", "descripcion", "Caja con ropa", "nombre_destinatario", "Carmen Valencia",
                "telefono_destinatario", "3105556677", "tipo_paquete", "caja-mediana", "peso_kg", "3");
        Map<String, Object> s = ok(post("/api/servicios"), tokenCliente, datos);
        long id = ((Number) s.get("id")).longValue();
        assertThat(s.get("estado")).isEqualTo("PENDIENTE");
        assertThat(lista(get(pendientes), tokenConductor)).anyMatch(p -> ((Number) p.get("servicio_id")).longValue() == id);
        // Al conductor que solo recibe la oferta no le llegan los celulares de remitente ni destinatario
        assertThat(ok(get("/api/servicios/" + id), tokenConductor, null))
                .doesNotContainKeys("telefono_remitente", "telefono_destinatario", "usuario_telefono");
        ok(post("/api/servicios/" + id + "/estado"), tokenCliente, Map.of("estado", "cancelado"));
        assertThat(db.queryForObject("SELECT estado FROM servicios WHERE id = ?", String.class, id)).isEqualTo("cancelado");
        assertThat(lista(get(pendientes), tokenConductor)).noneMatch(p -> ((Number) p.get("servicio_id")).longValue() == id);

        // 5. Aceptada: el cliente también puede cancelarla
        long id2 = ((Number) ok(post("/api/servicios"), tokenCliente, datos).get("id")).longValue();
        ok(patch("/api/transporte/servicio/" + id2 + "/estado"), tokenConductor, Map.of("estado", "ACEPTADO"));
        Map<String, Object> c = ok(post("/api/servicios/" + id2 + "/estado"), tokenCliente, Map.of("estado", "cancelado"));
        assertThat(c.get("estado_codigo")).isEqualTo("cancelado");
    }

    /** HU 2.7: domicilio se valida, queda pendiente, se cancela (pendiente o aceptado) y cierra con el valor de la factura. */
    @Test
    @Order(43)
    void domicilioSeValidaCancelaYCierraConFactura() throws Exception {
        String pendientes = "/api/transporte/solicitudes-pendientes/" + idConductor;
        int antes = db.queryForObject("SELECT count(*) FROM servicios WHERE cliente_id = ?", Integer.class, idCliente);

        // 1. Sin descripción, sin entrega o compra sin valor: 400 y no se crea nada
        var sinDescripcion = llamar(post("/api/servicios"), tokenCliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.87, "destino_lng", -77.04, "tipo", "domicilio", "descripcion", "  "));
        assertThat(sinDescripcion.getResponse().getStatus()).isEqualTo(400);
        var sinEntrega = llamar(post("/api/servicios"), tokenCliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "tipo", "domicilio", "descripcion", "Acetaminofén"));
        assertThat(sinEntrega.getResponse().getStatus()).isEqualTo(400);
        var compraSinValor = llamar(post("/api/servicios"), tokenCliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.87, "destino_lng", -77.04, "tipo", "domicilio", "descripcion", "Acetaminofén",
                "requiere_compra", true));
        assertThat(compraSinValor.getResponse().getStatus()).isEqualTo(400);
        var categoriaRara = llamar(post("/api/servicios"), tokenCliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.87, "destino_lng", -77.04, "tipo", "domicilio", "descripcion", "Acetaminofén",
                "categoria", "licores"));
        assertThat(categoriaRara.getResponse().getStatus()).isEqualTo(400);
        assertThat(db.queryForObject("SELECT count(*) FROM servicios WHERE cliente_id = ?", Integer.class, idCliente))
                .isEqualTo(antes);

        // 2. Completo: pendiente, con su categoría, y le llega al conductor; el cliente lo cancela y deja de verlo
        Map<String, Object> datos = Map.of("origen_lat", LAT, "origen_lng", LNG, "destino_lat", 3.87, "destino_lng", -77.04,
                "tipo", "domicilio", "descripcion", "Acetaminofén 500 mg y suero oral", "establecimiento",
                "Droguería La Económica", "categoria", "farmacia", "requiere_compra", true, "valor_productos_aprox", 28000);
        Map<String, Object> s = ok(post("/api/servicios"), tokenCliente, datos);
        long id = ((Number) s.get("id")).longValue();
        assertThat(s.get("estado")).isEqualTo("PENDIENTE");
        assertThat(ok(get("/api/servicios/" + id), tokenCliente, null).get("categoria")).isEqualTo("farmacia");
        assertThat(lista(get(pendientes), tokenConductor)).anyMatch(p -> ((Number) p.get("servicio_id")).longValue() == id);
        ok(post("/api/servicios/" + id + "/estado"), tokenCliente, Map.of("estado", "cancelado"));
        assertThat(lista(get(pendientes), tokenConductor)).noneMatch(p -> ((Number) p.get("servicio_id")).longValue() == id);

        // 3. Aceptado: el cliente también lo puede cancelar
        long id2 = ((Number) ok(post("/api/servicios"), tokenCliente, datos).get("id")).longValue();
        ok(patch("/api/transporte/servicio/" + id2 + "/estado"), tokenConductor, Map.of("estado", "ACEPTADO"));
        assertThat(ok(post("/api/servicios/" + id2 + "/estado"), tokenCliente, Map.of("estado", "cancelado"))
                .get("estado_codigo")).isEqualTo("cancelado");

        // 4. Flujo de estados (2.4): aceptado → recogido → finalizado; con compra exige el valor de la factura
        long id3 = ((Number) ok(post("/api/servicios"), tokenCliente, datos).get("id")).longValue();
        ok(patch("/api/transporte/servicio/" + id3 + "/estado"), tokenConductor, Map.of("estado", "ACEPTADO"));
        ok(patch("/api/transporte/servicio/" + id3 + "/estado"), tokenConductor, Map.of("estado", "PAQUETE_RECOGIDO"));
        var sinFactura = llamar(patch("/api/transporte/servicio/" + id3 + "/estado"), tokenConductor, Map.of("estado", "FINALIZADO"));
        assertThat(sinFactura.getResponse().getStatus()).isEqualTo(400);
        ok(patch("/api/transporte/servicio/" + id3 + "/estado"), tokenConductor,
                Map.of("estado", "FINALIZADO", "valor_compra_real", 27400));
        assertThat(db.queryForObject("SELECT estado FROM servicios WHERE id = ?", String.class, id3)).isEqualTo("finalizado");
        assertThat(db.queryForObject("SELECT valor_compra_real FROM servicio_domicilio WHERE servicio_id = ?", Integer.class, id3))
                .isEqualTo(27400);
    }

    /** La distancia de la BD es Haversine (igual a la del backend) y los conductores salen del más cercano al más lejano. */
    @Test
    @Order(45)
    void conductoresCercanosConHaversine() throws Exception {
        double lat2 = 3.8950, lng2 = -77.0150;
        Double bd = db.queryForObject("SELECT fn_distancia_km(?, ?, ?, ?)", Double.class, LAT, LNG, lat2, lng2);
        assertThat(bd).isCloseTo(TarifaServicio.haversineKm(LAT, LNG, lat2, lng2), within(1e-9));
        assertThat(db.queryForObject("SELECT fn_distancia_km(?, ?, ?, ?)", Double.class, LAT, LNG, LAT, LNG)).isZero();

        List<Map<String, Object>> cerca = db.queryForList(
                "SELECT conductor_id, distancia_km FROM fn_conductores_cercanos(?, ?, 5, 'moto', ?)", LAT, LNG, idCliente);
        assertThat(cerca).anyMatch(c -> ((Number) c.get("conductor_id")).longValue() == idConductor);
        for (int i = 1; i < cerca.size(); i++) {
            assertThat(((Number) cerca.get(i).get("distancia_km")).doubleValue())
                    .isGreaterThanOrEqualTo(((Number) cerca.get(i - 1).get("distancia_km")).doubleValue());
        }
        // Es moto: a un taxi no se le ofrece
        assertThat(db.queryForList("SELECT conductor_id FROM fn_conductores_cercanos(?, ?, 5, 'taxi', ?)", LAT, LNG, idCliente))
                .noneMatch(c -> ((Number) c.get("conductor_id")).longValue() == idConductor);
    }

    /**
     * HU 2.6: el taxi se guarda como "taxi" pendiente, solo lo ven taxistas aprobados y conectados (no los de moto),
     * el perfil del taxista tiene su vehículo y el viaje se inicia con el PIN del pasajero.
     */
    @Test
    @Order(48)
    void taxiSoloParaTaxistasYConPin() throws Exception {
        // El taxista del registro por pasos (Order 15): el admin lo aprueba y entra con su celular
        long idTaxista = db.queryForObject("SELECT id FROM usuarios WHERE telefono = '+573007778899'", Long.class);
        ok(patch("/api/transporte/admin/conductor/" + idTaxista + "/estado"), tokenAdmin, Map.of("estado", "aprobado"));
        String tokenTaxista = (String) ok(post("/api/auth/login-celular"), null, Map.of("celular", "3007778899", "clave", "8642"))
                .get("token");

        // Criterio 3: su perfil tiene placa, marca, modelo y color
        @SuppressWarnings("unchecked")
        Map<String, Object> vehiculo = (Map<String, Object>) ok(get("/api/conductor/mi-perfil"), tokenTaxista, null).get("vehiculo");
        assertThat(vehiculo).containsEntry("tipo_vehiculo", "taxi").containsEntry("placa", "TAX123")
                .containsEntry("marca", "Chevrolet").containsEntry("linea", "Spark GT").containsEntry("color", "Amarillo");

        ok(post("/api/transporte/activar"), tokenTaxista, Map.of("activo", true, "lat", LAT + 0.003, "lng", LNG));
        ok(post("/api/transporte/activar"), tokenConductor, Map.of("activo", true, "lat", LAT + 0.002, "lng", LNG));

        // Criterio 1: se guarda como taxi y pendiente; el pasajero recibe el PIN
        Map<String, Object> s = ok(post("/api/servicios"), tokenCliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.89, "destino_lng", -77.02, "tipo_vehiculo", "taxi"));
        long id = ((Number) s.get("id")).longValue();
        assertThat(s.get("estado")).isEqualTo("PENDIENTE");
        assertThat(db.queryForObject("SELECT tipo_vehiculo || '/' || estado FROM servicios WHERE id = ?", String.class, id))
                .isEqualTo("taxi/buscando");
        String pin = (String) s.get("codigo_abordaje");
        assertThat(pin).matches("\\d{4}");

        // Criterio 2: la ve el taxista; el de moto no la ve ni la puede aceptar
        assertThat(lista(get("/api/transporte/solicitudes-pendientes/" + idTaxista), tokenTaxista))
                .anyMatch(p -> ((Number) p.get("servicio_id")).longValue() == id);
        assertThat(lista(get("/api/transporte/solicitudes-pendientes/" + idConductor), tokenConductor))
                .noneMatch(p -> ((Number) p.get("servicio_id")).longValue() == id);
        assertThat(llamar(post("/api/servicios/" + id + "/estado"), tokenConductor, Map.of("estado", "asignado"))
                .getResponse().getStatus()).isGreaterThanOrEqualTo(400);

        // El taxista acepta; el PIN no le llega a él, solo al pasajero
        ok(post("/api/servicios/" + id + "/estado"), tokenTaxista, Map.of("estado", "asignado"));
        assertThat(ok(get("/api/servicios/" + id), tokenTaxista, null)).doesNotContainKey("codigo_abordaje");
        assertThat(ok(get("/api/servicios/" + id), tokenCliente, null).get("codigo_abordaje")).isEqualTo(pin);

        // Llegó, el PIN equivocado no deja empezar, el correcto sí; luego finaliza y califica al pasajero
        ok(post("/api/servicios/" + id + "/estado"), tokenTaxista, Map.of("estado", "en_origen"));
        String malo = pin.equals("0000") ? "1111" : "0000";
        var conPinMalo = llamar(post("/api/servicios/" + id + "/estado"), tokenTaxista, Map.of("estado", "en_curso",
                "codigo_abordaje", malo));
        assertThat(conPinMalo.getResponse().getStatus()).isEqualTo(400);
        assertThat(db.queryForObject("SELECT estado FROM servicios WHERE id = ?", String.class, id)).isEqualTo("en_origen");
        ok(post("/api/servicios/" + id + "/estado"), tokenTaxista, Map.of("estado", "en_curso", "codigo_abordaje", pin));
        ok(post("/api/servicios/" + id + "/estado"), tokenTaxista, Map.of("estado", "finalizado"));
        assertThat(db.queryForObject("SELECT estado FROM servicios WHERE id = ?", String.class, id)).isEqualTo("finalizado");
        ok(post("/api/servicios/" + id + "/calificar"), tokenTaxista, Map.of("puntuacion", 5, "comentario", "Muy amable"));

        ok(post("/api/transporte/activar"), tokenTaxista, Map.of("activo", false));
    }

    /** HU 2.5: un viaje en moto solo lo ven mototaxistas aprobados y conectados. */
    @Test
    @Order(47)
    void motoSoloParaConductoresConectados() throws Exception {
        String pendientes = "/api/transporte/solicitudes-pendientes/" + idConductor;
        ok(post("/api/transporte/activar"), tokenConductor, Map.of("activo", false));

        Map<String, Object> s = ok(post("/api/servicios"), tokenCliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.89, "destino_lng", -77.02, "tipo_vehiculo", "moto"));
        long id = ((Number) s.get("id")).longValue();
        assertThat(db.queryForObject("SELECT tipo_vehiculo || '/' || estado FROM servicios WHERE id = ?", String.class, id))
                .isEqualTo("moto/buscando");
        assertThat(((Number) s.get("conductores_notificados")).intValue()).isZero();

        // Desconectado: no la ve aunque esté aprobado y cerca
        assertThat(lista(get(pendientes), tokenConductor)).noneMatch(p -> ((Number) p.get("servicio_id")).longValue() == id);

        // Al conectarse sí le llega
        ok(post("/api/transporte/activar"), tokenConductor, Map.of("activo", true, "lat", LAT + 0.002, "lng", LNG));
        assertThat(lista(get(pendientes), tokenConductor)).anyMatch(p -> ((Number) p.get("servicio_id")).longValue() == id);

        ok(post("/api/servicios/" + id + "/estado"), tokenCliente, Map.of("estado", "cancelado"));
    }

    @Test
    @Order(50)
    void clienteCancelaYNadieMas() throws Exception {
        String pendientes = "/api/transporte/solicitudes-pendientes/" + idConductor;

        // 1. Pendiente: se le ofrece al conductor cercano
        Map<String, Object> s = ok(post("/api/servicios"), tokenCliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.89, "destino_lng", -77.02, "tipo_vehiculo", "moto"));
        long id = ((Number) s.get("id")).longValue();
        assertThat(s.get("estado_codigo")).isEqualTo("buscando");
        assertThat(lista(get(pendientes), tokenConductor)).anyMatch(p -> ((Number) p.get("servicio_id")).longValue() == id);

        // 2. Salto fuera del flujo (pendiente → finalizado): se rechaza aunque lo pida el admin, y no cambia nada
        var salto = llamar(post("/api/servicios/" + id + "/estado"), tokenAdmin, Map.of("estado", "finalizado"));
        assertThat(salto.getResponse().getStatus()).isEqualTo(409);
        assertThat(db.queryForObject("SELECT estado FROM servicios WHERE id = ?", String.class, id)).isEqualTo("buscando");

        // 3. El cliente cancela la pendiente: queda cancelada y el conductor ya no la ve
        Map<String, Object> c = ok(post("/api/servicios/" + id + "/estado"), tokenCliente, Map.of("estado", "cancelado"));
        assertThat(c.get("estado_codigo")).isEqualTo("cancelado");
        assertThat(db.queryForObject("SELECT motivo_cancelacion_id FROM servicios WHERE id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(lista(get(pendientes), tokenConductor)).noneMatch(p -> ((Number) p.get("servicio_id")).longValue() == id);
        assertThat(db.queryForObject("SELECT count(*) FROM servicio_ofertas WHERE servicio_id = ? AND estado IN ('enviada', 'vista')",
                Integer.class, id)).isZero();

        // 4. Aceptada: el cliente también puede cancelarla
        Map<String, Object> s2 = ok(post("/api/servicios"), tokenCliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.89, "destino_lng", -77.02, "tipo_vehiculo", "moto"));
        long id2 = ((Number) s2.get("id")).longValue();
        ok(patch("/api/transporte/servicio/" + id2 + "/estado"), tokenConductor, Map.of("estado", "ACEPTADO"));
        assertThat(db.queryForObject("SELECT estado FROM servicios WHERE id = ?", String.class, id2)).isEqualTo("asignado");
        Map<String, Object> c2 = ok(post("/api/servicios/" + id2 + "/estado"), tokenCliente, Map.of("estado", "cancelado"));
        assertThat(c2.get("estado_codigo")).isEqualTo("cancelado");
        assertThat(lista(get(pendientes), tokenConductor)).noneMatch(p -> ((Number) p.get("servicio_id")).longValue() == id2);

        // 5. Un servicio cerrado no vuelve a moverse
        var reabrir = llamar(post("/api/servicios/" + id2 + "/estado"), tokenAdmin, Map.of("estado", "en_curso"));
        assertThat(reabrir.getResponse().getStatus()).isEqualTo(409);
    }

    // ───────────────────────── Viaje completo (V14: como Uber / DiDi / inDrive) ─────────────────────────

    /** Casco, parada, tarifa ofrecida y "subir tarifa" cuando nadie acepta. */
    @Test
    @Order(52)
    void cascoParadaYTarifaOfrecida() throws Exception {
        String pendientes = "/api/transporte/solicitudes-pendientes/" + idConductor;
        ok(post("/api/transporte/activar"), tokenConductor, Map.of("activo", true, "lat", LAT + 0.002, "lng", LNG));

        // La parada alarga la ruta y el cotizador explica el recargo nocturno (0 de día)
        String base = "/api/tarifas/cotizar?origen_lat=" + LAT + "&origen_lng=" + LNG + "&destino_lat=3.8900&destino_lng=-77.0200";
        Map<String, Object> sin = ok(get(base), null, null);
        Map<String, Object> con = ok(get(base + "&parada_lat=3.8700&parada_lng=-77.0400"), null, null);
        assertThat(((Number) con.get("distancia_km")).doubleValue()).isGreaterThan(((Number) sin.get("distancia_km")).doubleValue());
        assertThat(sin).containsKey("nocturno");
        Map<String, Object> moto = cast(sin.get("opciones")).stream().filter(o -> "moto".equals(o.get("tipo_vehiculo"))).findFirst().orElseThrow();
        assertThat(moto).containsKeys("recargo_nocturno_pct", "valor_maximo");
        double cotizado = ((Number) moto.get("valor_estimado")).doubleValue();
        double conParada = cast(con.get("opciones")).stream().filter(o -> "moto".equals(o.get("tipo_vehiculo")))
                .mapToDouble(o -> ((Number) o.get("valor_estimado")).doubleValue()).findFirst().orElseThrow();

        // Casco: la moto del conductor no lleva casco para el pasajero → no se le ofrece
        Map<String, Object> sinCasco = ok(post("/api/servicios"), tokenCliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.89, "destino_lng", -77.02, "tipo_vehiculo", "moto", "necesita_casco", true));
        long idSinCasco = ((Number) sinCasco.get("id")).longValue();
        assertThat(((Number) sinCasco.get("conductores_notificados")).intValue()).isZero();
        assertThat(lista(get(pendientes), tokenConductor)).noneMatch(p -> ((Number) p.get("servicio_id")).longValue() == idSinCasco);
        ok(post("/api/servicios/" + idSinCasco + "/estado"), tokenCliente, Map.of("estado", "cancelado", "motivo_id", 4));
        assertThat(db.queryForObject("SELECT motivo_cancelacion_id FROM servicios WHERE id = ?", Integer.class, idSinCasco)).isEqualTo(4);

        // El casco no aplica en taxi
        var cascoTaxi = llamar(post("/api/servicios"), tokenCliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.89, "destino_lng", -77.02, "tipo_vehiculo", "taxi", "necesita_casco", true));
        assertThat(cascoTaxi.getResponse().getStatus()).isEqualTo(400);

        // Ofrecer más del tope (100 % por defecto) no se puede; ofrecer un poco más sí
        var caro = llamar(post("/api/servicios"), tokenCliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.89, "destino_lng", -77.02, "tipo_vehiculo", "moto", "valor_ofrecido", cotizado * 3));
        assertThat(caro.getResponse().getStatus()).isEqualTo(400);

        db.update("UPDATE vehiculos SET casco_pasajero = true WHERE conductor_id = ?", idConductor);
        Map<String, Object> s = ok(post("/api/servicios"), tokenCliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.89, "destino_lng", -77.02, "tipo_vehiculo", "moto", "necesita_casco", true,
                "valor_ofrecido", conParada + 950, "parada", "Droguería", "parada_lat", 3.87, "parada_lng", -77.04));
        long id = ((Number) s.get("id")).longValue();
        assertThat(((Number) s.get("conductores_notificados")).intValue()).isEqualTo(1);
        Map<String, Object> fila = db.queryForMap("SELECT valor_estimado, valor_sugerido, parada_direccion FROM servicios WHERE id = ?", id);
        // Se redondea a la centena hacia arriba: +950 → +1.000 sobre lo cotizado con la parada
        assertThat(((Number) fila.get("valor_estimado")).doubleValue() - ((Number) fila.get("valor_sugerido")).doubleValue())
                .isEqualTo(1000.0);
        assertThat(fila.get("parada_direccion")).isEqualTo("Droguería");
        // Al conductor le llega el push de nueva solicitud (queda en notificaciones)
        assertThat(db.queryForObject("SELECT count(*) FROM notificaciones WHERE usuario_id = ? AND servicio_id = ? AND tipo = 'nueva_solicitud'",
                Integer.class, idConductor, id)).isEqualTo(1);

        // El conductor la rechaza; el cliente sube la tarifa y le vuelve a llegar con la marca "subieron la tarifa"
        ok(post("/api/servicios/" + id + "/estado"), tokenConductor, Map.of("estado", "rechazar"));
        assertThat(lista(get(pendientes), tokenConductor)).noneMatch(p -> ((Number) p.get("servicio_id")).longValue() == id);
        var menor = llamar(post("/api/servicios/" + id + "/tarifa"), tokenCliente, Map.of("valor", 100));
        assertThat(menor.getResponse().getStatus()).isEqualTo(400);
        var ajeno = llamar(post("/api/servicios/" + id + "/tarifa"), tokenConductor, Map.of("valor", 99000));
        assertThat(ajeno.getResponse().getStatus()).isEqualTo(403);
        double actual = ((Number) fila.get("valor_estimado")).doubleValue();
        Map<String, Object> subida = ok(post("/api/servicios/" + id + "/tarifa"), tokenCliente, Map.of("valor", actual + 1000));
        assertThat(((Number) subida.get("tarifa")).doubleValue()).isEqualTo(actual + 1000);
        assertThat(((Number) subida.get("aumentos_tarifa")).intValue()).isEqualTo(1);
        Map<String, Object> deNuevo = lista(get(pendientes), tokenConductor).stream()
                .filter(p -> ((Number) p.get("servicio_id")).longValue() == id).findFirst().orElseThrow();
        assertThat(deNuevo).containsEntry("tarifa_subida", true).containsEntry("necesita_casco", true).containsEntry("parada", "Droguería");

        idServicio = id; // lo sigue la prueba siguiente
    }

    /** Compartir en vivo, foto y datos del vehículo, cambio de destino, chat, espera, "¿Todo bien?", favorito y bloqueo. */
    @Test
    @Order(53)
    void viajeAcompanadoDePuntaAPunta() throws Exception {
        long id = idServicio;

        // Compartir: enlace público sin celulares
        Map<String, Object> c = ok(post("/api/servicios/" + id + "/compartir"), tokenCliente, null);
        assertThat((String) c.get("url")).contains("/seguir/");
        String token = (String) c.get("token");
        assertThat(ok(post("/api/servicios/" + id + "/compartir"), tokenCliente, null).get("token")).isEqualTo(token);
        assertThat(llamar(post("/api/servicios/" + id + "/compartir"), tokenConductor, null).getResponse().getStatus()).isEqualTo(403);
        Map<String, Object> publico = ok(get("/api/publico/viajes/" + token), null, null);
        assertThat(publico).containsEntry("estado_codigo", "buscando").containsEntry("viajero", "Ana")
                .doesNotContainKeys("usuario_telefono", "conductor_lat", "pasajero_telefono");
        assertThat(llamar(get("/api/publico/viajes/" + "x".repeat(43)), null, null).getResponse().getStatus()).isEqualTo(404);

        // El conductor acepta: el pasajero ve si su moto lleva casco y si tiene foto
        ok(post("/api/servicios/" + id + "/estado"), tokenConductor, Map.of("estado", "asignado"));
        Map<String, Object> visto = ok(get("/api/servicios/" + id), tokenCliente, null);
        assertThat(visto).containsEntry("conductor_casco", true).containsEntry("es_favorito", false)
                .containsKeys("conductor_vehiculo_foto", "mensajes_sin_leer");
        assertThat(ok(get("/api/publico/viajes/" + token), null, null)).containsEntry("placa", "ABC12D").containsKey("conductor_lat");
        assertThat(db.queryForObject("SELECT count(*) FROM notificaciones WHERE usuario_id = ? AND servicio_id = ? AND datos->>'evento' = 'asignado'",
                Integer.class, idCliente, id)).isEqualTo(1);
        assertThat(llamar(get("/api/servicios/" + id + "/vehiculo/foto"), tokenCliente, null).getResponse().getStatus())
                .isIn(200, 404);

        // Cambia el destino: se recalcula el precio conservando lo que ofreció de más; al conductor le llega el aviso
        Map<String, Object> cambio = ok(post("/api/servicios/" + id + "/destino"), tokenCliente, Map.of("destino", "Muelle turístico",
                "destino_lat", 3.8950, "destino_lng", -77.0750));
        Map<String, Object> despues = db.queryForMap("SELECT destino_direccion, parada_lat, valor_estimado - valor_sugerido AS extra, destino_cambiado_en FROM servicios WHERE id = ?", id);
        assertThat(despues.get("destino_direccion")).isEqualTo("Muelle turístico");
        assertThat(despues.get("parada_lat")).isNull();
        assertThat(((Number) despues.get("extra")).doubleValue()).isEqualTo(2000.0);
        assertThat(despues.get("destino_cambiado_en")).isNotNull();
        assertThat(((Number) cambio.get("tarifa")).doubleValue()).isPositive();
        assertThat(db.queryForObject("SELECT count(*) FROM notificaciones WHERE usuario_id = ? AND datos->>'evento' = 'destino'",
                Integer.class, idConductor)).isEqualTo(1);

        // Chat dentro de la app (sin exponer el número): al otro le llega el aviso
        ok(post("/api/servicios/" + id + "/mensajes"), tokenCliente, Map.of("contenido", "Estoy en la puerta azul"));
        assertThat(((Number) ok(get("/api/servicios/" + id), tokenConductor, null).get("mensajes_sin_leer")).intValue()).isEqualTo(1);
        List<Map<String, Object>> chat = lista(get("/api/servicios/" + id + "/mensajes"), tokenConductor);
        assertThat(chat).hasSize(1);
        assertThat(chat.get(0)).containsEntry("mio", false).containsEntry("contenido", "Estoy en la puerta azul");
        assertThat(((Number) ok(get("/api/servicios/" + id), tokenConductor, null).get("mensajes_sin_leer")).intValue()).isZero();

        // Llegó: corre el tiempo de espera; "no se presentó" antes de tiempo no se deja
        ok(post("/api/servicios/" + id + "/estado"), tokenConductor, Map.of("estado", "en_origen"));
        assertThat(ok(get("/api/servicios/" + id), tokenCliente, null).get("espera_hasta")).isNotNull();
        var antes = llamar(post("/api/servicios/" + id + "/estado"), tokenConductor, Map.of("estado", "cancelado", "motivo_id", 10));
        assertThat(antes.getResponse().getStatus()).isEqualTo(400);
        assertThat(db.queryForObject("SELECT estado FROM servicios WHERE id = ?", String.class, id)).isEqualTo("en_origen");

        // Empieza con el PIN; "¿Todo bien?" durante el viaje
        String pin = db.queryForObject("SELECT codigo_abordaje FROM servicios WHERE id = ?", String.class, id);
        ok(post("/api/servicios/" + id + "/estado"), tokenConductor, Map.of("estado", "en_curso", "codigo_abordaje", pin));
        ok(post("/api/servicios/" + id + "/alertas"), tokenCliente, Map.of("tipo", "desvio", "respuesta", "estoy_bien", "lat", 3.88, "lng", -77.03));
        var malaAlerta = llamar(post("/api/servicios/" + id + "/alertas"), tokenCliente, Map.of("tipo", "otro", "respuesta", "estoy_bien"));
        assertThat(malaAlerta.getResponse().getStatus()).isEqualTo(400);
        assertThat(db.queryForObject("SELECT count(*) FROM alertas_viaje WHERE servicio_id = ?", Integer.class, id)).isEqualTo(1);
        ok(post("/api/servicios/" + id + "/estado"), tokenConductor, Map.of("estado", "finalizado"));

        // Terminado: el enlace ya no muestra la ubicación
        assertThat(ok(get("/api/publico/viajes/" + token), null, null)).containsEntry("estado_codigo", "finalizado")
                .doesNotContainKey("conductor_lat");

        // Favorito y bloqueo
        assertThat(ok(put("/api/servicios/" + id + "/favorito"), tokenCliente, null)).containsEntry("es_favorito", true);
        assertThat(ok(get("/api/servicios/" + id), tokenCliente, null)).containsEntry("es_favorito", true);
        ok(post("/api/servicios/" + id + "/bloquear"), tokenCliente, Map.of("motivo", "Prueba"));
        assertThat(db.queryForObject("SELECT count(*) FROM usuarios_bloqueados WHERE usuario_id = ? AND bloqueado_id = ?",
                Integer.class, idCliente, idConductor)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM conductores_favoritos WHERE cliente_id = ?", Integer.class, idCliente)).isZero();
        // Bloqueado: ya no le ofrecen sus viajes
        Map<String, Object> otro = ok(post("/api/servicios"), tokenCliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.89, "destino_lng", -77.02, "tipo_vehiculo", "moto"));
        assertThat(((Number) otro.get("conductores_notificados")).intValue()).isZero();
        ok(post("/api/servicios/" + ((Number) otro.get("id")).longValue() + "/estado"), tokenCliente, Map.of("estado", "cancelado"));
        db.update("DELETE FROM usuarios_bloqueados WHERE usuario_id = ?", idCliente);

        // Conductores libres en el mapa: posición aproximada, sin datos personales
        List<Map<String, Object>> cerca = lista(get("/api/servicios/cercanos?lat=" + LAT + "&lng=" + LNG + "&tipo_vehiculo=moto"), tokenCliente);
        assertThat(cerca).isNotEmpty();
        assertThat(cerca.get(0)).containsOnlyKeys("lat", "lng", "tipo_vehiculo");
    }

    /** Viaje para otra persona (SMS a quien viaja) y viajes programados. */
    @Test
    @Order(54)
    void paraOtraPersonaYProgramado() throws Exception {
        var sinNombre = llamar(post("/api/servicios"), tokenCliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.89, "destino_lng", -77.02, "tipo_vehiculo", "taxi", "pasajero_telefono", "3105556677"));
        assertThat(sinNombre.getResponse().getStatus()).isEqualTo(400);
        Map<String, Object> s = ok(post("/api/servicios"), tokenCliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.89, "destino_lng", -77.02, "tipo_vehiculo", "taxi", "pasajeros", 3, "lleva_equipaje", true,
                "pasajero_nombre", "Abuela Rosa", "pasajero_telefono", "310 555 6677"));
        long id = ((Number) s.get("id")).longValue();
        assertThat(db.queryForMap("SELECT pasajero_nombre, pasajero_telefono, pasajeros, lleva_equipaje FROM servicios WHERE id = ?", id))
                .containsEntry("pasajero_nombre", "Abuela Rosa").containsEntry("pasajero_telefono", "+573105556677")
                .containsEntry("lleva_equipaje", true);
        ok(post("/api/servicios/" + id + "/estado"), tokenCliente, Map.of("estado", "cancelado"));
        var cincoEnTaxi = llamar(post("/api/servicios"), tokenCliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.89, "destino_lng", -77.02, "tipo_vehiculo", "taxi", "pasajeros", 5));
        assertThat(cincoEnTaxi.getResponse().getStatus()).isEqualTo(400);

        // Programar: muy pronto no; en dos horas sí
        Map<String, Object> viaje = new java.util.HashMap<>(Map.of("tipo_vehiculo", "moto", "origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.89, "destino_lng", -77.02, "origen", "Parque", "destino", "Aeropuerto"));
        viaje.put("programado_para", java.time.OffsetDateTime.now().plusMinutes(10).toString());
        assertThat(llamar(post("/api/servicios/programados"), tokenCliente, viaje).getResponse().getStatus()).isEqualTo(400);
        viaje.put("programado_para", java.time.OffsetDateTime.now().plusHours(2).toString());
        long p1 = ((Number) ok(post("/api/servicios/programados"), tokenCliente, viaje).get("id")).longValue();
        long p2 = ((Number) ok(post("/api/servicios/programados"), tokenCliente, viaje).get("id")).longValue();
        assertThat(lista(get("/api/servicios/programados"), tokenCliente)).hasSize(2);
        ok(delete("/api/servicios/programados/" + p2), tokenCliente, null);
        assertThat(db.queryForObject("SELECT estado FROM viajes_programados WHERE id = ?", String.class, p2)).isEqualTo("cancelado");

        // Se acerca la hora: la tarea lo convierte en un servicio que busca conductor
        db.update("UPDATE viajes_programados SET programado_para = now() + interval '5 minutes' WHERE id = ?", p1);
        assertThat(programados.lanzarPendientes()).isEqualTo(1);
        Map<String, Object> lanzado = db.queryForMap("SELECT estado, servicio_id FROM viajes_programados WHERE id = ?", p1);
        assertThat(lanzado.get("estado")).isEqualTo("enviado");
        long servicio = ((Number) lanzado.get("servicio_id")).longValue();
        assertThat(db.queryForObject("SELECT estado FROM servicios WHERE id = ?", String.class, servicio)).isEqualTo("buscando");
        ok(post("/api/servicios/" + servicio + "/estado"), tokenCliente, Map.of("estado", "cancelado"));
    }

    // ───────────────────────── Panel administrativo (V15) ─────────────────────────

    @Test
    @Order(56)
    void panelAdministrativoOperacionYSoporte() throws Exception {
        // En vivo: el conductor conectado aparece en el mapa con su placa; un cliente no puede verlo
        Map<String, Object> vivo = ok(get("/api/admin/en-vivo"), tokenAdmin, null);
        assertThat(cast(vivo.get("conductores"))).anyMatch(c -> ((Number) c.get("id")).longValue() == idConductor
                && "ABC12D".equals(c.get("placa")));
        assertThat((Map<String, Object>) vivo.get("contadores")).containsKeys("sos_abiertas", "alertas_pendientes", "programados_24h");
        assertThat(llamar(get("/api/admin/en-vivo"), tokenCliente, null).getResponse().getStatus()).isEqualTo(403);

        // "¿Todo bien?": las de "estoy bien" no quedan pendientes; una sin respuesta sí, y se revisa una sola vez
        long sinRespuesta = db.queryForObject("""
                INSERT INTO alertas_viaje (servicio_id, usuario_id, tipo, respuesta) VALUES (?, ?, 'detenido', 'sin_respuesta')
                RETURNING id""", Long.class, idServicio, idCliente);
        List<Map<String, Object>> pendientes = lista(get("/api/admin/alertas-viaje?pendientes=true"), tokenAdmin);
        assertThat(pendientes).extracting(a -> ((Number) a.get("id")).longValue()).containsExactly(sinRespuesta);
        assertThat(pendientes.get(0)).containsEntry("placa", "ABC12D").containsKey("conductor_telefono");
        ok(patch("/api/admin/alertas-viaje/" + sinRespuesta), tokenAdmin, Map.of("notas", "Llamamos al pasajero: tráfico"));
        assertThat(llamar(patch("/api/admin/alertas-viaje/" + sinRespuesta), tokenAdmin, Map.of("notas", "otra vez"))
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(lista(get("/api/admin/alertas-viaje?pendientes=true"), tokenAdmin)).isEmpty();

        // PQRS con contexto: quién reporta, de qué viaje y a quién (el conductor de ese viaje)
        Map<String, Object> creada = ok(post("/api/pqrs"), tokenCliente, Map.of("tipo", "objeto_olvidado",
                "asunto", "Dejé mi sombrilla", "descripcion", "Una sombrilla azul en el asiento", "servicioId", idServicio));
        long pqrs = ((Number) creada.get("id")).longValue();
        Map<String, Object> fila = lista(get("/api/admin/pqrs"), tokenAdmin).stream()
                .filter(r -> ((Number) r.get("id")).longValue() == pqrs).findFirst().orElseThrow();
        assertThat(fila).containsEntry("reporta_rol", "cliente").containsEntry("descripcion", "Una sombrilla azul en el asiento");
        assertThat(((Number) fila.get("reportado_id")).longValue()).isEqualTo(idConductor);
        ok(patch("/api/admin/pqrs/" + pqrs), tokenAdmin, Map.of("estado", "resuelto",
                "respuesta", "El conductor la tiene; te escribimos para coordinar la entrega."));
        assertThat(lista(get("/api/admin/pqrs"), tokenAdmin)).noneMatch(r -> ((Number) r.get("id")).longValue() == pqrs);
        assertThat(lista(get("/api/admin/pqrs?filtro=todos"), tokenAdmin)).anyMatch(r -> ((Number) r.get("id")).longValue() == pqrs);
        assertThat(db.queryForObject("SELECT count(*) FROM notificaciones WHERE usuario_id = ? AND tipo = 'soporte'",
                Integer.class, idCliente)).isEqualTo(1);

        // Parámetros: un texto en un número rompería fn_config_num → se rechaza; el teléfono se normaliza
        var malo = llamar(put("/api/admin/configuracion/radio_busqueda_km"), tokenAdmin, Map.of("valor", "abc"));
        assertThat(malo.getResponse().getStatus()).isEqualTo(400);
        var hora = llamar(put("/api/admin/configuracion/hora_inicio_nocturno"), tokenAdmin, Map.of("valor", "25"));
        assertThat(hora.getResponse().getStatus()).isEqualTo(400);
        assertThat(ok(put("/api/admin/configuracion/telefono_central"), tokenAdmin, Map.of("valor", "300 123 4567")))
                .containsEntry("valor", "+573001234567");
        assertThat(db.queryForObject("SELECT valor FROM configuracion_sistema WHERE clave = 'radio_busqueda_km'", String.class))
                .isEqualTo("5");

        // Resumen con las alertas del menú y auditoría con el nombre de quien hizo cada acción
        Map<String, Object> totales = (Map<String, Object>) ok(get("/api/admin/resumen"), tokenAdmin, null).get("totales");
        assertThat(totales).containsKeys("alertas_viaje_pendientes", "pqrs_vencidas", "documentos_por_vencer", "programados_pendientes");
        Map<String, Object> auditoria = ok(get("/api/admin/auditoria"), tokenAdmin, null);
        assertThat(cast(auditoria.get("acciones"))).anyMatch(a -> "alerta_viaje_revisada".equals(a.get("accion"))
                && a.get("admin_nombre") != null);
        assertThat(lista(get("/api/admin/programados"), tokenAdmin)).isNotEmpty();
    }

    /** V16: búsquedas paginadas, datos parciales, reasignar sin castigar, motivos obligatorios, roles, permisos y catálogos. */
    @Test
    @Order(57)
    void panelAdministrativoControlado() throws Exception {
        // Resumen del día y solicitudes filtradas/paginadas en el backend
        Map<String, Object> dia = ok(get("/api/admin/resumen-dia"), tokenAdmin, null);
        assertThat(dia).containsKeys("servicios_hoy", "buscando_demorados", "conductores_conectados", "cancelados_hoy", "por_hora");
        Map<String, Object> pagina = ok(get("/api/admin/servicios/buscar?estado=cancelado&tamano=5"), tokenAdmin, null);
        assertThat(cast(pagina.get("items"))).hasSizeLessThanOrEqualTo(5).allMatch(s -> "cancelado".equals(s.get("estado_codigo")));
        assertThat(((Number) pagina.get("total")).intValue()).isGreaterThan(0);
        assertThat(cast(ok(get("/api/admin/servicios/buscar").param("q", "#" + idServicio), tokenAdmin, null).get("items"))).hasSize(1);
        assertThat(llamar(get("/api/admin/servicios/buscar?desde=ayer"), tokenAdmin, null).getResponse().getStatus()).isEqualTo(400);

        // Reasignar un servicio atascado: vuelve a buscar y NO le cuenta cancelación al conductor
        db.update("DELETE FROM usuarios_bloqueados");
        ok(post("/api/transporte/activar"), tokenConductor, Map.of("activo", true, "lat", LAT + 0.002, "lng", LNG));
        long id = ((Number) ok(post("/api/servicios"), tokenCliente, Map.of("origen_lat", LAT, "origen_lng", LNG,
                "destino_lat", 3.89, "destino_lng", -77.02, "tipo_vehiculo", "moto")).get("id")).longValue();
        ok(post("/api/servicios/" + id + "/estado"), tokenConductor, Map.of("estado", "asignado"));
        int cancelacionesAntes = db.queryForObject("SELECT total_cancelaciones FROM conductores WHERE usuario_id = ?", Integer.class, idConductor);
        assertThat(llamar(post("/api/admin/servicios/" + id + "/reasignar"), tokenAdmin, Map.of("motivo", "no")).getResponse().getStatus()).isEqualTo(400);
        ok(post("/api/admin/servicios/" + id + "/reasignar"), tokenAdmin, Map.of("motivo", "El conductor no avanza hace 10 minutos"));
        assertThat(db.queryForMap("SELECT estado, conductor_id FROM servicios WHERE id = ?", id))
                .containsEntry("estado", "buscando").containsEntry("conductor_id", null);
        assertThat(db.queryForObject("SELECT total_cancelaciones FROM conductores WHERE usuario_id = ?", Integer.class, idConductor))
                .isEqualTo(cancelacionesAntes);

        // Cancelar desde el panel: nunca sin motivo, y queda en la auditoría
        assertThat(llamar(post("/api/admin/servicios/" + id + "/cancelar"), tokenAdmin, Map.of("motivo_id", 20))
                .getResponse().getStatus()).isEqualTo(400);
        ok(post("/api/admin/servicios/" + id + "/cancelar"), tokenAdmin, Map.of("motivo_id", 20, "motivo", "El cliente llamó a la línea"));
        assertThat(db.queryForObject("SELECT count(*) FROM auditoria_admin WHERE accion = 'servicio_cancelado' AND entidad_id = ?",
                Integer.class, id)).isEqualTo(1);

        // Clientes: lista con datos parciales, detalle completo; bloquear exige motivo
        Map<String, Object> clientes = ok(get("/api/admin/usuarios/buscar?q=Ana&rol=cliente"), tokenAdmin, null);
        Map<String, Object> ana = cast(clientes.get("items")).get(0);
        assertThat((String) ana.get("telefono")).contains("•").doesNotContain("1112233");
        assertThat(ok(get("/api/admin/usuarios/" + idCliente), tokenAdmin, null)).containsEntry("telefono", "+573001112233")
                .containsKeys("actividad", "servicios", "calificaciones", "pqrs", "reportes_en_contra");
        assertThat(llamar(patch("/api/auth/usuarios/" + idCliente + "/estado"), tokenAdmin, Map.of("estado", "bloqueado"))
                .getResponse().getStatus()).isEqualTo(400);

        // Conductores: filtros en la base, documento oculto y checklist según el tipo de vehículo
        Map<String, Object> motos = ok(get("/api/admin/conductores/buscar?tipo_vehiculo=moto"), tokenAdmin, null);
        assertThat(cast(motos.get("items"))).allMatch(c -> "moto".equals(c.get("tipo_vehiculo")))
                .allMatch(c -> String.valueOf(c.get("documento")).startsWith("••••"));
        Map<String, Object> exp = ok(get("/api/admin/conductores/" + idConductor + "/expediente"), tokenAdmin, null);
        List<Map<String, Object>> requisitos = cast(exp.get("requisitos"));
        assertThat(requisitos).anyMatch(r -> "soat".equals(r.get("codigo")) && Boolean.TRUE.equals(r.get("obligatorio")));
        assertThat(requisitos).noneMatch(r -> "tarjeta_operacion".equals(r.get("codigo")) && Boolean.TRUE.equals(r.get("obligatorio")));

        // Reportes, CSV para Excel (sin fórmulas inyectadas) y calificaciones bajas
        Map<String, Object> rep = ok(get("/api/admin/reportes"), tokenAdmin, null);
        assertThat((Map<String, Object>) rep.get("totales")).containsKeys("tasa_perdida", "min_aceptacion", "efectivo");
        var csv = llamar(get("/api/admin/reportes/servicios.csv"), tokenAdmin, null);
        assertThat(csv.getResponse().getStatus()).isEqualTo(200);
        assertThat(csv.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).startsWith("﻿Servicio;Fecha;");
        assertThat(llamar(get("/api/admin/reportes/servicios.csv"), tokenCliente, null).getResponse().getStatus()).isEqualTo(403);
        assertThat(((Number) ok(get("/api/admin/calificaciones?maximo=5"), tokenAdmin, null).get("total")).intValue()).isPositive();

        // Equipo: se da el rol a una cuenta que ya existe; no se puede quitar el último admin ni el propio
        assertThat(llamar(post("/api/admin/equipo"), tokenAdmin, Map.of("identificador", "3209998877", "rol", "soporte"))
                .getResponse().getStatus()).isEqualTo(404);
        ok(post("/api/admin/equipo"), tokenAdmin, Map.of("identificador", "300 111 2233", "rol", "soporte"));
        assertThat(db.queryForObject("SELECT count(*) FROM usuario_roles WHERE usuario_id = ? AND rol = 'soporte'", Integer.class, idCliente)).isEqualTo(1);
        ok(delete("/api/admin/usuarios/" + idCliente + "/roles/soporte"), tokenAdmin, null);
        long idAdmin = db.queryForObject("SELECT usuario_id FROM usuario_roles WHERE rol = 'admin' LIMIT 1", Long.class);
        assertThat(llamar(delete("/api/admin/usuarios/" + idAdmin + "/roles/admin"), tokenAdmin, null).getResponse().getStatus()).isEqualTo(400);

        // Permisos: los de soporte se ajustan; los de admin son fijos
        assertThat(llamar(put("/api/admin/permisos"), tokenAdmin, Map.of("rol", "admin", "permiso", "tarifas.ver", "activo", false))
                .getResponse().getStatus()).isEqualTo(400);
        ok(put("/api/admin/permisos"), tokenAdmin, Map.of("rol", "soporte", "permiso", "reportes.exportar", "activo", true));
        assertThat(db.queryForObject("SELECT count(*) FROM rol_permisos WHERE rol = 'soporte' AND permiso = 'reportes.exportar'", Integer.class)).isEqualTo(1);
        ok(put("/api/admin/permisos"), tokenAdmin, Map.of("rol", "soporte", "permiso", "reportes.exportar", "activo", false));

        // Contenido prohibido editable: una palabra nueva se bloquea al instante (sin desplegar)
        Map<String, Object> palabra = ok(post("/api/admin/catalogos/contenido-prohibido"), tokenAdmin,
                Map.of("palabra", "Escopolamina", "categoria", "drogas", "tipo", "prohibida"));
        var domicilio = llamar(post("/api/servicios"), tokenCliente, Map.of("tipo", "domicilio", "tipo_vehiculo", "moto",
                "origen_lat", LAT, "origen_lng", LNG, "destino_lat", 3.89, "destino_lng", -77.02,
                "descripcion", "Tráeme escopolamina de la farmacia"));
        assertThat(domicilio.getResponse().getStatus()).isEqualTo(400);
        ok(delete("/api/admin/catalogos/contenido-prohibido/" + ((Number) palabra.get("id")).longValue()), tokenAdmin, null);
        assertThat(ok(get("/api/admin/catalogos/operacion"), tokenAdmin, null)).containsKeys("tipos_vehiculo", "permitidos", "limites");

        // Recargo por lluvia: solo cuando el admin lo prende
        ok(post("/api/admin/tarifas"), tokenAdmin, Map.of("tipo_servicio", "transporte", "tipo_vehiculo", "moto", "tarifa_base", 2500,
                "valor_km", 900, "valor_minuto", 100, "tarifa_minima", 4000, "recargo_nocturno_pct", 0, "recargo_lluvia_pct", 50));
        ok(put("/api/admin/configuracion/lluvia_activa"), tokenAdmin, Map.of("valor", "0"));
        String cotizar = "/api/tarifas/cotizar?tipo_vehiculo=moto&origen_lat=" + LAT + "&origen_lng=" + LNG + "&destino_lat=3.8900&destino_lng=-77.0200";
        double seco = ((Number) cast(ok(get(cotizar), null, null).get("opciones")).get(0).get("valor_estimado")).doubleValue();
        ok(put("/api/admin/configuracion/lluvia_activa"), tokenAdmin, Map.of("valor", "1"));
        Map<String, Object> mojado = ok(get(cotizar), null, null);
        assertThat(mojado).containsEntry("lluvia", true);
        assertThat(((Number) cast(mojado.get("opciones")).get(0).get("valor_estimado")).doubleValue()).isGreaterThan(seco);
        ok(put("/api/admin/configuracion/lluvia_activa"), tokenAdmin, Map.of("valor", "0"));
    }

    /** Notificaciones de la app: cada quien ve y marca solo las suyas. */
    @Test
    @Order(58)
    void notificacionesPropias() throws Exception {
        Map<String, Object> mias = ok(get("/api/notificaciones?tamano=5"), tokenCliente, null);
        List<Map<String, Object>> items = cast(mias.get("items"));
        assertThat(items).isNotEmpty().hasSizeLessThanOrEqualTo(5);
        assertThat(items.get(0)).containsKeys("titulo", "cuerpo", "evento", "servicio_id", "leida_en");
        long noLeidas = ((Number) ok(get("/api/notificaciones/no-leidas"), tokenCliente, null).get("no_leidas")).longValue();
        assertThat(noLeidas).isPositive();

        // La notificación de otro no se puede marcar (404, sin revelar que existe)
        long ajena = db.queryForObject("SELECT id FROM notificaciones WHERE usuario_id <> ? LIMIT 1", Long.class, idCliente);
        assertThat(llamar(post("/api/notificaciones/" + ajena + "/leida"), tokenCliente, null).getResponse().getStatus()).isEqualTo(404);

        long propia = ((Number) items.get(0).get("id")).longValue();
        db.update("UPDATE notificaciones SET leida_en = NULL WHERE id = ?", propia);
        long antes = ((Number) ok(get("/api/notificaciones/no-leidas"), tokenCliente, null).get("no_leidas")).longValue();
        assertThat(((Number) ok(post("/api/notificaciones/" + propia + "/leida"), tokenCliente, null).get("no_leidas")).longValue())
                .isEqualTo(antes - 1);
        ok(post("/api/notificaciones/leer-todas"), tokenCliente, null);
        assertThat(((Number) ok(get("/api/notificaciones/no-leidas"), tokenCliente, null).get("no_leidas")).longValue()).isZero();
        assertThat(llamar(get("/api/notificaciones"), null, null).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @Order(59)
    void gananciasDelConductorEnLaApp() throws Exception {
        String base = "/api/transporte/ganancias-resumen/" + idConductor;
        double hoyBd = db.queryForObject("""
                SELECT COALESCE(SUM(valor_final), 0) FROM servicios
                WHERE conductor_id = ? AND estado = 'finalizado'
                  AND (finalizado_en AT TIME ZONE 'America/Bogota')::date = (now() AT TIME ZONE 'America/Bogota')::date""",
                Double.class, idConductor);
        assertThat(hoyBd).isPositive();

        // Hoy: 8 bloques de 3 horas (también los vacíos); las barras y el desglose suman el total
        Map<String, Object> hoy = ok(get(base), tokenConductor, null);
        assertThat(hoy).containsEntry("periodo", "hoy").containsKeys("km", "minutos", "promedio", "anterior", "tendencia");
        assertThat(((Number) hoy.get("total")).doubleValue()).isEqualTo(hoyBd);
        assertThat(((Number) hoy.get("viajes")).longValue()).isPositive();
        List<Map<String, Object>> barras = cast(hoy.get("barras"));
        assertThat(barras).hasSize(8);
        assertThat(barras.stream().mapToDouble(b -> ((Number) b.get("valor")).doubleValue()).sum()).isEqualTo(hoyBd);
        assertThat(cast(hoy.get("por_tipo")).stream().mapToDouble(t -> ((Number) t.get("valor")).doubleValue()).sum())
                .isEqualTo(hoyBd);

        // Semana: lunes a domingo; mes: todos los días del mes en curso
        assertThat(cast(ok(get(base + "?periodo=semana"), tokenConductor, null).get("barras"))).hasSize(7);
        int diasMes = java.time.YearMonth.now(java.time.ZoneId.of("America/Bogota")).lengthOfMonth();
        Map<String, Object> mes = ok(get(base + "?periodo=mes"), tokenConductor, null);
        assertThat(cast(mes.get("barras"))).hasSize(diasMes);
        assertThat(((Number) mes.get("total")).doubleValue()).isGreaterThanOrEqualTo(hoyBd);

        // Un periodo raro no se mete al SQL: se trata como "hoy"
        assertThat(ok(get(base).param("periodo", "x');DROP"), tokenConductor, null)).containsEntry("periodo", "hoy");
        // Las ganancias de un conductor solo las ve él
        assertThat(llamar(get(base), tokenCliente, null).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @Order(59)
    void desempenoDelConductorEnSuPerfil() throws Exception {
        Map<String, Object> d = ok(get("/api/conductor/mi-desempeno"), tokenConductor, null);
        assertThat(d).containsKeys("calificacion_promedio", "tasa_cancelacion", "favoritos", "conductor_desde")
                .containsEntry("limite_cancelacion", 20.0);
        // La calificación de 5 con "Muy bien" del viaje: cuenta en las estrellas y el comentario sale sin el nombre
        assertThat(((Number) ((Map<String, Object>) d.get("estrellas")).get("5")).longValue()).isPositive();
        List<Map<String, Object>> comentarios = cast(d.get("comentarios"));
        assertThat(comentarios).anyMatch(c -> "Muy bien".equals(c.get("comentario")));
        assertThat(comentarios.get(0)).doesNotContainKeys("calificador_id", "nombre", "cliente_nombre");
        long cerrados = db.queryForObject("SELECT total_servicios + total_cancelaciones FROM conductores WHERE usuario_id = ?",
                Long.class, idConductor);
        assertThat(d.get("sancionado")).isEqualTo(cerrados >= 5 && ((Number) d.get("tasa_cancelacion")).doubleValue() > 20.0);
        // Un cliente no tiene desempeño de conductor
        assertThat(llamar(get("/api/conductor/mi-desempeno"), tokenCliente, null).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @Order(59)
    void perfilDelConductorSobreMiElogiosYDesvincular() throws Exception {
        // Servicios autorizados: salen de servicio_vehiculo_permitido (la moto recibe los tres)
        Map<String, Object> perfil = ok(get("/api/conductor/mi-perfil"), tokenConductor, null);
        assertThat(cast(perfil.get("servicios_autorizados"))).extracting(s -> s.get("codigo"))
                .containsExactly("transporte", "domicilio", "encomienda");

        // "Sobre mí": sin teléfonos ni enlaces; el pasajero lo ve con la señal de verificado
        assertThat(llamar(put("/api/conductor/sobre-mi"), tokenConductor, Map.of("texto", "Escríbeme al 300 123 4567"))
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(llamar(put("/api/conductor/sobre-mi"), tokenConductor, Map.of("texto", "Mira www.mipagina.com"))
                .getResponse().getStatus()).isEqualTo(400);
        ok(put("/api/conductor/sobre-mi"), tokenConductor, Map.of("texto", "  Llevo 8 años   manejando en Buenaventura. "));
        Map<String, Object> visto = ok(get("/api/servicios/" + idServicio), tokenCliente, null);
        assertThat(visto).containsEntry("conductor_sobre_mi", "Llevo 8 años manejando en Buenaventura.")
                .containsEntry("conductor_verificado", true).containsKey("conductor_elogios");
        assertThat(llamar(put("/api/conductor/sobre-mi"), tokenCliente, Map.of("texto", "Hola")).getResponse().getStatus())
                .isEqualTo(403);

        // Elogios: lista cerrada según a quién se califica (al pasajero no se le dice "Manejó con cuidado")
        assertThat(llamar(post("/api/servicios/" + idServicio + "/calificar"), tokenConductor,
                Map.of("puntuacion", 5, "elogios", List.of("Manejó con cuidado"))).getResponse().getStatus()).isEqualTo(400);
        ok(post("/api/servicios/" + idServicio + "/calificar"), tokenConductor,
                Map.of("puntuacion", 5, "elogios", List.of("Pagó completo", "Muy amable"), "comentario", "Puntual"));
        assertThat(db.queryForObject("""
                SELECT array_to_string(elogios, '|') FROM calificaciones WHERE servicio_id = ? AND calificador_id = ?""",
                String.class, idServicio, idConductor)).isEqualTo("Pagó completo|Muy amable");
        assertThat(ok(get("/api/conductor/mi-desempeno"), tokenConductor, null)).containsKey("elogios");

        // Desvincular: el conductor bloquea al pasajero, lo ve en su lista (sin teléfono) y lo puede quitar
        ok(post("/api/servicios/" + idServicio + "/bloquear"), tokenConductor, Map.of("motivo", "Prueba de desvincular"));
        List<Map<String, Object>> bloqueados = lista(get("/api/usuarios/bloqueados"), tokenConductor);
        assertThat(bloqueados).anyMatch(b -> ((Number) b.get("id")).longValue() == idCliente);
        assertThat(bloqueados.get(0)).doesNotContainKeys("telefono", "foto");
        ok(delete("/api/usuarios/bloqueados/" + idCliente), tokenConductor, null);
        assertThat(llamar(delete("/api/usuarios/bloqueados/" + idCliente), tokenConductor, null).getResponse().getStatus())
                .isEqualTo(404);
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

    // ───────────────────────── Datos básicos (CRUD del admin) ─────────────────────────

    @Test
    @Order(65)
    void adminMantieneLosDatosBasicos() throws Exception {
        String base = "/api/admin/catalogos/tipos-documento";
        Map<String, Object> nuevo = ok(post(base), tokenAdmin, Map.of("codigo", "certificado_curso", "nombre",
                "Certificado de curso de manejo", "aplicaA", "conductor"));
        assertThat(nuevo.get("paso")).isEqualTo("documentos");
        assertThat(lista(get(base), tokenAdmin)).anyMatch(t -> "certificado_curso".equals(t.get("codigo")));

        ok(put(base + "/certificado_curso"), tokenAdmin, Map.of("nombre", "Curso de manejo defensivo",
                "aplicaA", "conductor", "obligatorioMoto", true));
        assertThat(db.queryForObject("SELECT nombre FROM tipos_documento WHERE codigo = 'certificado_curso'", String.class))
                .isEqualTo("Curso de manejo defensivo");

        // 400: falta el nombre, no se guarda nada
        var incompleto = llamar(post(base), tokenAdmin, Map.of("codigo", "sin_nombre", "aplicaA", "conductor"));
        assertThat(incompleto.getResponse().getStatus()).isEqualTo(400);
        assertThat(db.queryForObject("SELECT count(*) FROM tipos_documento WHERE codigo = 'sin_nombre'", Integer.class)).isZero();

        // 409: lo usa la app; 404: no existe
        assertThat(llamar(delete(base + "/soat"), tokenAdmin, null).getResponse().getStatus()).isEqualTo(409);
        assertThat(llamar(get(base + "/no_existe"), tokenAdmin, null).getResponse().getStatus()).isEqualTo(404);
        assertThat(llamar(put(base + "/no_existe"), tokenAdmin, Map.of("nombre", "X", "aplicaA", "vehiculo"))
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(llamar(delete(base + "/no_existe"), tokenAdmin, null).getResponse().getStatus()).isEqualTo(404);

        ok(delete(base + "/certificado_curso"), tokenAdmin, null);
        assertThat(db.queryForObject("SELECT count(*) FROM tipos_documento WHERE codigo = 'certificado_curso'", Integer.class)).isZero();

        // Motivos de cancelación
        String motivos = "/api/admin/catalogos/motivos-cancelacion";
        Map<String, Object> m = ok(post(motivos), tokenAdmin, Map.of("actor", "cliente", "descripcion", "Pedí por error"));
        long id = ((Number) m.get("id")).longValue();
        ok(put(motivos + "/" + id), tokenAdmin, Map.of("actor", "cliente", "descripcion", "Lo pedí por error", "activo", false));
        assertThat(ok(get(motivos + "/" + id), tokenAdmin, null).get("activo")).isEqualTo(false);
        assertThat(llamar(post(motivos), tokenAdmin, Map.of("actor", "otro", "descripcion", "X")).getResponse().getStatus())
                .isEqualTo(400);
        ok(delete(motivos + "/" + id), tokenAdmin, null);
        assertThat(llamar(get(motivos + "/" + id), tokenAdmin, null).getResponse().getStatus()).isEqualTo(404);

        // Un cliente no entra
        assertThat(llamar(get(motivos), tokenCliente, null).getResponse().getStatus()).isEqualTo(403);
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
