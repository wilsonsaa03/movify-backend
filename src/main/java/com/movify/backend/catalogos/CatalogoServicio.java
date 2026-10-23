package com.movify.backend.catalogos;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Lectura de los catálogos públicos de la base (opciones, nombres y colores que pinta la app). */
@Service
public class CatalogoServicio {

    private final JdbcTemplate db;

    public CatalogoServicio(JdbcTemplate db) {
        this.db = db;
    }

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

    public List<Map<String, Object>> documentosLegales() {
        return db.queryForList("SELECT id, tipo, version, url, vigente_desde, obligatorio FROM documentos_legales WHERE activo ORDER BY tipo");
    }

    /** true si la base responde (healthcheck del deploy). */
    public boolean baseResponde() {
        Integer uno = db.queryForObject("SELECT 1", Integer.class);
        return uno != null && uno == 1;
    }
}
