-- =====================================================================
--  MoviFY · V10 — Solo los conductores conectados ven solicitudes (tarea 2.5)
--
--  fn_conductores_cercanos (al pedir) ya exigía c.disponible, pero
--  fn_servicios_disponibles (cuando el conductor consulta) solo exigía
--  'aprobado': un conductor desconectado igual veía las solicitudes.
--  Se agrega AND c.disponible; el resto queda igual que en V3.
-- =====================================================================

CREATE OR REPLACE FUNCTION fn_servicios_disponibles(
    p_conductor_id BIGINT,
    p_radio_km     DOUBLE PRECISION DEFAULT 5)
RETURNS TABLE (servicio_id BIGINT, tipo_servicio VARCHAR, distancia_km DOUBLE PRECISION,
               valor_estimado NUMERIC, solicitado_en TIMESTAMPTZ)
LANGUAGE sql STABLE AS $$
    SELECT s.id, s.tipo_servicio,
           fn_distancia_km(u.lat, u.lng, s.origen_lat, s.origen_lng) AS dist,
           s.valor_estimado, s.solicitado_en
      FROM conductores c
      JOIN ubicacion_conductores u ON u.conductor_id = c.usuario_id
      JOIN vehiculos v ON v.conductor_id = c.usuario_id AND v.activo
      JOIN servicios s ON s.estado = 'buscando'
                      AND s.tipo_vehiculo = v.tipo_vehiculo
                      AND s.expira_busqueda_en > now()
     WHERE c.usuario_id = p_conductor_id
       AND c.estado = 'aprobado'
       AND c.disponible
       AND s.cliente_id <> c.usuario_id
       AND s.origen_lat BETWEEN u.lat - p_radio_km / 111.045 AND u.lat + p_radio_km / 111.045
       AND fn_distancia_km(u.lat, u.lng, s.origen_lat, s.origen_lng) <= p_radio_km
       AND NOT EXISTS (SELECT 1 FROM usuarios_bloqueados b
                        WHERE (b.usuario_id = s.cliente_id AND b.bloqueado_id = c.usuario_id)
                           OR (b.usuario_id = c.usuario_id AND b.bloqueado_id = s.cliente_id))
     ORDER BY dist, s.solicitado_en
     LIMIT 30;
$$;
