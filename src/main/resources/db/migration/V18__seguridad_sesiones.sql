-- ─────────────────────────────────────────────────────────────
-- V18 · Seguridad: tokens de acceso revocables
--
-- El JWT de acceso dura hasta 24 h. Antes, si el admin bloqueaba a alguien o le quitaba el rol de
-- admin, ese token seguía sirviendo hasta vencer. Ahora cada usuario tiene "tokens_validos_desde":
-- todo token emitido ANTES de esa hora se rechaza (JwtFiltro + VigenciaTokens, con caché corta).
--
-- Se mueve sola (triggers) cuando:
--   - cambia el estado de la cuenta (activo ↔ bloqueado, eliminado);
--   - se le agrega o quita un rol (admin, soporte, conductor aprobado...).
-- Y desde el código cuando se restablece la clave (SesionesServicio.revocarTodas).
-- La app móvil, al recibir 401, renueva con el refresh token y sigue con los permisos nuevos.
-- ─────────────────────────────────────────────────────────────

ALTER TABLE usuarios
    ADD COLUMN tokens_validos_desde TIMESTAMPTZ NOT NULL DEFAULT '2000-01-01 00:00:00+00';

COMMENT ON COLUMN usuarios.tokens_validos_desde IS
    'Los JWT de acceso emitidos antes de esta hora ya no sirven (bloqueo, cambio de rol, clave restablecida).';

CREATE OR REPLACE FUNCTION fn_usuarios_revocar_tokens_por_estado()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.estado IS DISTINCT FROM OLD.estado THEN
        NEW.tokens_validos_desde := now();
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_usuarios_revocar_tokens
    BEFORE UPDATE OF estado ON usuarios
    FOR EACH ROW EXECUTE FUNCTION fn_usuarios_revocar_tokens_por_estado();

CREATE OR REPLACE FUNCTION fn_usuario_roles_revocar_tokens()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE usuarios SET tokens_validos_desde = now()
     WHERE id = COALESCE(NEW.usuario_id, OLD.usuario_id);
    RETURN NULL;
END $$;

CREATE TRIGGER trg_usuario_roles_revocar_tokens
    AFTER INSERT OR DELETE ON usuario_roles
    FOR EACH ROW EXECUTE FUNCTION fn_usuario_roles_revocar_tokens();

-- Vigilancia: intentos fallidos por IP en las últimas horas (panel de seguridad del admin)
CREATE INDEX IF NOT EXISTS ix_accesos_fecha_ip ON registro_accesos (creado_en DESC, ip_origen)
    WHERE resultado <> 'exitoso';
