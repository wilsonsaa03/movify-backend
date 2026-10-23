-- =====================================================================
--  MoviFY · V9 — Distancia con la fórmula de Haversine (tarea 2.4)
--
--  fn_distancia_km usaba la ley esférica de cosenos. Se cambia a Haversine,
--  que es más estable para distancias cortas (dentro de la ciudad) y es la
--  misma fórmula que usa el backend en TarifaServicio.haversineKm.
--
--  Misma firma: fn_conductores_cercanos, fn_servicios_disponibles y el
--  trigger de cobertura la siguen usando sin cambios.
-- =====================================================================

CREATE OR REPLACE FUNCTION fn_distancia_km(
    lat1 DOUBLE PRECISION, lng1 DOUBLE PRECISION,
    lat2 DOUBLE PRECISION, lng2 DOUBLE PRECISION)
RETURNS DOUBLE PRECISION
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    -- LEAST evita el error de dominio de asin() por redondeo.
    SELECT 2 * 6371.0 * asin(LEAST(1.0, sqrt(
               power(sin(radians(lat2 - lat1) / 2), 2)
             + cos(radians(lat1)) * cos(radians(lat2)) * power(sin(radians(lng2 - lng1) / 2), 2))));
$$;

COMMENT ON FUNCTION fn_distancia_km(DOUBLE PRECISION, DOUBLE PRECISION, DOUBLE PRECISION, DOUBLE PRECISION)
    IS 'Distancia en km entre dos coordenadas (fórmula de Haversine, radio de la Tierra 6371 km).';
