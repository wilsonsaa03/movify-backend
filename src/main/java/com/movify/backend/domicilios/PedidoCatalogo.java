package com.movify.backend.domicilios;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movify.backend.comun.ContenidoProhibido;
import com.movify.backend.comun.Datos;
import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.comun.ReglaNegocioException;
import com.movify.backend.seguridad.SesionActual;
import com.movify.backend.servicios.ServicioDtos.SolicitudRequest;
import com.movify.backend.servicios.TarifaServicio;
import com.movify.backend.servicios.ViajeServicio;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * El carrito de un comercio aliado → domicilio. El backend es la ÚNICA fuente de verdad del precio:
 * la app manda qué productos, cuántos y qué opciones; aquí se recalcula todo (producto + opciones) × cantidad,
 * el cupón, el domicilio y el total, y se valida antes de crear el pedido.
 *
 * Validaciones: comercio activo y abierto, la dirección dentro del radio del comercio, productos del comercio y
 * disponibles, reglas de cada grupo de opciones (obligatorio, mínimo, máximo), pedido mínimo, tope de compra
 * (el domiciliario paga en el comercio), +18 (licores) y contenido prohibido en las notas.
 * Si los precios cambiaron desde que el cliente los vio (subtotal_visto), no se pide: se avisa con el nuevo valor.
 *
 * El pedido se crea como un domicilio "con compra" (ViajeServicio): origen = el comercio, destino = la casa.
 * El cliente paga al terminar (efectivo o digital) lo que diga la factura + el domicilio − el cupón.
 */
@Service
public class PedidoCatalogo {

    private static final int MAX_CANTIDAD = 20;
    private static final int MAX_LINEAS = 40;

    private final JdbcTemplate db;
    private final SesionActual sesion;
    private final TarifaServicio tarifas;
    private final ViajeServicio viajes;
    private final CuponesDomicilio cupones;
    private final ObjectMapper json = new ObjectMapper();

    public PedidoCatalogo(JdbcTemplate db, SesionActual sesion, TarifaServicio tarifas, ViajeServicio viajes,
            CuponesDomicilio cupones) {
        this.db = db;
        this.sesion = sesion;
        this.tarifas = tarifas;
        this.viajes = viajes;
        this.cupones = cupones;
    }

    public record Linea(@NotNull @JsonProperty("producto_id") Long productoId,
            @NotNull @Min(1) @Max(MAX_CANTIDAD) Integer cantidad,
            @Size(max = 30) List<Long> opciones,
            @Size(max = 200) String nota) {
    }

    public record Pedido(
            @NotNull @JsonProperty("comercio_id") Long comercioId,
            @NotEmpty @Size(max = MAX_LINEAS) List<@Valid Linea> lineas,
            @NotNull @DecimalMin("-90") @DecimalMax("90") @JsonProperty("destino_lat") Double destinoLat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") @JsonProperty("destino_lng") Double destinoLng,
            @Size(max = 200) String destino,
            @Size(max = 80) @JsonProperty("destino_barrio") String destinoBarrio,
            @Size(max = 200) @JsonProperty("destino_referencia") String destinoReferencia,
            /** Instrucciones para el domiciliario ("dejar en portería"). */
            @Size(max = 300) String instrucciones,
            @Size(max = 30) String cupon,
            @Size(max = 12) @JsonProperty("metodo_pago") String metodoPago,
            /** El subtotal que el cliente vio en el carrito: si cambió, se le avisa antes de pedir. */
            @JsonProperty("subtotal_visto") BigDecimal subtotalVisto,
            /** "Confirmo que soy mayor de 18 años" (solo hace falta la primera vez que pide licor). */
            @JsonProperty("confirma_mayor_edad") Boolean confirmaMayorEdad) {
    }

    /** Todo calculado: líneas con su precio, totales, cupón y los problemas que impiden pedir. */
    private record Calculo(Map<String, Object> comercio, List<Map<String, Object>> lineas, BigDecimal subtotal,
            BigDecimal domicilio, CuponesDomicilio.Resultado cupon, BigDecimal total, int minutos, boolean soloMayores,
            List<String> errores) {
    }

    /** POST /api/domicilios/cotizar: lo que muestra el carrito (sin crear nada). */
    public Map<String, Object> cotizar(Pedido p) {
        Calculo c = calcular(p);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("comercio", Map.of("id", c.comercio().get("id"), "nombre", c.comercio().get("nombre"),
                "abierto", c.comercio().get("abierto"), "pedido_minimo", c.comercio().get("pedido_minimo")));
        r.put("lineas", c.lineas());
        r.put("subtotal", c.subtotal());
        r.put("domicilio", c.domicilio());
        r.put("descuento", c.cupon().descuento());
        r.put("cupon", c.cupon().codigo().isEmpty() ? null
                : Map.of("codigo", c.cupon().codigo(), "valido", c.cupon().valido(), "mensaje", Datos.nvl(c.cupon().mensaje()),
                        "descuento", c.cupon().descuento()));
        r.put("total", c.total());
        r.put("minutos_min", c.minutos());
        r.put("minutos_max", c.minutos() + 10);
        r.put("solo_mayores", c.soloMayores());
        r.put("mayor_edad_confirmado", mayorConfirmado());
        r.put("errores", c.errores());
        r.put("puede_pedir", c.errores().isEmpty());
        return r;
    }

    /** POST /api/domicilios/pedir: valida todo otra vez y crea el domicilio. */
    @Transactional
    public Map<String, Object> pedir(Pedido p) {
        Long yo = sesion.usuarioId();
        if (Boolean.TRUE.equals(p.confirmaMayorEdad())) {
            db.update("UPDATE usuarios SET mayor_edad_confirmado_en = COALESCE(mayor_edad_confirmado_en, now()) WHERE id = ?", yo);
        }
        Calculo c = calcular(p);
        if (!c.errores().isEmpty()) throw new ReglaNegocioException(400, c.errores().get(0));
        if (p.subtotalVisto() != null && p.subtotalVisto().compareTo(c.subtotal()) != 0) {
            throw new ReglaNegocioException(409, "Algunos precios cambiaron: tu carrito ahora suma " + CuponesDomicilio.pesos(c.subtotal())
                    + ". Revísalo antes de pedir.");
        }
        if (Datos.vacioANull(p.cupon()) != null && !c.cupon().valido()) {
            throw new ReglaNegocioException(400, c.cupon().mensaje() + " Quítalo para continuar.");
        }
        ContenidoProhibido.exigirPermitido(p.instrucciones());

        Map<String, Object> com = c.comercio();
        String categoria = String.valueOf(com.get("categoria"));
        // El domicilio "con compra" de siempre: el domiciliario paga en el comercio (valor = subtotal del catálogo)
        SolicitudRequest s = new SolicitudRequest(Datos.num(com.get("lat")), Datos.num(com.get("lng")), p.destinoLat(),
                p.destinoLng(), "domicilio", "moto", Datos.recortar(com.get("nombre") + " · " + com.get("direccion"), 200),
                (String) com.get("barrio"), null, p.destino(), p.destinoBarrio(), p.destinoReferencia(), resumen(c.lineas()),
                Datos.vacioANull(p.instrucciones()), null, null, p.metodoPago(), (String) com.get("nombre"), true, c.subtotal(),
                (String) com.get("telefono"), null, categoria, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        Map<String, Object> creado = viajes.crearSolicitud(yo, s);
        Long servicioId = Datos.aLong(creado.get("id"));

        db.update("""
                UPDATE servicio_domicilio SET comercio_id = ?, subtotal_catalogo = ?, descuento = ?, cupon_codigo = ?, solo_mayores = ?
                WHERE servicio_id = ?
                """, com.get("id"), c.subtotal(), c.cupon().descuento(), c.cupon().valido() ? c.cupon().codigo() : null,
                c.soloMayores(), servicioId);
        for (Map<String, Object> l : c.lineas()) {
            db.update("""
                    INSERT INTO servicio_domicilio_items (servicio_id, descripcion, cantidad, precio_unitario_aprox, notas,
                                                          producto_id, precio_unitario, opciones, total_linea)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                    """, servicioId, Datos.recortar(String.valueOf(l.get("nombre")), 150), l.get("cantidad"), l.get("precio_unitario"),
                    Datos.vacioANull((String) l.get("nota")), l.get("producto_id"), l.get("precio_unitario"),
                    escribir(l.get("opciones")), l.get("total_linea"));
        }
        cupones.registrarUso(c.cupon(), yo, servicioId);

        Map<String, Object> r = new LinkedHashMap<>(creado);
        r.put("subtotal", c.subtotal());
        r.put("descuento", c.cupon().descuento());
        r.put("total", c.subtotal().add(new BigDecimal(String.valueOf(creado.get("valor_estimado")))).subtract(c.cupon().descuento())
                .max(BigDecimal.ZERO));
        return r;
    }

    // ───────────── Cálculo ─────────────

    private Calculo calcular(Pedido p) {
        List<String> errores = new ArrayList<>();
        Map<String, Object> com = db.queryForList("SELECT c.*, " + CatalogoDomicilios.ABIERTO
                + " AS abierto, to_char(c.abre, 'HH24:MI') AS abre_texto FROM comercios_domicilio c WHERE c.id = ?",
                p.comercioId()).stream().findFirst().orElseThrow(() -> new RecursoNoEncontradoException("Ese comercio ya no está disponible"));
        if (!Boolean.TRUE.equals(com.get("activo"))) throw new RecursoNoEncontradoException("Ese comercio ya no está disponible");
        if (!Boolean.TRUE.equals(com.get("abierto"))) {
            // La hora ya termina en punto ("5:00 a. m."): no se agrega otro
            errores.add(com.get("nombre") + " está cerrado ahora. Abre a las " + horaBonita(String.valueOf(com.get("abre_texto"))));
        }
        double km = TarifaServicio.haversineKm(Datos.num(com.get("lat")), Datos.num(com.get("lng")), p.destinoLat(), p.destinoLng());
        if (km > ((BigDecimal) com.get("radio_km")).doubleValue()) {
            errores.add(com.get("nombre") + " no entrega en esa dirección (queda a " + String.format("%.1f", km) + " km).");
        }

        // Productos y opciones del comercio, de una vez
        Set<Long> ids = p.lineas().stream().map(Linea::productoId).collect(Collectors.toSet());
        Map<Long, Map<String, Object>> productos = new HashMap<>();
        for (Map<String, Object> f : db.queryForList(
                "SELECT * FROM productos_domicilio WHERE comercio_id = ? AND id = ANY (?::bigint[])", p.comercioId(),
                "{" + ids.stream().map(String::valueOf).collect(Collectors.joining(",")) + "}")) {
            productos.put(Datos.aLong(f.get("id")), f);
        }
        Map<Long, List<Map<String, Object>>> grupos = new CatalogoDomicilios(db).gruposDe(p.comercioId());
        boolean licores = "licores".equals(com.get("categoria"));
        boolean soloMayores = licores;

        List<Map<String, Object>> lineas = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (Linea l : p.lineas()) {
            Map<String, Object> prod = productos.get(l.productoId());
            if (prod == null) {
                errores.add("Un producto de tu carrito ya no existe en este comercio. Quítalo para continuar.");
                continue;
            }
            String nombre = String.valueOf(prod.get("nombre"));
            List<String> problemas = new ArrayList<>();
            if (!Boolean.TRUE.equals(prod.get("disponible"))) problemas.add(nombre + " está agotado.");
            soloMayores |= Boolean.TRUE.equals(prod.get("solo_mayores"));
            if (l.nota() != null && ContenidoProhibido.detectar(l.nota()).isPresent()) {
                problemas.add("La nota de " + nombre + " menciona algo que no podemos llevar.");
            }

            // Opciones elegidas: cada una de un grupo de este producto, disponible, y cada grupo dentro de su mínimo y máximo
            Set<Long> elegidas = new HashSet<>(l.opciones() == null ? List.of() : l.opciones());
            List<Map<String, Object>> copia = new ArrayList<>();
            BigDecimal extras = BigDecimal.ZERO;
            Set<Long> usadas = new HashSet<>();
            for (Map<String, Object> g : grupos.getOrDefault(l.productoId(), List.of())) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> ops = (List<Map<String, Object>>) g.get("opciones");
                int marcadas = 0;
                for (Map<String, Object> o : ops) {
                    Long oid = Datos.aLong(o.get("id"));
                    if (!elegidas.contains(oid)) continue;
                    usadas.add(oid);
                    if (!Boolean.TRUE.equals(o.get("disponible"))) {
                        problemas.add(o.get("nombre") + " ya no está disponible en " + nombre + ".");
                        continue;
                    }
                    marcadas++;
                    BigDecimal extra = (BigDecimal) o.get("precio_extra");
                    extras = extras.add(extra);
                    copia.add(Map.of("grupo", g.get("nombre"), "nombre", o.get("nombre"), "precio_extra", extra));
                }
                int min = ((Number) g.get("minimo")).intValue();
                int max = ((Number) g.get("maximo")).intValue();
                if (marcadas < min) problemas.add("En " + nombre + " elige " + (min == 1 ? "una opción" : min + " opciones") + " de «" + g.get("nombre") + "».");
                if (marcadas > max) problemas.add("En " + nombre + " puedes elegir hasta " + max + " en «" + g.get("nombre") + "».");
            }
            if (!usadas.containsAll(elegidas)) problemas.add("Una opción de " + nombre + " no corresponde a este producto.");

            BigDecimal unitario = ((BigDecimal) prod.get("precio")).add(extras);
            BigDecimal total = unitario.multiply(BigDecimal.valueOf(l.cantidad()));
            subtotal = subtotal.add(total);
            Map<String, Object> linea = new LinkedHashMap<>();
            linea.put("producto_id", l.productoId());
            linea.put("nombre", nombre);
            linea.put("cantidad", l.cantidad());
            linea.put("opciones", copia);
            linea.put("nota", Datos.vacioANull(l.nota()));
            linea.put("precio_unitario", unitario);
            linea.put("total_linea", total);
            linea.put("problemas", problemas);
            lineas.add(linea);
            errores.addAll(problemas);
        }

        BigDecimal minimo = (BigDecimal) com.get("pedido_minimo");
        if (subtotal.compareTo(minimo) < 0) {
            errores.add("El pedido mínimo en " + com.get("nombre") + " es " + CuponesDomicilio.pesos(minimo) + ": agrega "
                    + CuponesDomicilio.pesos(minimo.subtract(subtotal)) + " más.");
        }
        BigDecimal tope = db.queryForObject("SELECT fn_config_num('tope_compra_domicilio', 500000)", BigDecimal.class);
        if (subtotal.compareTo(tope) > 0) {
            errores.add("Por ahora el domiciliario puede pagar pedidos de hasta " + CuponesDomicilio.pesos(tope) + ".");
        }
        if (soloMayores && !mayorConfirmado() && !Boolean.TRUE.equals(p.confirmaMayorEdad())) {
            errores.add("Tu pedido tiene productos para mayores de 18 años: confirma que eres mayor de edad.");
        }

        TarifaServicio.Ruta ruta = tarifas.ruta(Datos.num(com.get("lat")), Datos.num(com.get("lng")), p.destinoLat(), p.destinoLng());
        BigDecimal domicilio = tarifas.cotizar("domicilio", "moto", ruta);
        CuponesDomicilio.Resultado cupon = cupones.evaluar(p.cupon(), sesion.usuarioId(), p.comercioId(), subtotal, domicilio);
        BigDecimal total = subtotal.add(domicilio).subtract(cupon.descuento()).max(BigDecimal.ZERO);
        int minutos = ((Number) com.get("minutos_preparacion")).intValue() + ruta.duracionMin();
        return new Calculo(com, lineas, subtotal, domicilio, cupon, total, minutos, soloMayores, errores);
    }

    private boolean mayorConfirmado() {
        return Boolean.TRUE.equals(db.queryForObject("SELECT mayor_edad_confirmado_en IS NOT NULL FROM usuarios WHERE id = ?",
                Boolean.class, sesion.usuarioId()));
    }

    /** "18:00" → "6:00 p. m." */
    static String horaBonita(String hhmm) {
        String[] p = hhmm.split(":");
        int h = Integer.parseInt(p[0]);
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return h12 + ":" + p[1] + (h < 12 ? " a. m." : " p. m.");
    }

    /** "2x Hamburguesa clásica (Doble, Queso extra) · 1x Papas" para el domiciliario y el historial. */
    static String resumen(List<Map<String, Object>> lineas) {
        String r = lineas.stream().map(l -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ops = (List<Map<String, Object>>) l.get("opciones");
            String o = ops.isEmpty() ? "" : " (" + ops.stream().map(x -> String.valueOf(x.get("nombre"))).collect(Collectors.joining(", ")) + ")";
            return l.get("cantidad") + "x " + l.get("nombre") + o;
        }).collect(Collectors.joining(" · "));
        return Datos.recortar(r, 500);
    }

    private String escribir(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }
}
