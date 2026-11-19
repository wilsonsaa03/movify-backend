package com.movify.backend.domicilios;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.movify.backend.comun.Datos;
import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.comun.ReglaNegocioException;

/**
 * Lo que ve el cliente al pedir un domicilio con catálogo (estilo Rappi): comercios aliados con su estado
 * (abierto / "abre a las 6:00 p. m."), distancia, tiempo estimado y pedido mínimo; y dentro de cada comercio
 * sus productos por sección con los grupos de opciones (tamaños, adiciones, "sin cebolla").
 */
@Service
public class CatalogoDomicilios {

    public static final List<String> CATEGORIAS = List.of("comida", "farmacia", "mercado", "licores", "tienda");
    /** Velocidad promedio del domiciliario en moto por la ciudad, para el tiempo estimado de la lista. */
    private static final double KMH_MOTO = 22;

    /** true si el comercio está abierto ahora (hora de Buenaventura); soporta horarios que pasan la medianoche. */
    static final String ABIERTO = """
            (c.activo AND CASE WHEN c.abre <= c.cierra
                  THEN (now() AT TIME ZONE 'America/Bogota')::time >= c.abre AND (now() AT TIME ZONE 'America/Bogota')::time < c.cierra
                  ELSE (now() AT TIME ZONE 'America/Bogota')::time >= c.abre OR (now() AT TIME ZONE 'America/Bogota')::time < c.cierra
             END)
            """;

    private final JdbcTemplate db;

    public CatalogoDomicilios(JdbcTemplate db) {
        this.db = db;
    }

    /**
     * Comercios activos, primero los abiertos y los más cercanos. q busca en el nombre del comercio y de sus productos.
     * Con lat/lng se calcula la distancia y se ocultan los que no entregan ahí (radio del comercio).
     */
    public List<Map<String, Object>> comercios(String categoria, String q, Double lat, Double lng) {
        String cat = Datos.vacioANull(categoria);
        if (cat != null && !CATEGORIAS.contains(cat)) throw new ReglaNegocioException(400, "Categoría no válida");
        String texto = Datos.vacioANull(q) == null ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        List<Map<String, Object>> filas = db.queryForList("""
                SELECT c.id, c.nombre, c.categoria, c.descripcion, c.foto_url, c.direccion, c.barrio, c.lat, c.lng,
                       to_char(c.abre, 'HH24:MI') AS abre, to_char(c.cierra, 'HH24:MI') AS cierra, c.pedido_minimo, c.radio_km,
                       c.minutos_preparacion, """ + ABIERTO + """
                        AS abierto,
                       CASE WHEN CAST(? AS float8) IS NULL THEN NULL
                            ELSE round(fn_distancia_km(c.lat, c.lng, CAST(? AS float8), CAST(? AS float8))::numeric, 1) END AS distancia_km,
                       (c.categoria = 'licores' OR EXISTS (SELECT 1 FROM productos_domicilio p WHERE p.comercio_id = c.id AND p.solo_mayores)) AS tiene_mayores,
                       (SELECT count(*) FROM productos_domicilio p WHERE p.comercio_id = c.id AND p.disponible) AS productos
                FROM comercios_domicilio c
                WHERE c.activo
                  AND (CAST(? AS varchar) IS NULL OR c.categoria = ?)
                  AND (CAST(? AS varchar) IS NULL OR lower(c.nombre) LIKE ?
                       OR EXISTS (SELECT 1 FROM productos_domicilio p WHERE p.comercio_id = c.id AND p.disponible
                                    AND (lower(p.nombre) LIKE ? OR lower(p.seccion) LIKE ?)))
                ORDER BY c.orden, c.nombre
                """, lat, lat, lng, cat, cat, texto, texto, texto, texto);
        List<Map<String, Object>> r = new ArrayList<>();
        for (Map<String, Object> f : filas) {
            Object d = f.get("distancia_km");
            // Fuera del radio del comercio: no entrega en esa dirección
            if (d instanceof Number km && km.doubleValue() > ((Number) f.get("radio_km")).doubleValue()) continue;
            int prep = ((Number) f.get("minutos_preparacion")).intValue();
            int viaje = d instanceof Number km ? (int) Math.ceil(km.doubleValue() / KMH_MOTO * 60) : 15;
            f.put("minutos_min", prep + viaje);
            f.put("minutos_max", prep + viaje + 10);
            r.add(f);
        }
        // Abiertos primero, luego los más cercanos (sort estable: a igual distancia se respeta el orden del admin)
        r.sort((a, b) -> {
            int ab = Boolean.compare(Boolean.TRUE.equals(b.get("abierto")), Boolean.TRUE.equals(a.get("abierto")));
            if (ab != 0) return ab;
            double da = a.get("distancia_km") instanceof Number n ? n.doubleValue() : 999;
            double dbb = b.get("distancia_km") instanceof Number n ? n.doubleValue() : 999;
            return Double.compare(da, dbb);
        });
        return r;
    }

    /** Un comercio con sus productos por sección; cada producto con sus grupos de opciones. Agotados incluidos (en gris). */
    public Map<String, Object> comercio(Long id, Double lat, Double lng) {
        Map<String, Object> c = new LinkedHashMap<>(db.queryForList("""
                SELECT c.id, c.nombre, c.categoria, c.descripcion, c.foto_url, c.direccion, c.barrio, c.lat, c.lng,
                       to_char(c.abre, 'HH24:MI') AS abre, to_char(c.cierra, 'HH24:MI') AS cierra, c.pedido_minimo, c.radio_km,
                       c.minutos_preparacion, """ + ABIERTO + """
                        AS abierto,
                       CASE WHEN CAST(? AS float8) IS NULL THEN NULL
                            ELSE round(fn_distancia_km(c.lat, c.lng, CAST(? AS float8), CAST(? AS float8))::numeric, 1) END AS distancia_km
                FROM comercios_domicilio c WHERE c.id = ? AND c.activo
                """, lat, lat, lng, id).stream().findFirst()
                .orElseThrow(() -> new RecursoNoEncontradoException("Ese comercio ya no está disponible")));
        List<Map<String, Object>> productos = db.queryForList("""
                SELECT id, seccion, nombre, descripcion, foto_url, precio, disponible, solo_mayores
                FROM productos_domicilio WHERE comercio_id = ?
                -- Secciones en el orden en que el admin las creó (la del primer producto), y dentro de cada una por orden
                ORDER BY min(id) OVER (PARTITION BY seccion), orden, nombre
                """, id);
        Map<Long, List<Map<String, Object>>> grupos = gruposDe(id);
        Map<String, List<Map<String, Object>>> secciones = new LinkedHashMap<>();
        boolean mayores = "licores".equals(c.get("categoria"));
        for (Map<String, Object> p : productos) {
            Long pid = Datos.aLong(p.get("id"));
            p.put("grupos", grupos.getOrDefault(pid, List.of()));
            if ("licores".equals(c.get("categoria"))) p.put("solo_mayores", true);
            mayores |= Boolean.TRUE.equals(p.get("solo_mayores"));
            secciones.computeIfAbsent(String.valueOf(p.get("seccion")), k -> new ArrayList<>()).add(p);
        }
        List<Map<String, Object>> lista = new ArrayList<>();
        secciones.forEach((nombre, ps) -> lista.add(Map.of("nombre", nombre, "productos", ps)));
        c.put("secciones", lista);
        c.put("tiene_mayores", mayores);
        Object d = c.get("distancia_km");
        c.put("entrega_aqui", !(d instanceof Number km) || km.doubleValue() <= ((Number) c.get("radio_km")).doubleValue());
        return c;
    }

    /** producto → [grupo { id, nombre, minimo, maximo, obligatorio, opciones [{ id, nombre, precio_extra, disponible }] }] */
    Map<Long, List<Map<String, Object>>> gruposDe(Long comercioId) {
        List<Map<String, Object>> filas = db.queryForList("""
                SELECT g.producto_id, g.id AS grupo_id, g.nombre AS grupo, g.minimo, g.maximo, g.orden AS orden_g,
                       o.id AS opcion_id, o.nombre AS opcion, o.precio_extra, o.disponible
                FROM grupos_opciones_domicilio g
                JOIN productos_domicilio p ON p.id = g.producto_id
                LEFT JOIN opciones_domicilio o ON o.grupo_id = g.id
                WHERE p.comercio_id = ?
                ORDER BY g.producto_id, g.orden, g.id, o.orden, o.id
                """, comercioId);
        Map<Long, Map<Long, Map<String, Object>>> porProducto = new LinkedHashMap<>();
        for (Map<String, Object> f : filas) {
            Long pid = Datos.aLong(f.get("producto_id"));
            Long gid = Datos.aLong(f.get("grupo_id"));
            Map<String, Object> g = porProducto.computeIfAbsent(pid, k -> new LinkedHashMap<>()).computeIfAbsent(gid, k -> {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("id", gid);
                n.put("nombre", f.get("grupo"));
                n.put("minimo", f.get("minimo"));
                n.put("maximo", f.get("maximo"));
                n.put("obligatorio", ((Number) f.get("minimo")).intValue() >= 1);
                n.put("opciones", new ArrayList<Map<String, Object>>());
                return n;
            });
            if (f.get("opcion_id") != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> ops = (List<Map<String, Object>>) g.get("opciones");
                ops.add(Map.of("id", f.get("opcion_id"), "nombre", f.get("opcion"), "precio_extra", f.get("precio_extra"),
                        "disponible", f.get("disponible")));
            }
        }
        Map<Long, List<Map<String, Object>>> r = new LinkedHashMap<>();
        porProducto.forEach((pid, gs) -> r.put(pid, new ArrayList<>(gs.values())));
        return r;
    }
}
