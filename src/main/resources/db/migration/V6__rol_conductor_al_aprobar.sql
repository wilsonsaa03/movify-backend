-- =====================================================================
--  MoviFY · V6 — El rol "conductor" se gana cuando el admin aprueba
--
--  Antes: toda cuenta de la app nacía como "cliente" y, al crear el perfil de conductor
--  (paso de identidad), un trigger le daba también el rol "conductor". Un aspirante en
--  revisión quedaba en usuario_roles como cliente Y conductor.
--
--  Ahora:
--   - Quien se registra como conductor no recibe ningún rol: es un ASPIRANTE
--     (usuarios.registrado_como = 'conductor' y su perfil en conductores en estado 'pendiente').
--   - El rol "conductor" se asigna solo cuando el admin lo aprueba (conductores.estado → 'aprobado').
--   - El aspirante puede entrar a la app (fn_validar_acceso) para terminar su registro y ver
--     cómo va la revisión, pero sin permisos: no recibe viajes ni pide servicios.
-- =====================================================================

-- ───────────── Con qué intención se registró la persona ─────────────
ALTER TABLE usuarios ADD COLUMN registrado_como VARCHAR(10) NOT NULL DEFAULT 'cliente'
    CHECK (registrado_como IN ('cliente', 'conductor'));

COMMENT ON COLUMN usuarios.registrado_como IS
    'Lo que eligió al crear la cuenta en la app. "conductor" sin rol conductor = aspirante en registro o revisión.';

-- Quien ya tiene perfil de conductor se registró para manejar
UPDATE usuarios u SET registrado_como = 'conductor'
 WHERE EXISTS (SELECT 1 FROM conductores c WHERE c.usuario_id = u.id);

-- ───────────── El rol se asigna al aprobar, no al crear el perfil ─────────────
DROP TRIGGER IF EXISTS trg_conductores_asignar_rol ON conductores;

CREATE OR REPLACE FUNCTION fn_conductores_asignar_rol()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.estado = 'aprobado' AND OLD.estado IS DISTINCT FROM 'aprobado' THEN
        INSERT INTO usuario_roles (usuario_id, rol) VALUES (NEW.usuario_id, 'conductor')
        ON CONFLICT (usuario_id, rol) DO NOTHING;
    END IF;
    RETURN NULL;
END $$;

CREATE TRIGGER trg_conductores_asignar_rol
    AFTER UPDATE OF estado ON conductores FOR EACH ROW EXECUTE FUNCTION fn_conductores_asignar_rol();

-- Aspirantes que nunca fueron aprobados: se les quita el rol que les dio el trigger anterior
-- (queda en auditoria_cambios por trg_auditoria_usuario_roles).
DELETE FROM usuario_roles ur
 USING conductores c
 WHERE ur.usuario_id = c.usuario_id
   AND ur.rol = 'conductor'
   AND c.estado <> 'aprobado'
   AND NOT EXISTS (SELECT 1 FROM historial_estados h
                    WHERE h.ambito = 'conductor' AND h.registro_id = c.usuario_id AND h.estado_nuevo = 'aprobado');

-- ───────────── El aspirante (sin roles) puede entrar a terminar su registro ─────────────
CREATE OR REPLACE FUNCTION fn_validar_acceso(p_usuario_id BIGINT)
RETURNS VARCHAR LANGUAGE sql STABLE AS $$
    SELECT CASE
             WHEN u.id IS NULL                                  THEN 'usuario_inexistente'
             WHEN u.estado = 'eliminado'                        THEN 'cuenta_eliminada'
             WHEN u.estado = 'bloqueado'                        THEN 'cuenta_bloqueada'
             WHEN u.acceso_bloqueado_hasta > now()              THEN 'acceso_bloqueado_temporal'
             WHEN NOT EXISTS (SELECT 1 FROM usuario_roles ur JOIN roles r ON r.codigo = ur.rol AND r.activo
                               WHERE ur.usuario_id = u.id)
                  AND u.registrado_como <> 'conductor'
                  AND NOT EXISTS (SELECT 1 FROM conductores c WHERE c.usuario_id = u.id)
                                                                THEN 'sin_permiso'
             ELSE 'exitoso'
           END
      FROM (SELECT p_usuario_id AS id_buscado) b
      LEFT JOIN usuarios u ON u.id = b.id_buscado;
$$;
