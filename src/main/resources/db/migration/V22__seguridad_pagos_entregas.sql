-- =====================================================================
-- V22: llamada enmascarada, selfie del conductor, pagos con Nequi/Daviplata (sandbox) y foto de entrega.
--
--  - llamadas_servicio: cada llamada entre cliente y conductor pasa por un número de MoviFY (Twilio);
--    ninguno ve el celular del otro. Aquí solo queda quién llamó a quién y cuándo (sin números).
--  - selfies_conductor: antes de conectarse el conductor se toma una selfie (vale unas horas). Soporte la
--    compara con la foto de perfil; si no es la misma persona la rechaza y el conductor queda desconectado.
--  - pagos: cobro digital al terminar el servicio (Nequi o Daviplata, por Wompi en modo sandbox o con el
--    simulador local). Si el pago falla, el cliente paga en efectivo como siempre.
--  - La foto de entrega usa la tabla evidencias_servicio que ya existe (tipo 'entrega').
-- =====================================================================

-- ─── Métodos de pago del servicio ───
DO $$
DECLARE c TEXT;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
              WHERE conrelid = 'servicios'::regclass AND contype = 'c'
                AND pg_get_constraintdef(oid) LIKE '%metodo_pago%'
    LOOP
        EXECUTE format('ALTER TABLE servicios DROP CONSTRAINT %I', c);
    END LOOP;
END $$;
ALTER TABLE servicios ADD CONSTRAINT ck_servicios_metodo_pago
    CHECK (metodo_pago IN ('efectivo', 'nequi', 'daviplata'));
COMMENT ON COLUMN servicios.metodo_pago IS 'efectivo | nequi | daviplata. Si el pago digital no se aprueba, se paga en efectivo.';

-- ─── Pagos digitales ───
CREATE TABLE pagos (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    servicio_id      BIGINT        NOT NULL REFERENCES servicios(id) ON DELETE CASCADE,
    cliente_id       BIGINT        NOT NULL REFERENCES usuarios(id),
    metodo           VARCHAR(15)   NOT NULL CHECK (metodo IN ('nequi', 'daviplata')),
    proveedor        VARCHAR(15)   NOT NULL CHECK (proveedor IN ('simulado', 'wompi')),
    monto            NUMERIC(12,2) NOT NULL CHECK (monto > 0),
    referencia       VARCHAR(60)   NOT NULL,
    transaccion_id   VARCHAR(80),
    estado           VARCHAR(15)   NOT NULL DEFAULT 'pendiente'
                     CHECK (estado IN ('pendiente', 'aprobado', 'rechazado', 'error', 'cancelado')),
    requiere_otp     BOOLEAN       NOT NULL DEFAULT false,
    intentos_otp     SMALLINT      NOT NULL DEFAULT 0 CHECK (intentos_otp BETWEEN 0 AND 10),
    detalle          VARCHAR(200),
    -- Solo los últimos 4 dígitos del celular o del documento (para mostrarlo en el recibo)
    cuenta_mascara   VARCHAR(20),
    -- Datos que el proveedor necesita para seguir (p. ej. URLs y token del OTP de Daviplata). Nunca se devuelven.
    datos_proveedor  JSONB,
    creado_en        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    actualizado_en   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ux_pagos_referencia UNIQUE (referencia)
);
-- Un servicio no puede tener dos cobros vivos ni dos aprobados
CREATE UNIQUE INDEX ux_pagos_un_vivo_por_servicio ON pagos (servicio_id) WHERE estado IN ('pendiente', 'aprobado');
CREATE INDEX ix_pagos_servicio ON pagos (servicio_id, creado_en DESC);
CREATE INDEX ix_pagos_pendientes ON pagos (actualizado_en) WHERE estado = 'pendiente';
CREATE TRIGGER trg_pagos_actualizado
    BEFORE UPDATE ON pagos FOR EACH ROW EXECUTE FUNCTION fn_set_actualizado_en();
COMMENT ON TABLE pagos IS 'Cobros digitales (Nequi/Daviplata por Wompi sandbox o simulador). Si fallan, el servicio se paga en efectivo.';

-- ─── Selfie del conductor ───
CREATE TABLE selfies_conductor (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conductor_id     BIGINT        NOT NULL REFERENCES conductores(usuario_id) ON DELETE CASCADE,
    ruta_archivo     VARCHAR(300)  NOT NULL,
    mime_type        VARCHAR(60)   NOT NULL CHECK (mime_type IN ('image/jpeg', 'image/png', 'image/webp')),
    estado           VARCHAR(15)   NOT NULL DEFAULT 'pendiente' CHECK (estado IN ('pendiente', 'aprobada', 'rechazada')),
    motivo_rechazo   VARCHAR(200),
    revisado_por     BIGINT        REFERENCES usuarios(id),
    revisado_en      TIMESTAMPTZ,
    creado_en        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_selfies_rechazo CHECK (estado <> 'rechazada' OR motivo_rechazo IS NOT NULL)
);
CREATE INDEX ix_selfies_conductor ON selfies_conductor (conductor_id, creado_en DESC);
CREATE INDEX ix_selfies_pendientes ON selfies_conductor (creado_en) WHERE estado = 'pendiente';
COMMENT ON TABLE selfies_conductor IS 'Selfie antes de conectarse: confirma que quien maneja es el titular de la cuenta.';

-- ─── Llamadas con número protegido ───
CREATE TABLE llamadas_servicio (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    servicio_id      BIGINT        NOT NULL REFERENCES servicios(id) ON DELETE CASCADE,
    llama_id         BIGINT        NOT NULL REFERENCES usuarios(id),
    recibe_id        BIGINT        NOT NULL REFERENCES usuarios(id),
    modo             VARCHAR(10)   NOT NULL CHECK (modo IN ('twilio', 'simulada')),
    sid_proveedor    VARCHAR(64),
    creado_en        TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_llamadas_servicio ON llamadas_servicio (servicio_id, llama_id, creado_en DESC);
COMMENT ON TABLE llamadas_servicio IS 'Llamadas por número de MoviFY: ninguno ve el celular del otro. No se guardan los números.';

-- ─── Parámetros (se cambian desde el panel) ───
INSERT INTO configuracion_sistema (clave, valor, descripcion) VALUES
    ('horas_vigencia_selfie',     '12', 'Horas que vale la selfie del conductor antes de pedirle otra para conectarse'),
    ('max_llamadas_servicio',     '6',  'Llamadas con número protegido que cada persona puede hacer en un mismo servicio'),
    ('segundos_entre_llamadas',   '20', 'Segundos mínimos entre dos llamadas de la misma persona en un servicio')
ON CONFLICT (clave) DO NOTHING;
