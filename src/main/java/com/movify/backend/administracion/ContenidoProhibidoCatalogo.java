package com.movify.backend.administracion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.movify.backend.comun.ContenidoProhibido;

/**
 * Carga en ContenidoProhibido las palabras que el admin agregó desde el panel (tabla palabras_prohibidas):
 * al arrancar y cada vez que se agrega o quita una. Así no hay que desplegar para sumar una palabra nueva.
 */
@Component
public class ContenidoProhibidoCatalogo {

    private static final Logger log = LoggerFactory.getLogger(ContenidoProhibidoCatalogo.class);

    private final JdbcTemplate db;

    public ContenidoProhibidoCatalogo(JdbcTemplate db) {
        this.db = db;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recargar() {
        try {
            Map<String, List<String>> prohibidas = new LinkedHashMap<>();
            db.queryForList("SELECT palabra, categoria FROM palabras_prohibidas WHERE tipo = 'prohibida' ORDER BY categoria, palabra")
                    .forEach(f -> prohibidas.computeIfAbsent((String) f.get("categoria"), k -> new java.util.ArrayList<>())
                            .add((String) f.get("palabra")));
            List<String> permitidas = db.queryForList(
                    "SELECT palabra FROM palabras_prohibidas WHERE tipo = 'permitida'", String.class);
            ContenidoProhibido.actualizarExtras(prohibidas, permitidas);
        } catch (RuntimeException e) {
            // Sin la tabla (base vieja) se sigue con la lista base: nunca se deja de filtrar
            log.warn("No se pudieron cargar las palabras prohibidas del panel: {}", e.getMessage());
        }
    }
}
