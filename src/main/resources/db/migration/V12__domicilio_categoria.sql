-- =====================================================================
--  MoviFY · V12 — Domicilio: qué es el pedido (Figma "Domicilio 1 · Pedido")
--
--  "¿Qué es?  Comida | Farmacia | Mercado | Diligencia". Sirve para el ícono
--  en las pantallas, para que el domiciliario sepa qué va a buscar y para
--  las estadísticas del admin. Opcional: la web vieja no lo manda.
-- =====================================================================

ALTER TABLE servicio_domicilio
    ADD COLUMN categoria VARCHAR(12)
        CHECK (categoria IS NULL OR categoria IN ('comida', 'farmacia', 'mercado', 'diligencia'));

COMMENT ON COLUMN servicio_domicilio.categoria IS
    'Qué es el pedido: comida, farmacia, mercado o diligencia (null = sin indicar).';
