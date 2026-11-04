package com.movify.backend.configuracion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Revisión de arranque. Con APP_ENTORNO=produccion el backend NO arranca si encuentra una
 * configuración peligrosa; en desarrollo solo avisa en el log.
 *
 *  - Modos de prueba encendidos (SMS en consola, simulación de viajes).
 *  - CORS con orígenes http o comodín total.
 *  - Algún secreto IGUAL a uno que quedó publicado en el historial de git
 *    (se compara el SHA-256: aquí no queda ningún secreto escrito).
 *  - Código de registro de admin corto (debe estar vacío = cerrado, o tener 16+ caracteres).
 * Si el deploy no arranca por esto, el workflow restaura el jar anterior y el motivo sale en journalctl.
 * Al terminar de arrancar avisa si la base tiene las cuentas demo (clave 1357, scripts/datos_demo.sql).
 */
@Component
public class ProduccionSegura {

    private static final Logger log = LoggerFactory.getLogger(ProduccionSegura.class);

    /** SHA-256 de secretos que estuvieron en el repositorio público (JWT, BD, Gmail, código admin). */
    private static final Set<String> FILTRADOS = Set.of(
            "647faa7e86b9aafbdf801336b84bbe894f5acb821b32a1c4c6d901961a445141",
            "2540b507843bfff1cb83a5047bf23277b8b4a28f56a10522e29068cc8a38bf66",
            "a54e959d951c1b62e9d8d928555b4bc30df14f3796e4724fb9e7903980de5f99",
            "a954d4d39936378545bb8b41bf464ed510f6446f115cebb445f5d262c3d65bd1",
            "8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92",
            "03434ebf65321692cfe09d11a29dfba22c61ca58b6a3f981e5b331daa1586939",
            "0e7bceb615d644c73da0b4696e2359edcd887352462b2e479333babf9fe63d3c");

    @Value("${app.entorno:desarrollo}")
    private String entorno;
    @Value("${app.sms.consola:false}")
    private boolean smsConsola;
    @Value("${app.demo.simulacion:false}")
    private boolean simulacion;
    @Value("${app.cors.allowed-origins:}")
    private String cors;
    @Value("${app.jwt.secret:}")
    private String jwt;
    @Value("${app.admin.secret-code:}")
    private String codigoAdmin;
    @Value("${spring.datasource.password:}")
    private String claveBd;
    @Value("${spring.mail.password:}")
    private String claveCorreo;

    private final JdbcTemplate db;

    public ProduccionSegura(JdbcTemplate db) {
        this.db = db;
    }

    boolean esProduccion() {
        return "produccion".equalsIgnoreCase(entorno.trim());
    }

    @PostConstruct
    void revisar() {
        List<String> problemas = problemas();
        if (problemas.isEmpty()) {
            if (esProduccion()) log.info("[SEGURIDAD] Revisión de arranque de producción: todo en orden");
            return;
        }
        if (esProduccion()) {
            throw new IllegalStateException("[SEGURIDAD] Configuración insegura para producción:\n - "
                    + String.join("\n - ", problemas));
        }
        problemas.forEach(p -> log.warn("[SEGURIDAD] (en producción esto impediría arrancar) {}", p));
    }

    List<String> problemas() {
        List<String> p = new ArrayList<>();
        if (smsConsola) p.add("SMS_CONSOLA=true: el código SMS sale en la respuesta. En producción debe ser false.");
        if (simulacion) p.add("DEMO_SIMULACION=true: los viajes se simulan. En producción debe ser false.");
        for (String o : cors.split(",")) {
            String origen = o.trim();
            if (origen.contains("*") || origen.startsWith("http://") && !origen.equals("http://localhost")) {
                p.add("CORS_ORIGINS incluye '" + origen + "': en producción solo orígenes https exactos (y los de la app).");
            }
        }
        // El largo mínimo del JWT_SECRET (32) ya lo exige JwtUtil al arrancar
        if (!codigoAdmin.isBlank() && codigoAdmin.length() < 16) {
            p.add("ADMIN_SECRET_CODE es corto: déjalo vacío (registro de admin cerrado) o usa 16+ caracteres aleatorios.");
        }
        revisarFiltrado(p, "JWT_SECRET", jwt);
        revisarFiltrado(p, "ADMIN_SECRET_CODE", codigoAdmin);
        revisarFiltrado(p, "DB_PASSWORD", claveBd);
        revisarFiltrado(p, "MAIL_PASSWORD", claveCorreo);
        return p;
    }

    private static void revisarFiltrado(List<String> p, String nombre, String valor) {
        if (valor != null && !valor.isBlank() && FILTRADOS.contains(sha256(valor.trim()))) {
            p.add(nombre + " es una clave que quedó publicada en el historial de git: cámbiala ya.");
        }
    }

    /** Las cuentas demo tienen la clave 1357 publicada en el repo: en producción no deben existir. */
    @EventListener(ApplicationReadyEvent.class)
    void cuentasDemo() {
        try {
            Integer n = db.queryForObject("""
                    SELECT count(*) FROM usuarios
                    WHERE telefono IN ('+573000000001', '+573000000002', '+573000000003') AND estado = 'activo'
                    """, Integer.class);
            if (n != null && n > 0 && esProduccion()) {
                log.error("[SEGURIDAD] Hay {} cuentas demo activas (clave 1357 pública). Bloquéalas o bórralas.", n);
            }
        } catch (Exception e) {
            log.debug("No se pudo revisar las cuentas demo: {}", e.getMessage());
        }
    }

    static String sha256(String texto) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
