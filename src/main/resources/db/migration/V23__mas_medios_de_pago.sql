-- =====================================================================
-- V23: más medios de pago digitales (por Wompi): tarjeta débito/crédito, PSE y botón Bancolombia,
-- además de Nequi y Daviplata (V22). El efectivo sigue siendo la opción por defecto y el respaldo.
--  - Tarjeta: los datos de la tarjeta van directo de la app a Wompi (token); MoviFY solo guarda los últimos 4.
--  - PSE y Bancolombia: el cliente termina el pago en la página del banco (url que entrega Wompi).
-- =====================================================================

ALTER TABLE servicios DROP CONSTRAINT IF EXISTS ck_servicios_metodo_pago;
ALTER TABLE servicios ADD CONSTRAINT ck_servicios_metodo_pago
    CHECK (metodo_pago IN ('efectivo', 'nequi', 'daviplata', 'tarjeta', 'pse', 'bancolombia'));
COMMENT ON COLUMN servicios.metodo_pago IS
    'efectivo | nequi | daviplata | tarjeta | pse | bancolombia. Si el pago digital no se aprueba, se paga en efectivo.';

DO $$
DECLARE c TEXT;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
              WHERE conrelid = 'pagos'::regclass AND contype = 'c'
                AND pg_get_constraintdef(oid) LIKE '%metodo%'
    LOOP
        EXECUTE format('ALTER TABLE pagos DROP CONSTRAINT %I', c);
    END LOOP;
END $$;
ALTER TABLE pagos ADD CONSTRAINT ck_pagos_metodo
    CHECK (metodo IN ('nequi', 'daviplata', 'tarjeta', 'pse', 'bancolombia'));
