-- =====================================================================
--  MoviFY · V19 — Segundo factor del equipo y comunicados
--
--  1. desafios_acceso: el admin y soporte, después de escribir bien su contraseña (o entrar con Google),
--     reciben un código de 6 números por SMS (o por correo si no tienen celular). Sin ese código no hay
--     sesión: robar la contraseña ya no basta para tomar el panel. Se guarda un HMAC del código.
--  2. comunicados: avisos masivos del admin a clientes y/o conductores ("Hoy hay paro", "Nuevas
--     tarifas"). Cada persona lo recibe en sus notificaciones (tipo 'sistema') y como push.
--
--  OJO: el permiso nuevo va dentro del JWT; el admin debe volver a iniciar sesión para recibirlo.
-- =====================================================================

CREATE TABLE desafios_acceso (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id      BIGINT       NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    codigo_hash     VARCHAR(64)  NOT NULL,
    canal           VARCHAR(10)  NOT NULL CHECK (canal IN ('sms', 'correo')),
    metodo          VARCHAR(10)  NOT NULL CHECK (metodo IN ('password', 'google')),
    intentos        SMALLINT     NOT NULL DEFAULT 0,
    expira_en       TIMESTAMPTZ  NOT NULL,
    usado_en        TIMESTAMPTZ,
    ip_origen       VARCHAR(45),
    creado_en       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_desafios_usuario ON desafios_acceso (usuario_id, creado_en DESC);

COMMENT ON TABLE desafios_acceso IS
    'Segundo factor del equipo (admin/soporte): código de un solo uso por SMS o correo (solo su HMAC).';

INSERT INTO modulos (codigo, nombre, icono, ruta_frontend, modulo_padre, orden) VALUES
    ('comunicados', 'Comunicados', 'megaphone', '/admin', NULL, 35);

INSERT INTO permisos (codigo, modulo, accion, descripcion, es_sensible) VALUES
    ('comunicados.enviar', 'comunicados', 'enviar', 'Enviar avisos masivos a clientes y conductores', true);

INSERT INTO rol_permisos (rol, permiso) VALUES
    ('admin', 'comunicados.enviar');

CREATE TABLE comunicados (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    titulo          VARCHAR(100) NOT NULL CHECK (length(trim(titulo)) >= 3),
    mensaje         VARCHAR(300) NOT NULL CHECK (length(trim(mensaje)) >= 5),
    audiencia       VARCHAR(12)  NOT NULL CHECK (audiencia IN ('todos', 'clientes', 'conductores')),
    destinatarios   INTEGER      NOT NULL DEFAULT 0,
    creado_por      BIGINT       REFERENCES usuarios(id) ON DELETE SET NULL,
    creado_en       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_comunicados_fecha ON comunicados (creado_en DESC);
