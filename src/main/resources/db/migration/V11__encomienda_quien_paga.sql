-- =====================================================================
--  MoviFY · V11 — Encomienda: quién paga el envío (Figma "Encomienda 3 · Confirmar")
--
--  "¿Quién paga el envío?  Yo (al recoger) | Destinatario (al recibir)".
--  Siempre en efectivo; lo que cambia es a quién le cobra el conductor.
--  false = paga quien envía (lo de siempre), true = paga quien recibe.
-- =====================================================================

ALTER TABLE servicio_encomienda
    ADD COLUMN paga_destinatario BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN servicio_encomienda.paga_destinatario IS
    'true = el conductor le cobra en efectivo a quien recibe; false = a quien envía, al recoger.';
