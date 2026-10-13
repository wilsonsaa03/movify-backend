package com.movify.backend.comun;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Le dice a la base de datos QUIÉN hace el cambio, como pide el DER v2.3:
 *   SELECT set_config('movify.usuario_id', id, true), set_config('movify.actor', rol, true);
 *
 * Con eso los triggers validan que ese actor pueda hacer la transición de estado
 * (transiciones_estado) y la auditoría queda con nombre propio.
 * El "true" hace que solo valga para la transacción actual: SIEMPRE llamar dentro de
 * un método @Transactional y antes del UPDATE.
 */
@Component
public class ActorBd {

    public static final String CLIENTE = "cliente";
    public static final String CONDUCTOR = "conductor";
    public static final String SOPORTE = "soporte";
    public static final String ADMIN = "admin";
    public static final String SISTEMA = "sistema";

    private final JdbcTemplate db;

    public ActorBd(JdbcTemplate db) {
        this.db = db;
    }

    public void declarar(Long usuarioId, String actor) {
        db.queryForList("SELECT set_config('movify.usuario_id', ?, true), set_config('movify.actor', ?, true)",
                usuarioId == null ? "" : String.valueOf(usuarioId), actor);
    }
}
