-- V26: en pruebas el vehículo simulado hace cada tramo en poco tiempo (app.simulacion.max-segundos),
-- pero la app sigue mostrando el tiempo realista del trayecto ("6 min", "Llegas a las 12:14").
--
--   velocidad_kmh           = con la que de verdad avanza el vehículo (puede ser muy alta para cumplir el tope)
--   velocidad_mostrada_kmh  = con la que se calcula el tiempo que ve la gente (moto 32, taxi 28 km/h)

-- Para que una ruta larga quepa en el tope hace falta más de 200 km/h (ej.: 15 km en 60 s = 900 km/h)
ALTER TABLE simulacion_viajes DROP CONSTRAINT IF EXISTS simulacion_viajes_velocidad_kmh_check;
ALTER TABLE simulacion_viajes ADD CONSTRAINT ck_simulacion_velocidad CHECK (velocidad_kmh BETWEEN 1 AND 5000);

ALTER TABLE simulacion_viajes ADD COLUMN velocidad_mostrada_kmh NUMERIC(5,1)
    CHECK (velocidad_mostrada_kmh IS NULL OR velocidad_mostrada_kmh BETWEEN 1 AND 200);

COMMENT ON COLUMN simulacion_viajes.velocidad_kmh IS
    'Velocidad con la que avanza el vehículo simulado (en pruebas puede ser alta para que el tramo dure poco).';
COMMENT ON COLUMN simulacion_viajes.velocidad_mostrada_kmh IS
    'Velocidad realista con la que se calcula el tiempo que muestra la app (eta_s). NULL = la misma velocidad_kmh.';
