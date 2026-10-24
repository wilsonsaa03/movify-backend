-- =====================================================================
--  MoviFY · V13 — PIN del viaje (Figma "Conductor app 4 · PIN")
--
--  "Pídele el PIN a Ana — así confirmas que llevas a la persona correcta".
--  Se genera al pedir un viaje (transporte); solo lo ve el pasajero. El
--  conductor lo escribe al iniciar el viaje y el backend lo compara.
--  Los servicios viejos quedan sin PIN (null).
-- =====================================================================

ALTER TABLE servicios
    ADD COLUMN codigo_abordaje CHAR(4) CHECK (codigo_abordaje IS NULL OR codigo_abordaje ~ '^[0-9]{4}$');

COMMENT ON COLUMN servicios.codigo_abordaje IS
    'PIN de 4 dígitos del viaje: el pasajero se lo dice al conductor para empezar (solo transporte).';
