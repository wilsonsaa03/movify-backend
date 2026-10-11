-- MoviFY · V2 — columnas y tablas que el código asumía y que antes se "toleraban" con try/catch.
-- Es idempotente (IF NOT EXISTS): sirve tanto para producción como para una base nueva.

-- Columnas usadas por el panel admin y el perfil del conductor
ALTER TABLE conductores ADD COLUMN IF NOT EXISTS motivo_rechazo varchar(500);
ALTER TABLE conductores ADD COLUMN IF NOT EXISTS ciudad varchar(80) DEFAULT 'Buenaventura';

-- Quién canceló (cliente | conductor | personal): la sanción del conductor solo cuenta las suyas
ALTER TABLE servicios ADD COLUMN IF NOT EXISTS cancelado_por varchar(20);

-- Retiros del conductor (antes se insertaba en historial_pagos con columnas que no existen y fallaba en silencio)
CREATE TABLE IF NOT EXISTS retiros_conductor (
    id           bigserial PRIMARY KEY,
    conductor_id bigint      NOT NULL REFERENCES conductores(id),
    monto        numeric(12,2) NOT NULL CHECK (monto > 0),
    estado       varchar(20) NOT NULL DEFAULT 'pendiente'
                 CHECK (estado IN ('pendiente', 'pagado', 'rechazado')),
    fecha        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_retiros_conductor ON retiros_conductor (conductor_id, fecha DESC);

-- Rol del cliente: la BD tenía "usuario" y "cliente" mezclados; el DER v2.3 usa "cliente"
UPDATE usuarios SET rol = 'cliente' WHERE rol = 'usuario';

-- Calificaciones: rango 1-5 y una sola por usuario y servicio.
-- Se conserva la primera calificación si había duplicadas por el bug anterior.
DELETE FROM calificaciones c
USING calificaciones d
WHERE c.servicio_id = d.servicio_id AND c.usuario_id = d.usuario_id AND c.id > d.id;

UPDATE calificaciones SET calificacion = LEAST(5, GREATEST(1, calificacion))
WHERE calificacion NOT BETWEEN 1 AND 5;

CREATE UNIQUE INDEX IF NOT EXISTS uq_calificacion_servicio_usuario ON calificaciones (servicio_id, usuario_id);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_calificacion_rango') THEN
        ALTER TABLE calificaciones ADD CONSTRAINT ck_calificacion_rango CHECK (calificacion BETWEEN 1 AND 5);
    END IF;
END $$;

-- El usuario de simulación tenía contraseña '123' en texto plano: se bloquea
UPDATE usuarios SET estado = 'bloqueado' WHERE correo = 'simula@movify.com';
UPDATE conductores SET en_linea = false, estado = 'rechazado', motivo_rechazo = 'Conductor de simulación deshabilitado'
WHERE placa = 'SIM-001';

-- Índices para las consultas más frecuentes
CREATE INDEX IF NOT EXISTS idx_servicios_conductor_estado ON servicios (conductor_id, estado);
CREATE INDEX IF NOT EXISTS idx_servicios_usuario_fecha ON servicios (usuario_id, fecha_solicitud DESC);
CREATE INDEX IF NOT EXISTS idx_notif_conductor_estado ON notificaciones_conductor (conductor_id, estado_notificacion);
