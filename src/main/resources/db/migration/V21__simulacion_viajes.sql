-- V21: simulación de viajes por las calles (moto, taxi, domicilio y encomienda).
--
-- Una fila por servicio con viaje simulado: es la ÚNICA fuente de verdad de dónde va el vehículo.
-- La posición no se guarda punto por punto: se calcula con
--     recorrido = recorrido_m + velocidad × (ahora − marca_en)      (solo si estado = 'corriendo')
-- sobre la polilínea `geometria` que devolvió el motor de rutas (OSRM). Así el backend, el conductor
-- y el pasajero calculan exactamente el mismo punto, sin mandar la posición cada segundo.
-- El backend además copia ese punto a ubicacion_conductores cada segundo (web, panel admin, enlace compartido).

CREATE TABLE simulacion_viajes (
    servicio_id     BIGINT           PRIMARY KEY REFERENCES servicios(id) ON DELETE CASCADE,
    conductor_id    BIGINT           NOT NULL REFERENCES conductores(usuario_id) ON DELETE CASCADE,
    -- recogida = del conductor hasta el origen (pasajero, tienda o remitente); destino = del origen a la entrega
    fase            VARCHAR(10)      NOT NULL CHECK (fase IN ('recogida', 'destino')),
    estado          VARCHAR(10)      NOT NULL CHECK (estado IN ('lista', 'corriendo', 'pausada', 'llego')),
    -- [[lat, lng], ...] tal como sale de OSRM (todos los vértices de la calle, en orden)
    geometria       JSONB            NOT NULL CHECK (jsonb_typeof(geometria) = 'array' AND jsonb_array_length(geometria) >= 2),
    distancia_m     DOUBLE PRECISION NOT NULL CHECK (distancia_m >= 0),
    duracion_ruta_s DOUBLE PRECISION,
    recorrido_m     DOUBLE PRECISION NOT NULL DEFAULT 0 CHECK (recorrido_m >= 0),
    velocidad_kmh   NUMERIC(5,1)     NOT NULL CHECK (velocidad_kmh BETWEEN 1 AND 200),
    -- Momento en que recorrido_m era exacto (se mueve al pausar, reanudar o cambiar la velocidad)
    marca_en        TIMESTAMPTZ      NOT NULL DEFAULT now(),
    -- Sube cada vez que cambia la ruta: la app vuelve a pedir la geometría
    version         INT              NOT NULL DEFAULT 1,
    -- servicios.destino_cambiado_en con el que se calculó la ruta (si cambia, se recalcula)
    destino_version TIMESTAMPTZ,
    perfil          VARCHAR(20)      NOT NULL DEFAULT 'driving',
    creado_en       TIMESTAMPTZ      NOT NULL DEFAULT now(),
    actualizado_en  TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE INDEX ix_simulacion_conductor ON simulacion_viajes (conductor_id);

COMMENT ON TABLE simulacion_viajes IS
    'Viaje simulado por la ruta real (OSRM): fase, ruta, metros recorridos, velocidad y estado. Fuente única de la posición mientras dura el servicio.';
COMMENT ON COLUMN simulacion_viajes.recorrido_m IS 'Metros recorridos sobre la geometría en el instante marca_en.';
