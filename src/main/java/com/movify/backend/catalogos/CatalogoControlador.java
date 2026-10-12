package com.movify.backend.catalogos;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catálogos públicos de la base: la app los lee para pintar opciones, nombres y colores
 * en vez de tenerlos "quemados" en el código.
 */
@RestController
@RequestMapping("/api")
public class CatalogoControlador {

    private final JdbcTemplate db;

    public CatalogoControlador(JdbcTemplate db) {
        this.db = db;
    }

    @GetMapping("/catalogos")
    public Map<String, Object> catalogos() {
        return Map.of(
                "tipos_servicio", db.queryForList("SELECT codigo, nombre, descripcion FROM tipos_servicio ORDER BY codigo"),
                "tipos_vehiculo", db.queryForList("SELECT codigo, nombre, capacidad_pasajeros FROM tipos_vehiculo ORDER BY codigo"),
                "servicio_vehiculo", db.queryForList("SELECT tipo_servicio, tipo_vehiculo FROM servicio_vehiculo_permitido"),
                "estados", db.queryForList("SELECT ambito, codigo, nombre, descripcion, color, es_inicial, es_final, orden FROM estados ORDER BY ambito, orden"),
                "motivos_cancelacion", db.queryForList("SELECT id, actor, descripcion FROM motivos_cancelacion WHERE activo ORDER BY id"),
                "tipos_documento", db.queryForList("""
                        SELECT codigo, nombre, aplica_a, requiere_vencimiento, obligatorio_moto, obligatorio_taxi
                        FROM tipos_documento ORDER BY aplica_a, nombre
                        """),
                "ciudad", db.queryForObject("SELECT valor FROM configuracion_sistema WHERE clave = 'ciudad_operacion'", String.class));
    }

    @GetMapping("/catalogos/documentos-legales")
    public List<Map<String, Object>> documentosLegales() {
        return db.queryForList("SELECT id, tipo, version, url, vigente_desde, obligatorio FROM documentos_legales WHERE activo ORDER BY tipo");
    }

    /** Healthcheck del deploy (también comprueba que la base responda). */
    @GetMapping("/test/ping")
    public String ping() {
        Integer uno = db.queryForObject("SELECT 1", Integer.class);
        return uno != null && uno == 1 ? "pong - Backend is working successfully!" : "pong";
    }
}
