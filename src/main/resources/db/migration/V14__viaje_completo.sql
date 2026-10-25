-- =====================================================================
--  MoviFY · V14 — Pedir moto y taxi al nivel de Uber / DiDi / inDrive
--
--  1. Preferencias del viaje: casco para el pasajero (moto), equipaje y
--     mascota (taxi). El casco filtra a quién se le ofrece el viaje.
--  2. Pedir para otra persona: nombre y celular de quien viaja.
--  3. Una parada en el camino y cambio de destino durante el viaje.
--  4. Subir la tarifa cuando nadie acepta (valor_sugerido = lo que cotizó
--     MoviFY; valor_estimado puede subir hasta un tope).
--  5. Enlace para seguir el viaje en vivo (viajes_compartidos).
--  6. Alertas de desvío o parada larga y la respuesta del pasajero.
--  7. Conductores favoritos (se les ofrece primero, con más radio).
--  8. Viajes programados (la búsqueda arranca unos minutos antes).
--  9. Parámetros nuevos: tiempo de espera del conductor, teléfono de la
--     central, tope de aumento de tarifa y ventanas de programación.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1–4. Columnas nuevas del servicio
-- ---------------------------------------------------------------------
ALTER TABLE servicios
    ADD COLUMN valor_sugerido       NUMERIC(12,2) CHECK (valor_sugerido >= 0),
    ADD COLUMN aumentos_tarifa      SMALLINT NOT NULL DEFAULT 0 CHECK (aumentos_tarifa BETWEEN 0 AND 10),
    ADD COLUMN pasajero_nombre      VARCHAR(80),
    ADD COLUMN pasajero_telefono    VARCHAR(16),
    ADD COLUMN necesita_casco       BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN lleva_equipaje       BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN lleva_mascota        BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN parada_direccion     VARCHAR(200),
    ADD COLUMN parada_lat           DOUBLE PRECISION CHECK (parada_lat BETWEEN -90 AND 90),
    ADD COLUMN parada_lng           DOUBLE PRECISION CHECK (parada_lng BETWEEN -180 AND 180),
    ADD COLUMN destino_cambiado_en  TIMESTAMPTZ,
    ADD CONSTRAINT ck_servicios_pasajero_completo
        CHECK ((pasajero_nombre IS NULL) = (pasajero_telefono IS NULL)),
    ADD CONSTRAINT ck_servicios_pasajero_telefono
        CHECK (pasajero_telefono IS NULL OR pasajero_telefono ~ '^\+573[0-9]{9}$'),
    ADD CONSTRAINT ck_servicios_pasajero_solo_transporte
        CHECK (pasajero_nombre IS NULL OR tipo_servicio = 'transporte'),
    ADD CONSTRAINT ck_servicios_casco_solo_moto
        CHECK (NOT necesita_casco OR tipo_vehiculo = 'moto'),
    ADD CONSTRAINT ck_servicios_parada_completa
        CHECK ((parada_lat IS NULL) = (parada_lng IS NULL) AND (parada_lat IS NULL) = (parada_direccion IS NULL)),
    ADD CONSTRAINT ck_servicios_parada_solo_transporte
        CHECK (parada_lat IS NULL OR tipo_servicio = 'transporte');

-- Los servicios que ya existían se cotizaron al valor que tienen
UPDATE servicios SET valor_sugerido = valor_estimado WHERE valor_sugerido IS NULL;

COMMENT ON COLUMN servicios.valor_sugerido IS 'Lo que cotizó MoviFY al pedir. valor_estimado puede ser mayor si el cliente subió la tarifa.';
COMMENT ON COLUMN servicios.pasajero_nombre IS 'Si el viaje es para otra persona: quién viaja (el conductor ve este nombre).';
COMMENT ON COLUMN servicios.pasajero_telefono IS 'Celular de quien viaja (+57...). Le llega un SMS con el conductor, la placa y el PIN.';
COMMENT ON COLUMN servicios.necesita_casco IS 'Moto: el pasajero no tiene casco. Solo se ofrece a motos con casco_pasajero.';
COMMENT ON COLUMN servicios.parada_direccion IS 'Una parada en el camino (el precio se calcula pasando por ella).';
COMMENT ON COLUMN servicios.destino_cambiado_en IS 'Última vez que el pasajero cambió el destino o la parada con el viaje andando.';

-- ---------------------------------------------------------------------
-- La vista de la API muestra lo nuevo (se agregan columnas al final)
-- ---------------------------------------------------------------------
CREATE OR REPLACE VIEW v_servicios_api AS
SELECT s.id,
       s.tipo_servicio,
       upper(s.tipo_servicio)                         AS tipo,
       s.tipo_vehiculo,
       s.cliente_id,
       s.cliente_id                                   AS usuario_id,
       trim(cli.nombres || ' ' || COALESCE(cli.apellidos, '')) AS usuario_nombre,
       cli.telefono                                   AS usuario_telefono,
       s.conductor_id,
       s.vehiculo_id,
       s.estado                                       AS estado_codigo,
       fn_estado_legado(s.tipo_servicio, s.estado)    AS estado,
       e.nombre                                       AS estado_nombre,
       e.color                                        AS estado_color,
       e.es_final                                     AS estado_final,
       COALESCE(dom.descripcion_pedido, enc.descripcion_paquete, s.notas_cliente, '') AS descripcion,
       s.origen_direccion                             AS origen,
       s.origen_barrio, s.origen_referencia,
       s.destino_direccion                            AS destino,
       s.destino_barrio, s.destino_referencia,
       s.origen_lat, s.origen_lng, s.destino_lat, s.destino_lng,
       s.distancia_km,
       s.duracion_estimada_min,
       s.valor_estimado,
       s.valor_final,
       COALESCE(s.valor_final, s.valor_estimado)      AS tarifa,
       s.metodo_pago,
       s.pasajeros,
       s.notas_cliente,
       s.solicitado_en                                AS fecha_solicitud,
       COALESCE(s.iniciado_en, s.asignado_en)         AS fecha_inicio,
       COALESCE(s.finalizado_en, s.cancelado_en)      AS fecha_fin,
       s.solicitado_en, s.expira_busqueda_en, s.asignado_en, s.llegada_origen_en,
       s.iniciado_en, s.finalizado_en, s.cancelado_en,
       s.motivo_cancelacion_id,
       mc.descripcion                                 AS motivo_cancelacion,
       s.detalle_cancelacion,
       CASE WHEN s.estado = 'expirado'              THEN 'sistema'
            WHEN s.cancelado_por IS NULL            THEN NULL
            WHEN s.cancelado_por = s.cliente_id     THEN 'cliente'
            WHEN s.cancelado_por = s.conductor_id   THEN 'conductor'
            ELSE 'personal' END                       AS cancelado_por,
       -- domicilio
       dom.establecimiento, dom.requiere_compra, dom.valor_productos_aprox, dom.valor_compra_real,
       -- encomienda
       enc.remitente_nombre                           AS nombre_remitente,
       enc.remitente_telefono                         AS telefono_remitente,
       enc.destinatario_nombre                        AS nombre_destinatario,
       enc.destinatario_telefono                      AS telefono_destinatario,
       enc.tamano, enc.peso_kg, enc.fragil, enc.valor_declarado, enc.entregado_a,
       -- V14: viaje
       COALESCE(s.valor_sugerido, s.valor_estimado)   AS valor_sugerido,
       s.pasajero_nombre, s.pasajero_telefono,
       s.necesita_casco, s.lleva_equipaje, s.lleva_mascota,
       s.parada_direccion                             AS parada,
       s.parada_lat, s.parada_lng,
       s.destino_cambiado_en
  FROM servicios s
  JOIN estados e        ON e.ambito = 'servicio' AND e.codigo = s.estado
  JOIN usuarios cli     ON cli.id = s.cliente_id
  LEFT JOIN servicio_domicilio  dom ON dom.servicio_id = s.id
  LEFT JOIN servicio_encomienda enc ON enc.servicio_id = s.id
  LEFT JOIN motivos_cancelacion mc  ON mc.id = s.motivo_cancelacion_id;

-- ---------------------------------------------------------------------
-- Solicitudes para el conductor: si el pasajero pidió casco, solo motos que lo llevan
-- (igual que V10 más la condición del casco)
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_servicios_disponibles(
    p_conductor_id BIGINT,
    p_radio_km     DOUBLE PRECISION DEFAULT 5)
RETURNS TABLE (servicio_id BIGINT, tipo_servicio VARCHAR, distancia_km DOUBLE PRECISION,
               valor_estimado NUMERIC, solicitado_en TIMESTAMPTZ)
LANGUAGE sql STABLE AS $$
    SELECT s.id, s.tipo_servicio,
           fn_distancia_km(u.lat, u.lng, s.origen_lat, s.origen_lng) AS dist,
           s.valor_estimado, s.solicitado_en
      FROM conductores c
      JOIN ubicacion_conductores u ON u.conductor_id = c.usuario_id
      JOIN vehiculos v ON v.conductor_id = c.usuario_id AND v.activo
      JOIN servicios s ON s.estado = 'buscando'
                      AND s.tipo_vehiculo = v.tipo_vehiculo
                      AND s.expira_busqueda_en > now()
     WHERE c.usuario_id = p_conductor_id
       AND c.estado = 'aprobado'
       AND c.disponible
       AND s.cliente_id <> c.usuario_id
       AND (NOT s.necesita_casco OR v.casco_pasajero IS TRUE)
       AND s.origen_lat BETWEEN u.lat - p_radio_km / 111.045 AND u.lat + p_radio_km / 111.045
       AND fn_distancia_km(u.lat, u.lng, s.origen_lat, s.origen_lng) <= p_radio_km
       AND NOT EXISTS (SELECT 1 FROM usuarios_bloqueados b
                        WHERE (b.usuario_id = s.cliente_id AND b.bloqueado_id = c.usuario_id)
                           OR (b.usuario_id = c.usuario_id AND b.bloqueado_id = s.cliente_id))
     ORDER BY dist, s.solicitado_en
     LIMIT 30;
$$;

-- ---------------------------------------------------------------------
-- 5. Seguir el viaje en vivo (enlace que el pasajero comparte con su familia)
-- ---------------------------------------------------------------------
CREATE TABLE viajes_compartidos (
    token           VARCHAR(43)  PRIMARY KEY CHECK (token ~ '^[A-Za-z0-9_-]{32,43}$'),
    servicio_id     BIGINT       NOT NULL REFERENCES servicios(id) ON DELETE CASCADE,
    usuario_id      BIGINT       NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    expira_en       TIMESTAMPTZ  NOT NULL,
    revocado_en     TIMESTAMPTZ,
    vistas          INTEGER      NOT NULL DEFAULT 0 CHECK (vistas >= 0),
    creado_en       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (expira_en > creado_en)
);
CREATE INDEX ix_compartidos_servicio ON viajes_compartidos (servicio_id);

COMMENT ON TABLE viajes_compartidos IS
    'Enlaces públicos de solo lectura para seguir un viaje en vivo. Sin datos de contacto; vencen solos.';

-- ---------------------------------------------------------------------
-- 6. Alertas de desvío o parada larga ("¿Todo bien?")
-- ---------------------------------------------------------------------
CREATE TABLE alertas_viaje (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    servicio_id     BIGINT           NOT NULL REFERENCES servicios(id) ON DELETE CASCADE,
    usuario_id      BIGINT           NOT NULL REFERENCES usuarios(id),
    tipo            VARCHAR(10)      NOT NULL CHECK (tipo IN ('desvio','detenido')),
    respuesta       VARCHAR(15)      NOT NULL CHECK (respuesta IN ('estoy_bien','sos','sin_respuesta')),
    lat             DOUBLE PRECISION CHECK (lat BETWEEN -90 AND 90),
    lng             DOUBLE PRECISION CHECK (lng BETWEEN -180 AND 180),
    creado_en       TIMESTAMPTZ      NOT NULL DEFAULT now()
);
CREATE INDEX ix_alertas_viaje_servicio ON alertas_viaje (servicio_id, creado_en);
CREATE INDEX ix_alertas_viaje_revisar ON alertas_viaje (creado_en) WHERE respuesta <> 'estoy_bien';

COMMENT ON TABLE alertas_viaje IS 'La app detectó un desvío o una parada larga y preguntó "¿Todo bien?". Soporte revisa las que no son "estoy_bien".';

-- ---------------------------------------------------------------------
-- 7. Conductores favoritos del cliente
-- ---------------------------------------------------------------------
CREATE TABLE conductores_favoritos (
    cliente_id      BIGINT       NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    conductor_id    BIGINT       NOT NULL REFERENCES conductores(usuario_id) ON DELETE CASCADE,
    creado_en       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (cliente_id, conductor_id),
    CHECK (cliente_id <> conductor_id)
);
CREATE INDEX ix_favoritos_conductor ON conductores_favoritos (conductor_id);

COMMENT ON TABLE conductores_favoritos IS 'Al pedir, a los favoritos libres se les ofrece el viaje aunque estén un poco más lejos.';

-- ---------------------------------------------------------------------
-- 8. Viajes programados
-- ---------------------------------------------------------------------
CREATE TABLE viajes_programados (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cliente_id          BIGINT           NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    tipo_vehiculo       VARCHAR(10)      NOT NULL CHECK (tipo_vehiculo IN ('moto','taxi')),
    origen_direccion    VARCHAR(200)     NOT NULL,
    origen_barrio       VARCHAR(80),
    origen_referencia   VARCHAR(200),
    origen_lat          DOUBLE PRECISION NOT NULL CHECK (origen_lat BETWEEN -90 AND 90),
    origen_lng          DOUBLE PRECISION NOT NULL CHECK (origen_lng BETWEEN -180 AND 180),
    destino_direccion   VARCHAR(200)     NOT NULL,
    destino_barrio      VARCHAR(80),
    destino_lat         DOUBLE PRECISION NOT NULL CHECK (destino_lat BETWEEN -90 AND 90),
    destino_lng         DOUBLE PRECISION NOT NULL CHECK (destino_lng BETWEEN -180 AND 180),
    pasajeros           SMALLINT         NOT NULL DEFAULT 1 CHECK (pasajeros BETWEEN 1 AND 4),
    necesita_casco      BOOLEAN          NOT NULL DEFAULT false,
    lleva_equipaje      BOOLEAN          NOT NULL DEFAULT false,
    lleva_mascota       BOOLEAN          NOT NULL DEFAULT false,
    pasajero_nombre     VARCHAR(80),
    pasajero_telefono   VARCHAR(16)      CHECK (pasajero_telefono IS NULL OR pasajero_telefono ~ '^\+573[0-9]{9}$'),
    valor_cotizado      NUMERIC(12,2)    CHECK (valor_cotizado >= 0),
    programado_para     TIMESTAMPTZ      NOT NULL,
    estado              VARCHAR(10)      NOT NULL DEFAULT 'pendiente'
                        CHECK (estado IN ('pendiente','enviado','cancelado','fallido')),
    servicio_id         BIGINT           REFERENCES servicios(id) ON DELETE SET NULL,
    motivo_fallo        VARCHAR(200),
    creado_en           TIMESTAMPTZ      NOT NULL DEFAULT now(),
    actualizado_en      TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CHECK (programado_para > creado_en),
    CHECK (tipo_vehiculo = 'taxi' OR pasajeros = 1),
    CHECK (NOT necesita_casco OR tipo_vehiculo = 'moto'),
    CHECK ((pasajero_nombre IS NULL) = (pasajero_telefono IS NULL)),
    CHECK (estado <> 'enviado' OR servicio_id IS NOT NULL),
    CHECK (estado <> 'fallido' OR motivo_fallo IS NOT NULL)
);
CREATE INDEX ix_programados_por_lanzar ON viajes_programados (programado_para) WHERE estado = 'pendiente';
CREATE INDEX ix_programados_cliente ON viajes_programados (cliente_id, programado_para DESC);
CREATE TRIGGER trg_programados_actualizado
    BEFORE UPDATE ON viajes_programados FOR EACH ROW EXECUTE FUNCTION fn_set_actualizado_en();

COMMENT ON TABLE viajes_programados IS
    'Viajes para más tarde. Una tarea los convierte en servicio unos minutos antes (minutos_anticipo_programado).';

-- ---------------------------------------------------------------------
-- 9. Parámetros (editables por el admin sin desplegar)
-- ---------------------------------------------------------------------
INSERT INTO configuracion_sistema (clave, valor, descripcion) VALUES
    ('minutos_espera_conductor',    '5',   'Minutos que el conductor espera en la recogida antes de poder marcar "no se presentó"'),
    ('max_aumento_tarifa_pct',      '100', 'Lo máximo que el cliente puede subir la tarifa sobre lo cotizado (%)'),
    ('max_aumentos_tarifa',         '3',   'Veces que el cliente puede subir la tarifa en una misma búsqueda'),
    ('radio_favoritos_km',          '8',   'Radio para ofrecer el viaje a los conductores favoritos del cliente'),
    ('minutos_anticipo_programado', '10',  'Minutos antes de la hora en que arranca la búsqueda de un viaje programado'),
    ('min_minutos_programar',       '30',  'Un viaje se puede programar desde dentro de estos minutos'),
    ('max_dias_programar',          '7',   'Un viaje se puede programar hasta estos días adelante'),
    ('max_programados_cliente',     '5',   'Viajes programados pendientes por cliente'),
    ('horas_enlace_compartido',     '6',   'Horas que dura el enlace para seguir un viaje en vivo'),
    ('telefono_central',            '',    'Celular de la central para pedir por llamada si falla internet (+573...). Vacío = no se muestra')
ON CONFLICT (clave) DO NOTHING;

-- Motivo del cliente para cuando quiere esperar menos o el conductor no llega al punto
INSERT INTO motivos_cancelacion (id, actor, descripcion) VALUES
    (6, 'cliente', 'El conductor no llega al punto de recogida')
ON CONFLICT (id) DO NOTHING;
