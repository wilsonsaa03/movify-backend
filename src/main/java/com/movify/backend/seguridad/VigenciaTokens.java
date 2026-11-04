package com.movify.backend.seguridad;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * ¿Sigue sirviendo este JWT de acceso? No, si la cuenta está bloqueada o eliminada, o si el token se
 * firmó antes de usuarios.tokens_validos_desde (cambio de rol, bloqueo, clave restablecida; V18).
 *
 * Para no ir a la base en cada petición se guarda el dato de cada usuario {@value #CACHE_MS} ms:
 * un bloqueo tarda como mucho eso en aplicar.
 */
@Component
public class VigenciaTokens {

    static final long CACHE_MS = 20_000;

    private record Estado(boolean bloqueado, long validosDesde, long leidoEn) {
    }

    private final JdbcTemplate db;
    private final Map<Long, Estado> cache = new ConcurrentHashMap<>();

    public VigenciaTokens(JdbcTemplate db) {
        this.db = db;
    }

    public boolean vigente(UsuarioAutenticado u) {
        long ahora = System.currentTimeMillis();
        Estado e = cache.get(u.id());
        if (e == null || ahora - e.leidoEn() > CACHE_MS) {
            e = leer(u.id(), ahora);
            if (cache.size() > 50_000) cache.clear();
            cache.put(u.id(), e);
        }
        return !e.bloqueado() && u.emitidoEn() >= e.validosDesde();
    }

    /** Se llama al revocar desde el código, para que aplique ya y no al vencer la caché. */
    public void olvidar(Long usuarioId) {
        cache.remove(usuarioId);
    }

    private Estado leer(Long id, long ahora) {
        List<Map<String, Object>> f = db.queryForList("""
                SELECT estado IN ('bloqueado', 'eliminado') AS bloqueado,
                       extract(epoch FROM date_trunc('second', tokens_validos_desde))::bigint AS desde
                FROM usuarios WHERE id = ?
                """, id);
        if (f.isEmpty()) return new Estado(true, Long.MAX_VALUE, ahora);
        return new Estado(Boolean.TRUE.equals(f.get(0).get("bloqueado")), ((Number) f.get(0).get("desde")).longValue(), ahora);
    }
}
