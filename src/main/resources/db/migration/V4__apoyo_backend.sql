-- =====================================================================
--  MoviFY · V4 — Objetos de apoyo del backend sobre el modelo v2.3
--  (no cambian el DER: son una función y una vista de solo lectura)
-- =====================================================================

-- Estado "viejo" que todavía entiende la web Angular (PENDIENTE, ACEPTADO...).
-- La app móvil y el código nuevo usan el código del catálogo (buscando, asignado...).
CREATE OR REPLACE FUNCTION fn_estado_legado(p_tipo_servicio VARCHAR, p_estado VARCHAR)
RETURNS VARCHAR LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE p_estado
             WHEN 'buscando'   THEN 'PENDIENTE'
             WHEN 'asignado'   THEN 'ACEPTADO'
             WHEN 'en_origen'  THEN 'LLEGO_AL_ORIGEN'
             WHEN 'en_curso'   THEN CASE WHEN p_tipo_servicio = 'transporte' THEN 'EN_VIAJE' ELSE 'PAQUETE_RECOGIDO' END
             WHEN 'finalizado' THEN 'FINALIZADO'
             WHEN 'cancelado'  THEN 'CANCELADO'
             WHEN 'expirado'   THEN 'CANCELADO'
             ELSE upper(p_estado)
           END;
$$;

-- Una fila por servicio con todo lo que muestran las pantallas (cliente, conductor y admin).
-- El código de entrega de la encomienda NO va aquí: solo lo ve el cliente (lo agrega el backend).
CREATE OR REPLACE VIEW v_servicios_api AS
SELECT s.id,
       s.tipo_servicio,
       upper(s.tipo_servicio)                         AS tipo,
       s.tipo_vehiculo,
       s.cliente_id,
       s.cliente_id                                   AS usuario_id,
       trim(cli.nombres || ' ' || COALESCE(cli.apellidos, '')) AS usuario_nombre,
       cli.telefono                                   AS usuario_telefono,
       s.conductor_id,
       s.vehiculo_id,
       s.estado                                       AS estado_codigo,
       fn_estado_legado(s.tipo_servicio, s.estado)    AS estado,
       e.nombre                                       AS estado_nombre,
       e.color                                        AS estado_color,
       e.es_final                                     AS estado_final,
       COALESCE(dom.descripcion_pedido, enc.descripcion_paquete, s.notas_cliente, '') AS descripcion,
       s.origen_direccion                             AS origen,
       s.origen_barrio, s.origen_referencia,
       s.destino_direccion                            AS destino,
       s.destino_barrio, s.destino_referencia,
       s.origen_lat, s.origen_lng, s.destino_lat, s.destino_lng,
       s.distancia_km,
       s.duracion_estimada_min,
       s.valor_estimado,
       s.valor_final,
       COALESCE(s.valor_final, s.valor_estimado)      AS tarifa,
       s.metodo_pago,
       s.pasajeros,
       s.notas_cliente,
       s.solicitado_en                                AS fecha_solicitud,
       COALESCE(s.iniciado_en, s.asignado_en)         AS fecha_inicio,
       COALESCE(s.finalizado_en, s.cancelado_en)      AS fecha_fin,
       s.solicitado_en, s.expira_busqueda_en, s.asignado_en, s.llegada_origen_en,
       s.iniciado_en, s.finalizado_en, s.cancelado_en,
       s.motivo_cancelacion_id,
       mc.descripcion                                 AS motivo_cancelacion,
       s.detalle_cancelacion,
       CASE WHEN s.estado = 'expirado'              THEN 'sistema'
            WHEN s.cancelado_por IS NULL            THEN NULL
            WHEN s.cancelado_por = s.cliente_id     THEN 'cliente'
            WHEN s.cancelado_por = s.conductor_id   THEN 'conductor'
            ELSE 'personal' END                       AS cancelado_por,
       -- domicilio
       dom.establecimiento, dom.requiere_compra, dom.valor_productos_aprox, dom.valor_compra_real,
       -- encomienda
       enc.remitente_nombre                           AS nombre_remitente,
       enc.remitente_telefono                         AS telefono_remitente,
       enc.destinatario_nombre                        AS nombre_destinatario,
       enc.destinatario_telefono                      AS telefono_destinatario,
       enc.tamano, enc.peso_kg, enc.fragil, enc.valor_declarado, enc.entregado_a
  FROM servicios s
  JOIN estados e        ON e.ambito = 'servicio' AND e.codigo = s.estado
  JOIN usuarios cli     ON cli.id = s.cliente_id
  LEFT JOIN servicio_domicilio  dom ON dom.servicio_id = s.id
  LEFT JOIN servicio_encomienda enc ON enc.servicio_id = s.id
  LEFT JOIN motivos_cancelacion mc  ON mc.id = s.motivo_cancelacion_id;

COMMENT ON VIEW v_servicios_api IS 'Servicios aplanados para la API (incluye el estado "legado" que usa la web).';
