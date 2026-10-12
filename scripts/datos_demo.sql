-- =====================================================================
--  MoviFY · Datos de DEMOSTRACIÓN (solo base local o de sustentación, NUNCA producción)
--
--  Crea 3 cuentas con la clave 1357:
--    Cliente demo ........ +57 300 000 0001
--    Conductor moto demo . +57 300 000 0002  (demo.moto@movify.co, placa DEM12A, aprobado)
--    Conductor taxi demo . +57 300 000 0003  (demo.taxi@movify.co, placa DEM123, aprobado)
--  Con app.demo.simulacion=true, si nadie acepta en 10 s, uno de estos conductores acepta
--  el servicio y recorre el viaje solo (SimulacionDemo).
--
--  psql -U dev_movify -d movify_local -v ON_ERROR_STOP=1 -f scripts/datos_demo.sql
-- =====================================================================
BEGIN;

DO $$
DECLARE
    v_clave CONSTANT VARCHAR := '$2a$10$z1wBVOETH61EOO8y0rIBAeUfwmu73zIWuMUFpyS8dLqBGYq2pgrhu';  -- BCrypt de 1357
    v_cli   BIGINT;
    v_moto  BIGINT;
    v_taxi  BIGINT;
    v_veh   BIGINT;
    r       RECORD;
BEGIN
    IF EXISTS (SELECT 1 FROM usuarios WHERE telefono IN ('+573000000001', '+573000000002', '+573000000003')) THEN
        RAISE NOTICE 'Los datos de demostración ya existen';
        RETURN;
    END IF;

    INSERT INTO usuarios (nombres, apellidos, telefono, telefono_verificado, password_hash)
    VALUES ('Cliente', 'Demo', '+573000000001', true, v_clave) RETURNING id INTO v_cli;
    INSERT INTO usuario_roles (usuario_id, rol) VALUES (v_cli, 'cliente');

    FOR r IN SELECT * FROM (VALUES
            ('Moto', '+573000000002', 'demo.moto@movify.co', 'moto', 'DEM12A', 'Yamaha', 'FZ 2.0', '{A2}', NULL::VARCHAR, '1000000002'),
            ('Taxi', '+573000000003', 'demo.taxi@movify.co', 'taxi', 'DEM123', 'Hyundai', 'Atos', '{C1}', 'Taxis del Puerto', '1000000003'))
            AS t(nombre, tel, correo, tipo, placa, marca, linea, cats, empresa, cedula) LOOP

        PERFORM set_config('movify.actor', 'conductor', true);
        INSERT INTO usuarios (nombres, apellidos, telefono, telefono_verificado, email, password_hash)
        VALUES ('Conductor', r.nombre || ' Demo', r.tel, true, r.correo, v_clave) RETURNING id INTO v_moto;
        INSERT INTO usuario_roles (usuario_id, rol) VALUES (v_moto, 'cliente');
        INSERT INTO conductores (usuario_id, tipo_identificacion, numero_identificacion, fecha_nacimiento, categorias_licencia)
        VALUES (v_moto, 'CC', r.cedula, DATE '1990-01-01', r.cats::VARCHAR(2)[]);
        INSERT INTO vehiculos (conductor_id, tipo_vehiculo, placa, marca, linea, modelo_anio, color, empresa_afiliadora)
        VALUES (v_moto, r.tipo, r.placa, r.marca, r.linea, 2022, CASE WHEN r.tipo = 'taxi' THEN 'Amarillo' ELSE 'Negro' END, r.empresa)
        RETURNING id INTO v_veh;
        INSERT INTO documentos_conductor (conductor_id, vehiculo_id, tipo_documento, ruta_archivo, mime_type, tamano_bytes, fecha_vencimiento)
        SELECT v_moto, CASE WHEN td.aplica_a = 'vehiculo' THEN v_veh END, td.codigo,
               'demo_00000000-0000-0000-0000-00000000000' || v_moto || '.pdf', 'application/pdf', 1,
               CASE WHEN td.requiere_vencimiento THEN CURRENT_DATE + 365 END
          FROM tipos_documento td
         WHERE CASE r.tipo WHEN 'moto' THEN td.obligatorio_moto ELSE td.obligatorio_taxi END;

        PERFORM set_config('movify.actor', 'admin', true);
        UPDATE documentos_conductor SET estado = 'aprobado' WHERE conductor_id = v_moto AND vigente;
        UPDATE conductores SET estado = 'aprobado' WHERE usuario_id = v_moto;
        -- Ubicación inicial: centro de Buenaventura
        INSERT INTO ubicacion_conductores (conductor_id, lat, lng) VALUES (v_moto, 3.8815, -77.0290);
    END LOOP;
    RAISE NOTICE 'Datos de demostración creados (clave 1357)';
END $$;

COMMIT;
