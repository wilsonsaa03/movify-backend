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
