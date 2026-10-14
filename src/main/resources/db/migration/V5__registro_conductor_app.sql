-- =====================================================================
--  MoviFY · V5 — Registro del conductor por pasos (prototipo de Figma)
--
--  Pasos de la app: ¿moto o taxi? → 1 Identidad → 2 Licencia → 3 Vehículo → 4 Documentos → 5 Revisa y envía
--
--  Cambios sobre el DER v2.3:
--   - conductores: la categoría de licencia se pide en el paso 2, así que el perfil puede nacer sin ella;
--     se guarda el número de licencia y cuándo envió la solicitud.
--   - vehiculos: cilindraje (moto) y si lleva casco para el pasajero.
--   - tipos_documento: cada foto del prototipo es un documento (frente/reverso, selfie, fotos del vehículo,
--     casco certificado), con el paso de la app al que pertenece. La técnico-mecánica solo se exige
--     cuando el vehículo ya tiene la edad que pide la ley (2 años).
--   - documentos_legales: condiciones para conductores (se aceptan al enviar la solicitud).
-- =====================================================================

-- ───────────── Conductores ─────────────
ALTER TABLE conductores DROP CONSTRAINT ck_conductores_categorias;
ALTER TABLE conductores ADD CONSTRAINT ck_conductores_categorias
    CHECK (categorias_licencia <@ ARRAY['A1','A2','B1','B2','B3','C1','C2','C3']::VARCHAR(2)[]);
ALTER TABLE conductores ALTER COLUMN categorias_licencia SET DEFAULT '{}';
ALTER TABLE conductores
    ADD COLUMN numero_licencia      VARCHAR(20) CHECK (numero_licencia ~ '^[0-9A-Z]{4,20}$'),
    ADD COLUMN solicitud_enviada_en TIMESTAMPTZ;

COMMENT ON COLUMN conductores.solicitud_enviada_en IS
    'Cuándo el conductor tocó "Enviar solicitud" (paso 5). Antes de eso el registro está incompleto.';

-- ───────────── Vehículos ─────────────
ALTER TABLE vehiculos
    ADD COLUMN cilindraje_cc  SMALLINT CHECK (cilindraje_cc BETWEEN 50 AND 2000),
    ADD COLUMN casco_pasajero BOOLEAN;

-- ───────────── Documentos ─────────────
ALTER TABLE tipos_documento
    ADD COLUMN paso                VARCHAR(12) NOT NULL DEFAULT 'documentos'
               CHECK (paso IN ('identidad','licencia','vehiculo','documentos')),
    ADD COLUMN orden               SMALLINT    NOT NULL DEFAULT 50,
    ADD COLUMN solo_imagen         BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN exigir_desde_anios  SMALLINT,
    ADD COLUMN ayuda               VARCHAR(120);

COMMENT ON COLUMN tipos_documento.exigir_desde_anios IS
    'Si tiene valor, el documento solo es obligatorio cuando el vehículo tiene al menos esos años (técnico-mecánica).';

UPDATE tipos_documento SET nombre = 'Documento de identidad (frente)', paso = 'identidad', orden = 1, solo_imagen = true
 WHERE codigo = 'cedula';
UPDATE tipos_documento SET nombre = 'Licencia de conducción (frente)', paso = 'licencia', orden = 1, solo_imagen = true
 WHERE codigo = 'licencia_conduccion';
UPDATE tipos_documento SET paso = 'documentos', orden = 10, ayuda = 'Vigente y a nombre del vehículo'                    WHERE codigo = 'soat';
UPDATE tipos_documento SET nombre = 'Tarjeta de propiedad', paso = 'documentos', orden = 20,
                           ayuda = 'A tu nombre o con autorización del dueño'                                            WHERE codigo = 'tarjeta_propiedad';
UPDATE tipos_documento SET paso = 'documentos', orden = 30, exigir_desde_anios = 2,
                           ayuda = 'Si el vehículo tiene más de 2 años'                                                  WHERE codigo = 'tecnomecanica';
UPDATE tipos_documento SET paso = 'documentos', orden = 40, ayuda = 'Solo para taxis'                                    WHERE codigo = 'tarjeta_operacion';
UPDATE tipos_documento SET paso = 'documentos', orden = 45, ayuda = 'Expedida por la secretaría de movilidad'             WHERE codigo = 'tarjeta_control';
UPDATE tipos_documento SET paso = 'documentos', orden = 60, ayuda = 'Opcional'                                           WHERE codigo = 'antecedentes';

INSERT INTO tipos_documento (codigo, nombre, aplica_a, requiere_vencimiento, obligatorio_moto, obligatorio_taxi,
                             paso, orden, solo_imagen, ayuda) VALUES
    ('cedula_reverso',    'Documento de identidad (reverso)',  'conductor', false, true,  true,  'identidad', 2, true,  NULL),
    ('selfie',            'Selfie',                            'conductor', false, true,  true,  'identidad', 3, true,  'Buena luz, sin gafas ni gorra'),
    ('licencia_reverso',  'Licencia de conducción (reverso)',  'conductor', false, true,  true,  'licencia',  2, true,  NULL),
    ('foto_frente',       'Foto del vehículo (frente)',        'vehiculo',  false, true,  true,  'vehiculo',  1, true,  NULL),
    ('foto_atras',        'Foto del vehículo (atrás)',         'vehiculo',  false, true,  true,  'vehiculo',  2, true,  NULL),
    ('foto_lado',         'Foto del vehículo (lado)',          'vehiculo',  false, true,  true,  'vehiculo',  3, true,  NULL),
    ('foto_placa',        'Foto de la placa',                  'vehiculo',  false, true,  false, 'vehiculo',  4, true,  NULL),
    ('foto_interior',     'Foto del interior',                 'vehiculo',  false, false, true,  'vehiculo',  4, true,  NULL),
    ('casco_certificado', 'Casco certificado',                 'vehiculo',  false, true,  false, 'documentos', 50, true, 'Foto del casco con su sello');

-- ¿El documento es obligatorio para este vehículo? (tipo y año del modelo)
CREATE OR REPLACE FUNCTION fn_documento_exigido(p_codigo VARCHAR, p_tipo_vehiculo VARCHAR, p_modelo_anio INT)
RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT CASE p_tipo_vehiculo WHEN 'moto' THEN td.obligatorio_moto
                                WHEN 'taxi' THEN td.obligatorio_taxi
                                ELSE td.obligatorio_moto OR td.obligatorio_taxi END
           AND (td.exigir_desde_anios IS NULL OR p_modelo_anio IS NULL
                OR EXTRACT(YEAR FROM CURRENT_DATE)::INT - p_modelo_anio >= td.exigir_desde_anios)
      FROM tipos_documento td
     WHERE td.codigo = p_codigo;
$$;

-- La aprobación del admin usa la misma regla (técnico-mecánica según la edad del vehículo)
CREATE OR REPLACE FUNCTION fn_conductores_validar()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_tipo       VARCHAR(10);
    v_anio       INT;
    v_vehiculo   BIGINT;
    v_faltantes  TEXT;
BEGIN
    IF TG_OP = 'INSERT' OR NEW.fecha_nacimiento IS DISTINCT FROM OLD.fecha_nacimiento THEN
        IF NEW.fecha_nacimiento > CURRENT_DATE - INTERVAL '18 years' THEN
            RAISE EXCEPTION 'El conductor debe ser mayor de edad' USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    IF TG_OP = 'INSERT' THEN
        NEW.estado := 'pendiente';
        NEW.disponible := false;
        RETURN NEW;
    END IF;

    IF NEW.estado IS DISTINCT FROM OLD.estado THEN
        NEW.verificado_en  := now();
        NEW.verificado_por := COALESCE(fn_actor_usuario(), NEW.verificado_por);

        IF NEW.estado <> 'aprobado' THEN
            NEW.disponible := false;
        ELSE
            IF NOT EXISTS (SELECT 1 FROM usuarios WHERE id = NEW.usuario_id AND telefono_verificado) THEN
                RAISE EXCEPTION 'No se puede aprobar: el conductor no ha verificado su celular'
                      USING ERRCODE = 'check_violation';
            END IF;
            IF cardinality(NEW.categorias_licencia) = 0 THEN
                RAISE EXCEPTION 'No se puede aprobar: el conductor no ha registrado su licencia'
                      USING ERRCODE = 'check_violation';
            END IF;
            SELECT id, tipo_vehiculo, modelo_anio INTO v_vehiculo, v_tipo, v_anio
              FROM vehiculos WHERE conductor_id = NEW.usuario_id AND activo;
            IF v_vehiculo IS NULL THEN
                RAISE EXCEPTION 'No se puede aprobar: el conductor no tiene vehículo activo'
                      USING ERRCODE = 'check_violation';
            END IF;

            SELECT string_agg(td.nombre, ', ' ORDER BY td.nombre) INTO v_faltantes
              FROM tipos_documento td
             WHERE fn_documento_exigido(td.codigo, v_tipo, v_anio)
               AND NOT EXISTS (
                     SELECT 1 FROM documentos_conductor d
                      WHERE d.conductor_id   = NEW.usuario_id
                        AND d.tipo_documento = td.codigo
                        AND d.vigente AND d.estado = 'aprobado'
                        AND (d.fecha_vencimiento IS NULL OR d.fecha_vencimiento >= CURRENT_DATE)
                        AND (td.aplica_a = 'conductor' OR d.vehiculo_id = v_vehiculo));
            IF v_faltantes IS NOT NULL THEN
                RAISE EXCEPTION 'No se puede aprobar: faltan documentos aprobados y vigentes: %', v_faltantes
                      USING ERRCODE = 'check_violation';
            END IF;
            NEW.motivo_estado := NULL;
        END IF;
    END IF;

    IF NEW.disponible AND NOT OLD.disponible THEN
        IF NEW.estado <> 'aprobado' THEN
            RAISE EXCEPTION 'Solo los conductores aprobados pueden conectarse (estado: %)', NEW.estado
                  USING ERRCODE = 'check_violation';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM vehiculos WHERE conductor_id = NEW.usuario_id AND activo) THEN
            RAISE EXCEPTION 'No puedes conectarte sin un vehículo activo' USING ERRCODE = 'check_violation';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM ubicacion_conductores u
                        WHERE u.conductor_id = NEW.usuario_id
                          AND u.actualizado_en > now() - make_interval(secs => fn_config_num('ubicacion_vigencia_seg', 300)::INT)) THEN
            RAISE EXCEPTION 'Envía tu ubicación antes de conectarte' USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    RETURN NEW;
END $$;

-- ───────────── Condiciones para conductores ─────────────
ALTER TABLE documentos_legales DROP CONSTRAINT IF EXISTS documentos_legales_tipo_check;
ALTER TABLE documentos_legales ADD CONSTRAINT documentos_legales_tipo_check
    CHECK (tipo IN ('terminos','privacidad','tratamiento_datos','contrato_conductor'));
INSERT INTO documentos_legales (tipo, version, url, obligatorio) VALUES
    ('contrato_conductor', '1.0', 'https://movify.ds2.eleueleo.com/legal/condiciones-conductor-v1.pdf', false);

-- ───────────── Panel del admin ─────────────
-- Misma vista, con dos columnas nuevas al final (la web sigue leyendo las de siempre).
CREATE OR REPLACE VIEW v_conductores_pendientes AS
SELECT c.usuario_id, u.nombres, u.apellidos, u.telefono, c.tipo_identificacion, c.numero_identificacion,
       c.categorias_licencia, v.tipo_vehiculo, v.placa, c.creado_en,
       COUNT(d.id) FILTER (WHERE d.vigente)                            AS documentos_cargados,
       COUNT(d.id) FILTER (WHERE d.vigente AND d.estado = 'pendiente')  AS documentos_por_revisar,
       COUNT(d.id) FILTER (WHERE d.vigente AND d.estado = 'aprobado')   AS documentos_aprobados,
       c.solicitud_enviada_en,
       c.numero_licencia
  FROM conductores c
  JOIN usuarios u                  ON u.id = c.usuario_id
  LEFT JOIN vehiculos v            ON v.conductor_id = c.usuario_id AND v.activo
  LEFT JOIN documentos_conductor d ON d.conductor_id = c.usuario_id
 WHERE c.estado = 'pendiente'
 GROUP BY c.usuario_id, u.id, v.id;
