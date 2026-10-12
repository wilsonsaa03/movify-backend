package com.movify.backend.autenticacion;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.movify.backend.configuracion.ContextoPeticion;

/**
 * Bitácora registro_accesos. Va en su PROPIA transacción (REQUIRES_NEW): así un intento
 * fallido queda guardado aunque después se responda error, y el trigger del DER puede
 * bloquear temporalmente la cuenta tras N intentos (max_intentos_fallidos).
 */
@Component
public class RegistroAccesos {

    private final JdbcTemplate db;

    public RegistroAccesos(JdbcTemplate db) {
        this.db = db;
    }

    /**
     * @param metodo    sms | google | password | refresh
     * @param resultado exitoso | codigo_invalido | password_invalido | usuario_inexistente | cuenta_bloqueada ...
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrar(Long usuarioId, String identificador, String metodo, String resultado, ContextoPeticion ctx) {
        insertar(usuarioId, identificador, metodo, resultado, ctx);
    }

    /**
     * Acceso exitoso: va en la MISMA transacción del login, porque el usuario puede ser
     * nuevo (registro, Google) y todavía no estar confirmado en la base.
     */
    @Transactional
    public void registrarExito(Long usuarioId, String identificador, String metodo, ContextoPeticion ctx) {
        insertar(usuarioId, identificador, metodo, "exitoso", ctx);
    }

    private void insertar(Long usuarioId, String identificador, String metodo, String resultado, ContextoPeticion ctx) {
        db.update("""
                INSERT INTO registro_accesos (usuario_id, identificador, metodo, resultado, ip_origen, user_agent, plataforma)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, usuarioId, identificador == null ? "?" : identificador.length() > 120 ? identificador.substring(0, 120)
                        : identificador,
                metodo, resultado, ctx.ip(), ctx.userAgent(), ctx.plataforma());
    }
}
