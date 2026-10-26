-- =====================================================================
-- V17 · Perfil del conductor (estilo Uber Driver / DiDi / WayCali, sin niveles)
--   1. "Sobre mí": una frase corta que el pasajero ve al ser asignado.
--   2. Elogios como dato propio de la calificación ("Muy amable", "Manejó con cuidado"…). Antes iban pegados
--      al inicio del comentario; se copian aquí para no perder los que ya existen.
-- =====================================================================

ALTER TABLE conductores ADD COLUMN sobre_mi VARCHAR(160);
COMMENT ON COLUMN conductores.sobre_mi IS 'Frase del conductor que ve el pasajero asignado. Sin teléfonos, correos ni enlaces (lo valida el backend).';

ALTER TABLE calificaciones
    ADD COLUMN elogios VARCHAR(40)[] NOT NULL DEFAULT '{}',
    ADD CONSTRAINT ck_calificaciones_elogios CHECK (cardinality(elogios) <= 6);
COMMENT ON COLUMN calificaciones.elogios IS 'Elogios elegidos al calificar (lista cerrada en ViajeServicio.ELOGIOS).';

-- Los elogios que ya venían dentro del comentario. Las calificaciones son inmutables (trigger): se apaga solo
-- para esta copia única, dentro de la misma transacción de Flyway, y se vuelve a encender.
ALTER TABLE calificaciones DISABLE TRIGGER trg_calificaciones_inmutable;
UPDATE calificaciones c
   SET elogios = ARRAY(SELECT e FROM unnest(ARRAY[
           'Manejó con cuidado', 'Muy amable', 'Llegó rápido', 'Conoce la zona', 'Me prestó casco', 'Carro limpio',
           'Buena conducta', 'Dio indicaciones precisas', 'Pagó completo']::varchar[]) AS e
         WHERE position(e IN c.comentario) > 0)
 WHERE c.comentario IS NOT NULL;
ALTER TABLE calificaciones ENABLE TRIGGER trg_calificaciones_inmutable;

CREATE INDEX ix_calificaciones_elogios ON calificaciones USING gin (elogios) WHERE elogios <> '{}';
