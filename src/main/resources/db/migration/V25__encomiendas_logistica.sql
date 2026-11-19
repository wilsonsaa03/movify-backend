-- =====================================================================
-- V25 · Encomiendas con logística (MVP)
--   1. Número de guía y token de rastreo público por encomienda.
--   2. Varios bultos por encomienda, con medidas y peso volumétrico.
--   3. La cotización con su desglose queda guardada (trazabilidad del precio).
--   4. Novedades de recogida y entrega (destinatario ausente, dirección incorrecta…),
--      que no se pueden editar después de registradas.
--   5. Parámetros del cotizador en configuracion_sistema (los edita el admin).
-- Todo es aditivo: no se borra ni se cambia ninguna columna existente.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1 y 3. Guía, token, totales de bultos y cotización
-- ---------------------------------------------------------------------
ALTER TABLE servicio_encomienda
    -- "MVF" + 10 caracteres Crockford base32 (sin I, L, O, U para que no se confundan al dictarla)
    ADD COLUMN numero_guia          VARCHAR(13),
    -- Token aleatorio de 256 bits (base64url) para el rastreo sin cuenta
    ADD COLUMN token_publico        VARCHAR(43),
    ADD COLUMN bultos               SMALLINT      NOT NULL DEFAULT 1 CHECK (bultos BETWEEN 1 AND 50),
    ADD COLUMN peso_volumetrico_kg  NUMERIC(7,2)  CHECK (peso_volumetrico_kg >= 0),
    ADD COLUMN peso_facturable_kg   NUMERIC(7,2)  CHECK (peso_facturable_kg >= 0),
    -- Copia del desglose con el que se cobró (si el admin cambia los parámetros, la encomienda conserva el suyo)
    ADD COLUMN cotizacion           JSONB,
    ADD COLUMN instrucciones_entrega VARCHAR(300),
    ADD CONSTRAINT ck_encomienda_guia CHECK (numero_guia IS NULL OR numero_guia ~ '^MVF[0-9A-HJKMNP-TV-Z]{10}$'),
    ADD CONSTRAINT ck_encomienda_token CHECK (token_publico IS NULL OR token_publico ~ '^[A-Za-z0-9_-]{43}$');

CREATE UNIQUE INDEX ux_encomienda_guia  ON servicio_encomienda (numero_guia)   WHERE numero_guia IS NOT NULL;
CREATE UNIQUE INDEX ux_encomienda_token ON servicio_encomienda (token_publico) WHERE token_publico IS NOT NULL;

-- Las encomiendas anteriores reciben guía y token la primera vez que se consultan (el backend usa SecureRandom).

-- ---------------------------------------------------------------------
-- 2. Bultos
-- ---------------------------------------------------------------------
CREATE TABLE encomienda_paquetes (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    servicio_id          BIGINT        NOT NULL REFERENCES servicio_encomienda(servicio_id) ON DELETE CASCADE,
    numero               SMALLINT      NOT NULL CHECK (numero BETWEEN 1 AND 50),
    tipo                 VARCHAR(10)   NOT NULL CHECK (tipo IN ('sobre', 'pequeno', 'mediano', 'grande')),
    descripcion          VARCHAR(120),
    peso_kg              NUMERIC(6,2)  NOT NULL CHECK (peso_kg > 0 AND peso_kg <= 100),
    largo_cm             NUMERIC(5,1)  CHECK (largo_cm > 0 AND largo_cm <= 300),
    ancho_cm             NUMERIC(5,1)  CHECK (ancho_cm > 0 AND ancho_cm <= 300),
    alto_cm              NUMERIC(5,1)  CHECK (alto_cm  > 0 AND alto_cm  <= 300),
    peso_volumetrico_kg  NUMERIC(7,2)  CHECK (peso_volumetrico_kg >= 0),
    valor_declarado      NUMERIC(12,2) CHECK (valor_declarado >= 0),
    fragil               BOOLEAN       NOT NULL DEFAULT false,
    creado_en            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ux_encomienda_paquete_numero UNIQUE (servicio_id, numero),
    -- Las tres medidas van juntas o ninguna
    CONSTRAINT ck_encomienda_paquete_medidas CHECK (
        (largo_cm IS NULL AND ancho_cm IS NULL AND alto_cm IS NULL) OR
        (largo_cm IS NOT NULL AND ancho_cm IS NOT NULL AND alto_cm IS NOT NULL))
);

-- Las encomiendas que ya existían quedan con un bulto (el mismo paquete que tenían)
INSERT INTO encomienda_paquetes (servicio_id, numero, tipo, descripcion, peso_kg, valor_declarado, fragil)
SELECT servicio_id, 1, tamano, left(descripcion_paquete, 120), peso_kg, valor_declarado, fragil
FROM servicio_encomienda;

UPDATE servicio_encomienda SET peso_facturable_kg = peso_kg WHERE peso_facturable_kg IS NULL;

-- ---------------------------------------------------------------------
-- 4. Novedades de recogida y entrega
-- ---------------------------------------------------------------------
CREATE TABLE encomienda_novedades (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    servicio_id     BIGINT           NOT NULL REFERENCES servicio_encomienda(servicio_id) ON DELETE CASCADE,
    tipo            VARCHAR(30)      NOT NULL CHECK (tipo IN ('destinatario_ausente', 'remitente_ausente',
                                                              'direccion_incorrecta', 'sin_acceso', 'rechazado',
                                                              'paquete_danado', 'retraso', 'otro')),
    etapa           VARCHAR(10)      NOT NULL CHECK (etapa IN ('recogida', 'entrega')),
    nota            VARCHAR(300),
    lat             DOUBLE PRECISION CHECK (lat BETWEEN -90  AND 90),
    lng             DOUBLE PRECISION CHECK (lng BETWEEN -180 AND 180),
    reportado_por   BIGINT           NOT NULL REFERENCES usuarios(id),
    actor_rol       VARCHAR(20)      NOT NULL CHECK (actor_rol IN ('conductor', 'soporte', 'admin')),
    creado_en       TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CHECK ((lat IS NULL) = (lng IS NULL)),
    -- "Otro" exige explicar qué pasó
    CHECK (tipo <> 'otro' OR length(trim(COALESCE(nota, ''))) >= 5)
);
CREATE INDEX ix_encomienda_novedades ON encomienda_novedades (servicio_id, creado_en);

-- Trazabilidad: una novedad registrada no se edita (sí se borra en cascada si se purga el servicio)
CREATE OR REPLACE FUNCTION fn_novedades_inmutables()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Las novedades de una encomienda no se modifican: registra una nueva'
          USING ERRCODE = 'check_violation';
END $$;
CREATE TRIGGER trg_novedades_inmutables
    BEFORE UPDATE ON encomienda_novedades FOR EACH ROW EXECUTE FUNCTION fn_novedades_inmutables();

-- ---------------------------------------------------------------------
-- 5. Parámetros del cotizador (VALORES INICIALES DE PRUEBA: definirlos antes de producción)
-- ---------------------------------------------------------------------
INSERT INTO configuracion_sistema (clave, valor, descripcion) VALUES
    ('encomienda_factor_volumetrico', '5000',
        'cm³ por kg para el peso volumétrico (largo × ancho × alto / factor). Estándar de mensajería: 5000'),
    ('encomienda_kg_incluidos',       '5',
        'Kilos facturables incluidos en la tarifa de la ruta. PRUEBA: definir antes de producción'),
    ('encomienda_valor_kg_adicional', '600',
        '$ por cada kilo facturable por encima de los incluidos. PRUEBA: definir antes de producción'),
    ('encomienda_valor_bulto_adicional', '1000',
        '$ por cada bulto adicional al primero. PRUEBA: definir antes de producción'),
    ('encomienda_recargo_fragil',     '1500',
        '$ por manejo especial de cada bulto frágil. PRUEBA: definir antes de producción'),
    ('encomienda_proteccion_pct',     '0',
        '% del valor declarado que se cobra como protección. 0 = no se cobra (requiere política legal aprobada)'),
    ('encomienda_max_valor_declarado', '2000000',
        'Valor declarado máximo por encomienda ($). PRUEBA: definir antes de producción'),
    ('encomienda_max_bultos',         '10',
        'Bultos máximos en una misma encomienda'),
    ('encomienda_moto_max_kg',        '15',
        'Peso facturable máximo que lleva una moto (kg). PRUEBA: validar con la operación'),
    ('encomienda_taxi_max_kg',        '30',
        'Peso facturable máximo que lleva un taxi (kg). PRUEBA: validar con la operación'),
    ('encomienda_moto_max_lado_cm',   '50',
        'Lado más largo de un bulto que cabe en la moto (cm). PRUEBA: validar con la operación'),
    ('encomienda_taxi_max_lado_cm',   '100',
        'Lado más largo de un bulto que cabe en el baúl del taxi (cm). PRUEBA: validar con la operación'),
    ('encomienda_moto_tamano_max',    '2',
        'Tamaño máximo de bulto en moto: 1 sobre, 2 pequeño, 3 mediano, 4 grande'),
    ('encomienda_taxi_tamano_max',    '3',
        'Tamaño máximo de bulto en taxi: 1 sobre, 2 pequeño, 3 mediano, 4 grande'),
    ('encomienda_dias_rastreo_publico', '30',
        'Días que el enlace público de rastreo sigue abierto después de entregar o cancelar')
ON CONFLICT (clave) DO NOTHING;
