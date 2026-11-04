package com.movify.backend.administracion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.movify.backend.comun.Datos;
import com.movify.backend.seguridad.VigilanciaSeguridad;

/**
 * Vigilancia de actividad sospechosa para el admin (permiso seguridad.ver; soporte no lo tiene).
 *  - IPs con muchos inicios de sesión fallidos en 24 h (registro_accesos)
 *  - cuentas con bloqueo temporal por intentos fallidos
 *  - eventos recientes en memoria: límites superados, intentos de entrar al panel sin permiso
 */
@RestController
@RequestMapping("/api/admin/seguridad")
public class AdminSeguridadControlador {

    private final JdbcTemplate db;
    private final VigilanciaSeguridad vigilancia;

    public AdminSeguridadControlador(JdbcTemplate db, VigilanciaSeguridad vigilancia) {
        this.db = db;
        this.vigilancia = vigilancia;
    }

    @GetMapping("/actividad")
    @PreAuthorize("hasAuthority('seguridad.ver')")
    public Map<String, Object> actividad() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("resumen_24h", db.queryForMap("""
                SELECT count(*) FILTER (WHERE resultado = 'exitoso')  AS exitosos,
                       count(*) FILTER (WHERE resultado <> 'exitoso') AS fallidos,
                       count(DISTINCT ip_origen) FILTER (WHERE resultado <> 'exitoso') AS ips_con_fallos
                FROM registro_accesos WHERE creado_en > now() - INTERVAL '24 hours'
                """));
        r.put("ips_sospechosas", db.queryForList("""
                SELECT ip_origen AS ip, count(*) AS fallidos,
                       count(DISTINCT identificador) AS cuentas_distintas,
                       max(creado_en) AS ultimo
                FROM registro_accesos
                WHERE creado_en > now() - INTERVAL '24 hours' AND resultado <> 'exitoso' AND ip_origen IS NOT NULL
                GROUP BY ip_origen HAVING count(*) >= 5
                ORDER BY count(*) DESC LIMIT 30
                """));
        List<Map<String, Object>> bloqueadas = db.queryForList("""
                SELECT id, nombres, telefono, email, acceso_bloqueado_hasta AS hasta
                FROM usuarios WHERE acceso_bloqueado_hasta > now() ORDER BY acceso_bloqueado_hasta DESC LIMIT 50
                """);
        for (Map<String, Object> b : bloqueadas) {
            b.put("telefono", Datos.ocultarTelefono((String) b.get("telefono")));
            b.put("email", Datos.ocultarCorreo((String) b.get("email")));
        }
        r.put("cuentas_bloqueadas_temporalmente", bloqueadas);
        r.put("eventos_recientes", vigilancia.recientes().stream().limit(100).toList());
        return r;
    }
}
