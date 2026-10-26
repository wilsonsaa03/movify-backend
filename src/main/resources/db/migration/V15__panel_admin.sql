-- =====================================================================
--  MoviFY · V15 — Panel administrativo web
--
--  Las alertas "¿Todo bien?" (V14, alertas_viaje) las revisa soporte desde
--  el panel: quién la revisó, cuándo y qué hizo. Mismos permisos del SOS
--  (admin_sos.ver / admin_sos.atender).
-- =====================================================================

ALTER TABLE alertas_viaje
    ADD COLUMN revisada_por BIGINT REFERENCES usuarios(id),
    ADD COLUMN revisada_en  TIMESTAMPTZ,
    ADD COLUMN notas        VARCHAR(500),
    ADD CONSTRAINT ck_alertas_viaje_revision CHECK ((revisada_en IS NULL) = (revisada_por IS NULL));

-- Pendientes de revisar: las que no fueron "estoy bien" y nadie ha mirado
CREATE INDEX ix_alertas_viaje_pendientes ON alertas_viaje (creado_en DESC)
    WHERE respuesta <> 'estoy_bien' AND revisada_en IS NULL;

COMMENT ON COLUMN alertas_viaje.revisada_por IS 'Persona de soporte o admin que revisó la alerta en el panel.';
COMMENT ON COLUMN alertas_viaje.notas IS 'Qué se hizo (llamamos al pasajero, todo bien, se escaló a la Policía…).';
