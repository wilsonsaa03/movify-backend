-- =====================================================================
--  MoviFY · V16 — Panel administrativo: operación controlada y negocio configurable
--
--  1. Reasignar un servicio atascado: el personal (admin/soporte) puede devolver
--     un servicio asignado o con el conductor en la recogida a "buscando".
--     Solo cuenta como cancelación del conductor cuando es él quien lo suelta
--     (antes cualquier vuelta a "buscando" le sumaba una cancelación).
--  2. Recargo por lluvia: porcentaje por tarifa que solo se aplica cuando el
--     admin activa "lluvia_activa" en el panel (Buenaventura: llueve mucho).
--  3. Tope de compra de domicilios configurable (antes estaba en el código).
--  4. Contenido prohibido editable: palabras extra (o excepciones) que el admin
--     agrega sin desplegar. La lista base sigue en el código (probada).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Reasignar (personal) sin castigar al conductor
-- ---------------------------------------------------------------------
INSERT INTO transiciones_estado (ambito, estado_origen, estado_destino, actor) VALUES
    ('servicio', 'asignado',  'buscando', 'admin'),
    ('servicio', 'asignado',  'buscando', 'soporte'),
    ('servicio', 'en_origen', 'buscando', 'admin'),
    ('servicio', 'en_origen', 'buscando', 'soporte')
ON CONFLICT DO NOTHING;

-- Igual que V3, pero la cancelación solo se le cuenta al conductor si él soltó el servicio
CREATE OR REPLACE FUNCTION fn_servicios_despues()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_actor_rol VARCHAR(20) := fn_actor_rol();
BEGIN
    -- El historial lo registra el trigger genérico fn_estado_historial.
    IF NEW.estado IS NOT DISTINCT FROM OLD.estado THEN
        RETURN NULL;
    END IF;

    IF NEW.estado = 'asignado' THEN
        UPDATE servicio_ofertas
           SET estado = CASE WHEN conductor_id = NEW.conductor_id THEN 'aceptada' ELSE 'expirada' END,
               respondida_en = now()
         WHERE servicio_id = NEW.id AND estado IN ('enviada','vista');

    ELSIF NEW.estado = 'buscando' AND OLD.conductor_id IS NOT NULL THEN
        IF v_actor_rol IS DISTINCT FROM 'admin' AND v_actor_rol IS DISTINCT FROM 'soporte' THEN
            UPDATE conductores SET total_cancelaciones = total_cancelaciones + 1 WHERE usuario_id = OLD.conductor_id;
        END IF;
        UPDATE servicio_ofertas SET estado = 'rechazada', respondida_en = now()
         WHERE servicio_id = NEW.id AND conductor_id = OLD.conductor_id;
        UPDATE servicio_ofertas SET estado = 'enviada', respondida_en = NULL, enviada_en = now()
         WHERE servicio_id = NEW.id AND estado = 'expirada';

    ELSIF (SELECT es_final FROM estados WHERE ambito = 'servicio' AND codigo = NEW.estado) THEN
        UPDATE servicio_ofertas SET estado = 'expirada', respondida_en = now()
         WHERE servicio_id = NEW.id AND estado IN ('enviada','vista');
        IF NEW.estado = 'finalizado' THEN
            UPDATE conductores SET total_servicios = total_servicios + 1 WHERE usuario_id = NEW.conductor_id;
        ELSIF NEW.estado = 'cancelado' AND v_actor_rol = 'conductor' THEN
            UPDATE conductores SET total_cancelaciones = total_cancelaciones + 1 WHERE usuario_id = NEW.conductor_id;
        END IF;
    END IF;

    RETURN NULL;
END $$;

-- ---------------------------------------------------------------------
-- 2. Recargo por lluvia
-- ---------------------------------------------------------------------
ALTER TABLE tarifas
    ADD COLUMN recargo_lluvia_pct NUMERIC(5,2) NOT NULL DEFAULT 0
        CHECK (recargo_lluvia_pct BETWEEN 0 AND 100);
COMMENT ON COLUMN tarifas.recargo_lluvia_pct IS
    'Recargo (%) que se suma mientras configuracion_sistema.lluvia_activa = 1 (lo prende el admin).';

-- Las tarifas siguen sin poder editarse (ahora también el recargo por lluvia)
CREATE OR REPLACE FUNCTION fn_tarifas_inmutables()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.tarifa_base, NEW.valor_km, NEW.valor_minuto, NEW.tarifa_minima, NEW.recargo_nocturno_pct,
        NEW.recargo_lluvia_pct, NEW.tipo_servicio, NEW.tipo_vehiculo)
       IS DISTINCT FROM
       (OLD.tarifa_base, OLD.valor_km, OLD.valor_minuto, OLD.tarifa_minima, OLD.recargo_nocturno_pct,
        OLD.recargo_lluvia_pct, OLD.tipo_servicio, OLD.tipo_vehiculo) THEN
        RAISE EXCEPTION 'Los valores de una tarifa no se modifican: crea una nueva y desactiva la anterior'
              USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

-- Igual que V3 más el recargo por lluvia (se suma al nocturno, sobre el valor base)
CREATE OR REPLACE FUNCTION fn_calcular_tarifa(
    p_tarifa_id BIGINT, p_km NUMERIC, p_min NUMERIC, p_momento TIMESTAMPTZ DEFAULT now())
RETURNS NUMERIC LANGUAGE plpgsql STABLE AS $$
DECLARE
    t        tarifas%ROWTYPE;
    v_valor  NUMERIC;
    v_hora   INTEGER;
    v_recargo NUMERIC := 0;
BEGIN
    SELECT * INTO t FROM tarifas WHERE id = p_tarifa_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Tarifa % no existe', p_tarifa_id USING ERRCODE = 'no_data_found';
    END IF;

    v_valor := t.tarifa_base + COALESCE(p_km, 0) * t.valor_km + COALESCE(p_min, 0) * t.valor_minuto;
    v_hora  := EXTRACT(HOUR FROM p_momento AT TIME ZONE 'America/Bogota');
    IF v_hora >= fn_config_num('hora_inicio_nocturno', 21) OR v_hora < fn_config_num('hora_fin_nocturno', 5) THEN
        v_recargo := v_recargo + t.recargo_nocturno_pct;
    END IF;
    IF fn_config_num('lluvia_activa', 0) = 1 THEN
        v_recargo := v_recargo + t.recargo_lluvia_pct;
    END IF;
    v_valor := v_valor * (1 + v_recargo / 100);
    v_valor := GREATEST(v_valor, t.tarifa_minima);
    RETURN ceil(v_valor / 100) * 100;                 -- redondeo a la centena (efectivo)
END $$;

-- ---------------------------------------------------------------------
-- 3. Parámetros nuevos
-- ---------------------------------------------------------------------
INSERT INTO configuracion_sistema (clave, valor, descripcion) VALUES
    ('lluvia_activa',          '0',      'Recargo por lluvia: 1 = activo (lo prende y apaga el admin), 0 = apagado'),
    ('tope_compra_domicilio',  '500000', 'Lo máximo que el domiciliario adelanta de su bolsillo por un pedido ($)')
ON CONFLICT (clave) DO NOTHING;

-- ---------------------------------------------------------------------
-- 4. Contenido prohibido editable
-- ---------------------------------------------------------------------
CREATE TABLE palabras_prohibidas (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    palabra     VARCHAR(60)  NOT NULL CHECK (palabra = lower(palabra) AND length(trim(palabra)) >= 3),
    categoria   VARCHAR(40)  NOT NULL,
    tipo        VARCHAR(10)  NOT NULL DEFAULT 'prohibida' CHECK (tipo IN ('prohibida', 'permitida')),
    creado_por  BIGINT       REFERENCES usuarios(id),
    creado_en   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_palabras_prohibidas UNIQUE (palabra, tipo)
);
COMMENT ON TABLE palabras_prohibidas IS
    'Palabras que el admin agrega a la lista de contenido prohibido (o excepciones legales, tipo = permitida). '
    'La lista base está en ContenidoProhibido.java.';
