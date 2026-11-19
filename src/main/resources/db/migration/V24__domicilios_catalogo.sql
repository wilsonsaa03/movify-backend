-- =====================================================================
-- V24: domicilios con catálogo (estilo Rappi), además del pedido libre que ya existía.
--
--  - comercios_domicilio: restaurantes, droguerías, mercados, licoreras y tiendas aliadas (los carga el admin).
--  - productos_domicilio → grupos_opciones_domicilio → opciones_domicilio: tamaños, adiciones, "sin cebolla"...
--    Cada grupo dice si es obligatorio y cuántas opciones se pueden marcar (mínimo / máximo).
--  - cupones_domicilio (simulados: el descuento lo asume MoviFY) y cupones_usados.
--  - El pedido sigue siendo un servicio de domicilio "con compra": el domiciliario paga en el comercio y el
--    cliente paga al terminar (efectivo o digital). Se guarda una COPIA de cada línea (nombre, precio y opciones
--    al momento de pedir): si el admin cambia el catálogo después, el pedido no cambia.
--  - Licores y productos +18: el cliente confirma que es mayor de edad y el domiciliario pide la cédula al entregar.
-- =====================================================================

CREATE TABLE comercios_domicilio (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre              VARCHAR(120)     NOT NULL CHECK (length(trim(nombre)) >= 2),
    categoria           VARCHAR(12)      NOT NULL CHECK (categoria IN ('comida', 'farmacia', 'mercado', 'licores', 'tienda')),
    descripcion         VARCHAR(300),
    foto_url            VARCHAR(300),
    direccion           VARCHAR(200)     NOT NULL,
    barrio              VARCHAR(80),
    lat                 DOUBLE PRECISION NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lng                 DOUBLE PRECISION NOT NULL CHECK (lng BETWEEN -180 AND 180),
    telefono            VARCHAR(16)      CHECK (telefono IS NULL OR telefono ~ '^\+57(3[0-9]{9}|60[0-9]{8})$'),
    -- Horario diario (hora de Buenaventura). Si cierra < abre, cierra después de medianoche (ej. 18:00 a 02:00).
    abre                TIME             NOT NULL DEFAULT '08:00',
    cierra              TIME             NOT NULL DEFAULT '20:00',
    pedido_minimo       NUMERIC(12,2)    NOT NULL DEFAULT 0 CHECK (pedido_minimo >= 0),
    radio_km            NUMERIC(5,2)     NOT NULL DEFAULT 8 CHECK (radio_km > 0 AND radio_km <= 30),
    minutos_preparacion SMALLINT         NOT NULL DEFAULT 15 CHECK (minutos_preparacion BETWEEN 0 AND 180),
    activo              BOOLEAN          NOT NULL DEFAULT true,
    orden               SMALLINT         NOT NULL DEFAULT 0,
    creado_en           TIMESTAMPTZ      NOT NULL DEFAULT now(),
    actualizado_en      TIMESTAMPTZ      NOT NULL DEFAULT now()
);
CREATE INDEX ix_comercios_domicilio_activos ON comercios_domicilio (categoria, orden) WHERE activo;
CREATE TRIGGER trg_comercios_domicilio_actualizado
    BEFORE UPDATE ON comercios_domicilio FOR EACH ROW EXECUTE FUNCTION fn_set_actualizado_en();
COMMENT ON TABLE comercios_domicilio IS 'Comercios aliados con catálogo para domicilios (los carga el admin).';

CREATE TABLE productos_domicilio (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    comercio_id     BIGINT        NOT NULL REFERENCES comercios_domicilio(id) ON DELETE CASCADE,
    seccion         VARCHAR(60)   NOT NULL DEFAULT 'Productos',   -- categoría dentro del comercio ("Hamburguesas")
    nombre          VARCHAR(120)  NOT NULL CHECK (length(trim(nombre)) >= 2),
    descripcion     VARCHAR(300),
    foto_url        VARCHAR(300),
    precio          NUMERIC(12,2) NOT NULL CHECK (precio >= 0),
    disponible      BOOLEAN       NOT NULL DEFAULT true,
    solo_mayores    BOOLEAN       NOT NULL DEFAULT false,         -- licor, cigarrillos...: +18
    orden           SMALLINT      NOT NULL DEFAULT 0,
    creado_en       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    actualizado_en  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_productos_domicilio_comercio ON productos_domicilio (comercio_id, seccion, orden);
CREATE TRIGGER trg_productos_domicilio_actualizado
    BEFORE UPDATE ON productos_domicilio FOR EACH ROW EXECUTE FUNCTION fn_set_actualizado_en();

CREATE TABLE grupos_opciones_domicilio (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    producto_id     BIGINT       NOT NULL REFERENCES productos_domicilio(id) ON DELETE CASCADE,
    nombre          VARCHAR(60)  NOT NULL,                          -- "Tamaño", "Adiciones", "Quitar"
    minimo          SMALLINT     NOT NULL DEFAULT 0 CHECK (minimo BETWEEN 0 AND 20),
    maximo          SMALLINT     NOT NULL DEFAULT 1 CHECK (maximo BETWEEN 1 AND 20),
    orden           SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT ck_grupos_minimo_maximo CHECK (minimo <= maximo)
);
CREATE INDEX ix_grupos_opciones_producto ON grupos_opciones_domicilio (producto_id, orden);
COMMENT ON COLUMN grupos_opciones_domicilio.minimo IS 'minimo >= 1 = obligatorio (ej. "Elige el tamaño": minimo 1, maximo 1).';

CREATE TABLE opciones_domicilio (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    grupo_id        BIGINT        NOT NULL REFERENCES grupos_opciones_domicilio(id) ON DELETE CASCADE,
    nombre          VARCHAR(60)   NOT NULL,
    precio_extra    NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (precio_extra >= 0),
    disponible      BOOLEAN       NOT NULL DEFAULT true,
    orden           SMALLINT      NOT NULL DEFAULT 0
);
CREATE INDEX ix_opciones_domicilio_grupo ON opciones_domicilio (grupo_id, orden);

-- ─── Cupones (simulados: el descuento lo asume MoviFY; no hay cobro real) ───
CREATE TABLE cupones_domicilio (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo              VARCHAR(30)   NOT NULL CHECK (codigo = upper(codigo) AND codigo ~ '^[A-Z0-9]{3,30}$'),
    descripcion         VARCHAR(200)  NOT NULL,
    tipo                VARCHAR(10)   NOT NULL CHECK (tipo IN ('porcentaje', 'valor', 'envio')),
    valor               NUMERIC(12,2) NOT NULL CHECK (valor > 0),
    tope_descuento      NUMERIC(12,2) CHECK (tope_descuento IS NULL OR tope_descuento > 0),
    minimo_compra       NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (minimo_compra >= 0),
    comercio_id         BIGINT        REFERENCES comercios_domicilio(id) ON DELETE CASCADE,   -- null = cualquier comercio
    solo_primer_pedido  BOOLEAN       NOT NULL DEFAULT false,
    usos_por_cliente    SMALLINT      NOT NULL DEFAULT 1 CHECK (usos_por_cliente >= 1),
    usos_totales        INTEGER       CHECK (usos_totales IS NULL OR usos_totales >= 1),
    vence_en            TIMESTAMPTZ,
    activo              BOOLEAN       NOT NULL DEFAULT true,
    creado_en           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ux_cupones_domicilio_codigo UNIQUE (codigo),
    CONSTRAINT ck_cupones_porcentaje CHECK (tipo <> 'porcentaje' OR valor <= 100)
);
COMMENT ON TABLE cupones_domicilio IS
    'Cupones de domicilio (simulados). porcentaje/valor descuentan de los productos; envio descuenta del domicilio.';

CREATE TABLE cupones_usados (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cupon_id        BIGINT        NOT NULL REFERENCES cupones_domicilio(id) ON DELETE CASCADE,
    usuario_id      BIGINT        NOT NULL REFERENCES usuarios(id),
    servicio_id     BIGINT        NOT NULL REFERENCES servicios(id) ON DELETE CASCADE,
    descuento       NUMERIC(12,2) NOT NULL CHECK (descuento >= 0),
    creado_en       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ux_cupones_usados_servicio UNIQUE (servicio_id)
);
CREATE INDEX ix_cupones_usados_cupon ON cupones_usados (cupon_id, usuario_id);

-- ─── El domicilio con catálogo ───
ALTER TABLE servicio_domicilio
    ADD COLUMN comercio_id      BIGINT        REFERENCES comercios_domicilio(id) ON DELETE SET NULL,
    ADD COLUMN subtotal_catalogo NUMERIC(12,2) CHECK (subtotal_catalogo IS NULL OR subtotal_catalogo >= 0),
    ADD COLUMN descuento        NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (descuento >= 0),
    ADD COLUMN cupon_codigo     VARCHAR(30),
    ADD COLUMN solo_mayores     BOOLEAN       NOT NULL DEFAULT false;
COMMENT ON COLUMN servicio_domicilio.descuento IS 'Descuento del cupón (simulado): se resta de lo que paga el cliente.';
COMMENT ON COLUMN servicio_domicilio.solo_mayores IS 'Lleva licor u otro producto +18: el domiciliario pide la cédula al entregar.';

ALTER TABLE servicio_domicilio_items
    ADD COLUMN producto_id      BIGINT        REFERENCES productos_domicilio(id) ON DELETE SET NULL,
    ADD COLUMN precio_unitario  NUMERIC(12,2) CHECK (precio_unitario IS NULL OR precio_unitario >= 0),
    ADD COLUMN opciones         JSONB,
    ADD COLUMN total_linea      NUMERIC(12,2) CHECK (total_linea IS NULL OR total_linea >= 0);
COMMENT ON COLUMN servicio_domicilio_items.opciones IS 'Copia de las opciones elegidas: [{"grupo":"Tamaño","nombre":"Doble","precio_extra":5000}]';

-- Categoría del domicilio: también licores y tienda (V12 tenía comida, farmacia, mercado, diligencia)
DO $$
DECLARE c TEXT;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
              WHERE conrelid = 'servicio_domicilio'::regclass AND contype = 'c'
                AND pg_get_constraintdef(oid) LIKE '%categoria%'
    LOOP
        EXECUTE format('ALTER TABLE servicio_domicilio DROP CONSTRAINT %I', c);
    END LOOP;
END $$;
ALTER TABLE servicio_domicilio ADD CONSTRAINT ck_servicio_domicilio_categoria
    CHECK (categoria IS NULL OR categoria IN ('comida', 'farmacia', 'mercado', 'diligencia', 'licores', 'tienda'));

-- ─── Mayoría de edad (para licores) ───
ALTER TABLE usuarios ADD COLUMN mayor_edad_confirmado_en TIMESTAMPTZ;
COMMENT ON COLUMN usuarios.mayor_edad_confirmado_en IS
    'Cuándo confirmó que es mayor de 18 (para pedir licor). El domiciliario igual pide la cédula al entregar.';
