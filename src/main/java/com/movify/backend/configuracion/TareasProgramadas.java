package com.movify.backend.configuracion;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.movify.backend.comun.Datos;
import com.movify.backend.notificaciones.NotificacionServicio;
import com.movify.backend.servicios.ProgramadoServicio;

/**
 * Tareas que el DER v2.3 deja como funciones de la base (sección 9):
 *   cada minuto  → solicitudes sin conductor pasan a "expirado" (como fn_expirar_solicitudes) y se le avisa al cliente
 *   cada minuto  → los viajes programados cuya hora se acerca empiezan a buscar conductor
 *   cada día     → fn_suspender_documentos_vencidos() (SOAT, licencia... vencidos)
 *   cada semana  → fn_purgar_datos()                  (códigos SMS, sesiones y rastros viejos)
 * Se pueden apagar con app.tareas.habilitadas=false (por ejemplo en las pruebas).
 */
@Component
@ConditionalOnProperty(name = "app.tareas.habilitadas", havingValue = "true", matchIfMissing = true)
public class TareasProgramadas {

    private static final Logger log = LoggerFactory.getLogger(TareasProgramadas.class);

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final NotificacionServicio avisos;
    private final ProgramadoServicio programados;

    public TareasProgramadas(JdbcTemplate db, TransactionTemplate tx, NotificacionServicio avisos,
            ProgramadoServicio programados) {
        this.db = db;
        this.tx = tx;
        this.avisos = avisos;
        this.programados = programados;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void expirarSolicitudes() {
        // Igual que fn_expirar_solicitudes(), pero devuelve a quién avisarle
        List<Map<String, Object>> vencidas = tx.execute(estado -> {
            db.queryForList("SELECT set_config('movify.actor', 'sistema', true)");
            return db.queryForList("""
                    UPDATE servicios SET estado = 'expirado'
                    WHERE estado = 'buscando' AND expira_busqueda_en < now()
                    RETURNING id, cliente_id, tipo_servicio, tipo_vehiculo
                    """);
        });
        if (vencidas == null || vencidas.isEmpty()) return;
        log.info("Solicitudes expiradas sin conductor: {}", vencidas.size());
        for (Map<String, Object> v : vencidas) {
            String que = switch (String.valueOf(v.get("tipo_servicio"))) {
                case "domicilio" -> "tu domicilio";
                case "encomienda" -> "tu envío";
                default -> "tu " + v.get("tipo_vehiculo");
            };
            avisos.avisar(Datos.aLong(v.get("cliente_id")), Datos.aLong(v.get("id")), NotificacionServicio.ESTADO,
                    "expirado", "Nadie tomó " + que, "Los conductores cercanos están ocupados. Intenta de nuevo u ofrece un poco más.");
        }
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    public void lanzarProgramados() {
        int n = programados.lanzarPendientes();
        if (n > 0) log.info("Viajes programados que empezaron a buscar conductor: {}", n);
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
