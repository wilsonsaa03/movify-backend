package com.movify.backend.configuracion;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tareas que el DER v2.3 deja como funciones de la base (sección 9):
 *   cada minuto  → fn_expirar_solicitudes()           (solicitudes sin conductor pasan a "expirado")
 *   cada día     → fn_suspender_documentos_vencidos() (SOAT, licencia... vencidos)
 *   cada semana  → fn_purgar_datos()                  (códigos SMS, sesiones y rastros viejos)
 * Se pueden apagar con app.tareas.habilitadas=false (por ejemplo en las pruebas).
 */
@Component
@ConditionalOnProperty(name = "app.tareas.habilitadas", havingValue = "true", matchIfMissing = true)
public class TareasProgramadas {

    private static final Logger log = LoggerFactory.getLogger(TareasProgramadas.class);

    private final JdbcTemplate db;

    public TareasProgramadas(JdbcTemplate db) {
        this.db = db;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void expirarSolicitudes() {
        Integer n = db.queryForObject("SELECT fn_expirar_solicitudes()", Integer.class);
        if (n != null && n > 0) log.info("Solicitudes expiradas sin conductor: {}", n);
    }

    @Scheduled(cron = "0 15 3 * * *", zone = "America/Bogota")
    public void suspenderDocumentosVencidos() {
        Integer n = db.queryForObject("SELECT fn_suspender_documentos_vencidos()", Integer.class);
        log.info("Conductores suspendidos por documentos vencidos: {}", n);
    }

    @Scheduled(cron = "0 30 3 * * SUN", zone = "America/Bogota")
    public void purgarDatos() {
        List<Map<String, Object>> r = db.queryForList("SELECT * FROM fn_purgar_datos()");
        log.info("Purga semanal: {}", r);
    }
}
