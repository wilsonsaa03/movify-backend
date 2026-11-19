package com.movify.backend.domicilios;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.movify.backend.comun.ArchivoServicio;
import com.movify.backend.comun.Celular;
import com.movify.backend.comun.Datos;
import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.comun.ReglaNegocioException;
import com.movify.backend.seguridad.SesionActual;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Catálogo de domicilios en el panel (por ahora lo carga el admin): comercios, productos con grupos de opciones
 * y cupones simulados. Cada cambio queda en auditoria_admin.
 */
@Service
public class AdminCatalogoDomicilios {

    private final JdbcTemplate db;
    private final SesionActual sesion;
    private final ArchivoServicio archivos;
    private final CatalogoDomicilios catalogo;

    public AdminCatalogoDomicilios(JdbcTemplate db, SesionActual sesion, ArchivoServicio archivos, CatalogoDomicilios catalogo) {
        this.db = db;
        this.sesion = sesion;
        this.archivos = archivos;
        this.catalogo = catalogo;
    }

    // ───────────── Comercios ─────────────

    public record ComercioRequest(
            @NotBlank @Size(max = 120) String nombre,
            @NotBlank @Pattern(regexp = "comida|farmacia|mercado|licores|tienda") String categoria,
            @Size(max = 300) String descripcion,
            @Size(max = 300) @JsonProperty("foto_url") String fotoUrl,
            @NotBlank @Size(max = 200) String direccion,
            @Size(max = 80) String barrio,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
            @Size(max = 20) String telefono,
            @NotBlank @Pattern(regexp = "\\d{2}:\\d{2}") String abre,
            @NotBlank @Pattern(regexp = "\\d{2}:\\d{2}") String cierra,
            @NotNull @DecimalMin("0") @JsonProperty("pedido_minimo") BigDecimal pedidoMinimo,
            @NotNull @DecimalMin("0.5") @DecimalMax("30") @JsonProperty("radio_km") BigDecimal radioKm,
            @NotNull @Min(0) @Max(180) @JsonProperty("minutos_preparacion") Integer minutosPreparacion,
            Boolean activo,
            Integer orden) {
    }

    public List<Map<String, Object>> comercios() {
        return db.queryForList("""
                SELECT c.id, c.nombre, c.categoria, c.descripcion, c.foto_url, c.direccion, c.barrio, c.lat, c.lng, c.telefono,
                       to_char(c.abre, 'HH24:MI') AS abre, to_char(c.cierra, 'HH24:MI') AS cierra, c.pedido_minimo, c.radio_km,
                       c.minutos_preparacion, c.activo, c.orden, """ + CatalogoDomicilios.ABIERTO + """
                        AS abierto,
                       (SELECT count(*) FROM productos_domicilio p WHERE p.comercio_id = c.id) AS productos,
                       (SELECT count(*) FROM servicio_domicilio d WHERE d.comercio_id = c.id) AS pedidos
                FROM comercios_domicilio c ORDER BY c.activo DESC, c.orden, c.nombre
                """);
    }

    @Transactional
    public Map<String, Object> guardarComercio(Long id, ComercioRequest r) {
        LocalTime abre = hora(r.abre());
        LocalTime cierra = hora(r.cierra());
        if (abre.equals(cierra)) throw new ReglaNegocioException(400, "La hora de abrir y la de cerrar no pueden ser iguales");
        String tel = Datos.vacioANull(r.telefono()) == null ? null : Celular.normalizarContacto(r.telefono());
        Object[] valores = { r.nombre().trim(), r.categoria(), Datos.vacioANull(r.descripcion()), Datos.vacioANull(r.fotoUrl()),
                r.direccion().trim(), Datos.vacioANull(r.barrio()), r.lat(), r.lng(), tel, abre, cierra, r.pedidoMinimo(), r.radioKm(),
                r.minutosPreparacion(), r.activo() == null || r.activo(), r.orden() == null ? 0 : r.orden() };
        Long nuevo;
        if (id == null) {
            nuevo = db.queryForObject("""
                    INSERT INTO comercios_domicilio (nombre, categoria, descripcion, foto_url, direccion, barrio, lat, lng, telefono,
                           abre, cierra, pedido_minimo, radio_km, minutos_preparacion, activo, orden)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, valores);
        } else {
            Object[] conId = java.util.Arrays.copyOf(valores, valores.length + 1);
            conId[valores.length] = id;
            int n = db.update("""
                    UPDATE comercios_domicilio SET nombre = ?, categoria = ?, descripcion = ?, foto_url = ?, direccion = ?, barrio = ?,
                           lat = ?, lng = ?, telefono = ?, abre = ?, cierra = ?, pedido_minimo = ?, radio_km = ?,
                           minutos_preparacion = ?, activo = ?, orden = ?
                    WHERE id = ?
                    """, conId);
            if (n == 0) throw new RecursoNoEncontradoException("Comercio no encontrado");
            nuevo = id;
        }
        auditar(id == null ? "comercio_creado" : "comercio_editado", "comercio_domicilio", nuevo, r.nombre());
        return Map.of("id", nuevo, "message", id == null ? "Comercio creado" : "Comercio guardado");
    }

    /** Los pedidos viejos conservan la copia de sus productos (servicio_domicilio_items). */
    @Transactional
    public Map<String, Object> borrarComercio(Long id) {
        int n = db.update("DELETE FROM comercios_domicilio WHERE id = ?", id);
        if (n == 0) throw new RecursoNoEncontradoException("Comercio no encontrado");
        auditar("comercio_borrado", "comercio_domicilio", id, null);
        return Map.of("message", "Comercio borrado");
    }

    // ───────────── Productos (con sus grupos de opciones) ─────────────

    public record OpcionRequest(@NotBlank @Size(max = 60) String nombre,
            @NotNull @DecimalMin("0") @JsonProperty("precio_extra") BigDecimal precioExtra, Boolean disponible) {
    }

    public record GrupoRequest(@NotBlank @Size(max = 60) String nombre, @NotNull @Min(0) @Max(20) Integer minimo,
            @NotNull @Min(1) @Max(20) Integer maximo, @NotNull @Size(min = 1, max = 30) List<@Valid OpcionRequest> opciones) {
    }

    public record ProductoRequest(
            @NotBlank @Size(max = 60) String seccion,
            @NotBlank @Size(max = 120) String nombre,
            @Size(max = 300) String descripcion,
            @Size(max = 300) @JsonProperty("foto_url") String fotoUrl,
            @NotNull @DecimalMin("0") BigDecimal precio,
            Boolean disponible,
            @JsonProperty("solo_mayores") Boolean soloMayores,
            Integer orden,
            @Size(max = 12) List<@Valid GrupoRequest> grupos) {
    }

    public Map<String, Object> productos(Long comercioId) {
        existeComercio(comercioId);
        List<Map<String, Object>> ps = db.queryForList("""
                SELECT id, seccion, nombre, descripcion, foto_url, precio, disponible, solo_mayores, orden
                FROM productos_domicilio WHERE comercio_id = ? ORDER BY min(id) OVER (PARTITION BY seccion), orden, nombre
                """, comercioId);
        Map<Long, List<Map<String, Object>>> grupos = catalogo.gruposDe(comercioId);
        for (Map<String, Object> p : ps) p.put("grupos", grupos.getOrDefault(Datos.aLong(p.get("id")), List.of()));
        return Map.of("comercio_id", comercioId, "productos", ps);
    }

    @Transactional
    public Map<String, Object> guardarProducto(Long comercioId, Long productoId, ProductoRequest r) {
        List<GrupoRequest> grupos = r.grupos() == null ? List.of() : r.grupos();
        for (GrupoRequest g : grupos) {
            if (g.minimo() > g.maximo()) throw new ReglaNegocioException(400, "En «" + g.nombre() + "» el mínimo no puede ser mayor que el máximo");
            if (g.maximo() > g.opciones().size()) {
                throw new ReglaNegocioException(400, "En «" + g.nombre() + "» el máximo no puede ser mayor que el número de opciones");
            }
        }
        Long id;
        if (productoId == null) {
            existeComercio(comercioId);
            id = db.queryForObject("""
                    INSERT INTO productos_domicilio (comercio_id, seccion, nombre, descripcion, foto_url, precio, disponible, solo_mayores, orden)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, comercioId, r.seccion().trim(), r.nombre().trim(), Datos.vacioANull(r.descripcion()),
                    Datos.vacioANull(r.fotoUrl()), r.precio(), r.disponible() == null || r.disponible(),
                    Boolean.TRUE.equals(r.soloMayores()), r.orden() == null ? 0 : r.orden());
        } else {
            int n = db.update("""
                    UPDATE productos_domicilio SET seccion = ?, nombre = ?, descripcion = ?, foto_url = ?, precio = ?, disponible = ?,
                           solo_mayores = ?, orden = ?
                    WHERE id = ?
                    """, r.seccion().trim(), r.nombre().trim(), Datos.vacioANull(r.descripcion()), Datos.vacioANull(r.fotoUrl()),
                    r.precio(), r.disponible() == null || r.disponible(), Boolean.TRUE.equals(r.soloMayores()),
                    r.orden() == null ? 0 : r.orden(), productoId);
            if (n == 0) throw new RecursoNoEncontradoException("Producto no encontrado");
            id = productoId;
            // Los grupos se reemplazan completos (los pedidos ya hechos guardan su copia en JSON)
            db.update("DELETE FROM grupos_opciones_domicilio WHERE producto_id = ?", id);
        }
        short og = 0;
        for (GrupoRequest g : grupos) {
            Long gid = db.queryForObject("""
                    INSERT INTO grupos_opciones_domicilio (producto_id, nombre, minimo, maximo, orden) VALUES (?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, id, g.nombre().trim(), g.minimo(), g.maximo(), og++);
            short oo = 0;
            for (OpcionRequest o : g.opciones()) {
                db.update("INSERT INTO opciones_domicilio (grupo_id, nombre, precio_extra, disponible, orden) VALUES (?, ?, ?, ?, ?)",
                        gid, o.nombre().trim(), o.precioExtra(), o.disponible() == null || o.disponible(), oo++);
            }
        }
        auditar(productoId == null ? "producto_creado" : "producto_editado", "producto_domicilio", id, r.nombre());
        return Map.of("id", id, "message", productoId == null ? "Producto creado" : "Producto guardado");
    }

    /** Marcar agotado / disponible sin abrir todo el formulario. */
    public Map<String, Object> disponibilidad(Long productoId, boolean disponible) {
        int n = db.update("UPDATE productos_domicilio SET disponible = ? WHERE id = ?", disponible, productoId);
        if (n == 0) throw new RecursoNoEncontradoException("Producto no encontrado");
        return Map.of("message", disponible ? "Producto disponible" : "Producto agotado");
    }

    @Transactional
    public Map<String, Object> borrarProducto(Long productoId) {
        int n = db.update("DELETE FROM productos_domicilio WHERE id = ?", productoId);
        if (n == 0) throw new RecursoNoEncontradoException("Producto no encontrado");
        auditar("producto_borrado", "producto_domicilio", productoId, null);
        return Map.of("message", "Producto borrado");
    }

    // ───────────── Cupones (simulados) ─────────────

    public record CuponRequest(
            @NotBlank @Size(max = 30) String codigo,
            @NotBlank @Size(max = 200) String descripcion,
            @NotBlank @Pattern(regexp = "porcentaje|valor|envio") String tipo,
            @NotNull @DecimalMin("1") BigDecimal valor,
            @JsonProperty("tope_descuento") BigDecimal topeDescuento,
            @NotNull @DecimalMin("0") @JsonProperty("minimo_compra") BigDecimal minimoCompra,
            @JsonProperty("comercio_id") Long comercioId,
            @JsonProperty("solo_primer_pedido") Boolean soloPrimerPedido,
            @NotNull @Min(1) @Max(100) @JsonProperty("usos_por_cliente") Integer usosPorCliente,
            @Min(1) @JsonProperty("usos_totales") Integer usosTotales,
            @JsonProperty("vence_en") OffsetDateTime venceEn,
            Boolean activo) {
    }

    public List<Map<String, Object>> cupones() {
        return db.queryForList("""
                SELECT cu.*, c.nombre AS comercio_nombre,
                       (SELECT count(*) FROM cupones_usados u JOIN servicios s ON s.id = u.servicio_id
                         WHERE u.cupon_id = cu.id AND s.estado NOT IN ('cancelado', 'expirado')) AS usados,
                       (cu.vence_en IS NOT NULL AND cu.vence_en < now()) AS vencido
                FROM cupones_domicilio cu LEFT JOIN comercios_domicilio c ON c.id = cu.comercio_id
                ORDER BY cu.activo DESC, cu.creado_en DESC
                """);
    }

    @Transactional
    public Map<String, Object> guardarCupon(Long id, CuponRequest r) {
        String codigo = CuponesDomicilio.normalizar(r.codigo());
        if (codigo.length() < 3) throw new ReglaNegocioException(400, "El código debe tener al menos 3 letras o números");
        if ("porcentaje".equals(r.tipo()) && r.valor().compareTo(new BigDecimal("100")) > 0) {
            throw new ReglaNegocioException(400, "Un porcentaje no puede ser mayor que 100");
        }
        Object[] v = { codigo, r.descripcion().trim(), r.tipo(), r.valor(), r.topeDescuento(), r.minimoCompra(), r.comercioId(),
                Boolean.TRUE.equals(r.soloPrimerPedido()), r.usosPorCliente(), r.usosTotales(), r.venceEn(),
                r.activo() == null || r.activo() };
        Long nuevo;
        if (id == null) {
            nuevo = db.queryForObject("""
                    INSERT INTO cupones_domicilio (codigo, descripcion, tipo, valor, tope_descuento, minimo_compra, comercio_id,
                           solo_primer_pedido, usos_por_cliente, usos_totales, vence_en, activo)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, v);
        } else {
            Object[] conId = java.util.Arrays.copyOf(v, v.length + 1);
            conId[v.length] = id;
            int n = db.update("""
                    UPDATE cupones_domicilio SET codigo = ?, descripcion = ?, tipo = ?, valor = ?, tope_descuento = ?, minimo_compra = ?,
                           comercio_id = ?, solo_primer_pedido = ?, usos_por_cliente = ?, usos_totales = ?, vence_en = ?, activo = ?
                    WHERE id = ?
                    """, conId);
            if (n == 0) throw new RecursoNoEncontradoException("Cupón no encontrado");
            nuevo = id;
        }
        auditar(id == null ? "cupon_creado" : "cupon_editado", "cupon_domicilio", nuevo, codigo);
        return Map.of("id", nuevo, "codigo", codigo, "message", id == null ? "Cupón creado" : "Cupón guardado");
    }

    @Transactional
    public Map<String, Object> borrarCupon(Long id) {
        int n = db.update("DELETE FROM cupones_domicilio WHERE id = ?", id);
        if (n == 0) throw new RecursoNoEncontradoException("Cupón no encontrado");
        auditar("cupon_borrado", "cupon_domicilio", id, null);
        return Map.of("message", "Cupón borrado");
    }

    // ───────────── Fotos ─────────────

    /** Foto de un comercio o producto (JPG/PNG/WEBP). Devuelve la URL pública para guardarla en foto_url. */
    public Map<String, Object> subirFoto(MultipartFile foto) {
        ArchivoServicio.Guardado g = archivos.guardar(foto, "catalogo", false);
        return Map.of("foto_url", "/api/domicilios/fotos/" + g.nombre());
    }

    public ArchivoServicio.Archivo foto(String nombre) {
        if (nombre == null || !nombre.startsWith("catalogo_")) throw new RecursoNoEncontradoException("Foto no encontrada");
        return archivos.obtener(nombre);
    }

    // ───────────── Ayudas ─────────────

    private void existeComercio(Long id) {
        if (!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS (SELECT 1 FROM comercios_domicilio WHERE id = ?)", Boolean.class, id))) {
            throw new RecursoNoEncontradoException("Comercio no encontrado");
        }
    }

    private static LocalTime hora(String hhmm) {
        try {
            return LocalTime.parse(hhmm);
        } catch (Exception e) {
            throw new ReglaNegocioException(400, "Hora no válida: usa el formato 08:00");
        }
    }

    private void auditar(String accion, String entidad, Long entidadId, String nombre) {
        String json = nombre == null ? "{}" : "{\"nombre\":\"" + nombre.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        db.update("INSERT INTO auditoria_admin (admin_id, accion, entidad, entidad_id, detalle) VALUES (?, ?, ?, ?, ?::jsonb)",
                sesion.usuarioId(), accion, entidad, entidadId, json);
    }
}
