-- =====================================================================
-- Catálogo de DEMOSTRACIÓN para domicilios (V24): comercios aliados de Buenaventura (ficticios), productos con
-- opciones, una licorera (+18) y cupones simulados. Solo para pruebas y la sustentación; NO correr en producción.
-- Se puede correr varias veces: si ya existe "Burger Pacífico" no hace nada.
--
--   psql -h localhost -U dev_movify -d movify_local -f scripts/catalogo_demo.sql
-- =====================================================================
DO $$
DECLARE
    c_burger BIGINT; c_droga BIGINT; c_mercado BIGINT; c_licor BIGINT; c_pan BIGINT;
    p BIGINT; g BIGINT;
BEGIN
    IF EXISTS (SELECT 1 FROM comercios_domicilio WHERE nombre = 'Burger Pacífico') THEN
        RAISE NOTICE 'El catálogo demo ya estaba cargado';
        RETURN;
    END IF;

    -- ─── Comercios (horarios amplios para poder probar casi a cualquier hora) ───
    INSERT INTO comercios_domicilio (nombre, categoria, descripcion, direccion, barrio, lat, lng, telefono, abre, cierra,
                                     pedido_minimo, radio_km, minutos_preparacion, orden)
    VALUES ('Burger Pacífico', 'comida', 'Hamburguesas, perros y papas al estilo del Pacífico', 'Calle 2 # 3-45', 'Centro',
            3.8851, -77.0251, '+573001112233', '06:00', '02:00', 15000, 10, 20, 1) RETURNING id INTO c_burger;
    INSERT INTO comercios_domicilio (nombre, categoria, descripcion, direccion, barrio, lat, lng, telefono, abre, cierra,
                                     pedido_minimo, radio_km, minutos_preparacion, orden)
    VALUES ('Droguería La Económica', 'farmacia', 'Medicamentos de venta libre y cuidado personal', 'Carrera 5 # 7-10', 'Pueblo Nuevo',
            3.8829, -77.0201, '+573002223344', '00:00', '23:59', 0, 12, 5, 2) RETURNING id INTO c_droga;
    INSERT INTO comercios_domicilio (nombre, categoria, descripcion, direccion, barrio, lat, lng, abre, cierra,
                                     pedido_minimo, radio_km, minutos_preparacion, orden)
    VALUES ('Mercadito El Puerto', 'mercado', 'Frutas, verduras y abarrotes', 'Avenida Simón Bolívar # 12-30', 'La Independencia',
            3.8790, -77.0110, '05:00', '23:00', 10000, 10, 15, 3) RETURNING id INTO c_mercado;
    INSERT INTO comercios_domicilio (nombre, categoria, descripcion, direccion, barrio, lat, lng, abre, cierra,
                                     pedido_minimo, radio_km, minutos_preparacion, orden)
    VALUES ('Licorera La Bahía', 'licores', 'Cerveza, aguardiente y ron. Solo mayores de 18 años', 'Calle 6 # 8-20', 'El Firme',
            3.8810, -77.0170, '10:00', '03:00', 20000, 8, 10, 4) RETURNING id INTO c_licor;
    INSERT INTO comercios_domicilio (nombre, categoria, descripcion, direccion, barrio, lat, lng, abre, cierra,
                                     pedido_minimo, radio_km, minutos_preparacion, orden, activo)
    VALUES ('Panadería La Marea', 'comida', 'Pan, pandebono y tortas (abre de madrugada)', 'Calle 4 # 2-15', 'Bellavista',
            3.8875, -77.0290, '05:00', '05:30', 0, 8, 10, 5, true) RETURNING id INTO c_pan;   -- casi siempre cerrada (prueba)

    -- ─── Burger Pacífico ───
    INSERT INTO productos_domicilio (comercio_id, seccion, nombre, descripcion, precio, orden)
    VALUES (c_burger, 'Hamburguesas', 'Hamburguesa clásica', 'Carne de res 150 g, lechuga, tomate y salsa de la casa', 18000, 1)
    RETURNING id INTO p;
    INSERT INTO grupos_opciones_domicilio (producto_id, nombre, minimo, maximo, orden) VALUES (p, 'Tamaño', 1, 1, 0) RETURNING id INTO g;
    INSERT INTO opciones_domicilio (grupo_id, nombre, precio_extra, orden) VALUES (g, 'Sencilla', 0, 0), (g, 'Doble carne', 5000, 1);
    INSERT INTO grupos_opciones_domicilio (producto_id, nombre, minimo, maximo, orden) VALUES (p, 'Adiciones', 0, 3, 1) RETURNING id INTO g;
    INSERT INTO opciones_domicilio (grupo_id, nombre, precio_extra, orden)
    VALUES (g, 'Queso extra', 3000, 0), (g, 'Tocineta', 4000, 1), (g, 'Huevo', 2000, 2), (g, 'Maduro', 2500, 3);
    INSERT INTO grupos_opciones_domicilio (producto_id, nombre, minimo, maximo, orden) VALUES (p, 'Quitar', 0, 3, 2) RETURNING id INTO g;
    INSERT INTO opciones_domicilio (grupo_id, nombre, precio_extra, orden) VALUES (g, 'Sin cebolla', 0, 0), (g, 'Sin tomate', 0, 1), (g, 'Sin salsas', 0, 2);

    INSERT INTO productos_domicilio (comercio_id, seccion, nombre, descripcion, precio, orden)
    VALUES (c_burger, 'Hamburguesas', 'Hamburguesa de pescado', 'Filete de pargo apanado con salsa tártara', 22000, 2) RETURNING id INTO p;
    INSERT INTO grupos_opciones_domicilio (producto_id, nombre, minimo, maximo, orden) VALUES (p, 'Acompañante', 1, 1, 0) RETURNING id INTO g;
    INSERT INTO opciones_domicilio (grupo_id, nombre, precio_extra, orden) VALUES (g, 'Papas', 0, 0), (g, 'Patacones', 1500, 1);

    INSERT INTO productos_domicilio (comercio_id, seccion, nombre, descripcion, precio, orden)
    VALUES (c_burger, 'Acompañantes', 'Papas a la francesa', 'Porción mediana', 7000, 1),
           (c_burger, 'Acompañantes', 'Patacones con hogao', '4 patacones', 8000, 2);
    INSERT INTO productos_domicilio (comercio_id, seccion, nombre, descripcion, precio, orden)
    VALUES (c_burger, 'Bebidas', 'Jugo de borojó', 'Natural, en agua o en leche', 6000, 1) RETURNING id INTO p;
    INSERT INTO grupos_opciones_domicilio (producto_id, nombre, minimo, maximo, orden) VALUES (p, 'Preparación', 1, 1, 0) RETURNING id INTO g;
    INSERT INTO opciones_domicilio (grupo_id, nombre, precio_extra, orden) VALUES (g, 'En agua', 0, 0), (g, 'En leche', 1000, 1);
    INSERT INTO productos_domicilio (comercio_id, seccion, nombre, descripcion, precio, orden, disponible)
    VALUES (c_burger, 'Bebidas', 'Gaseosa 400 ml', 'Sabores surtidos', 3500, 2, true),
           (c_burger, 'Bebidas', 'Limonada de coco', 'Agotada por hoy (prueba de producto agotado)', 7000, 3, false);

    -- ─── Droguería La Económica ───
    INSERT INTO productos_domicilio (comercio_id, seccion, nombre, descripcion, precio, orden)
    VALUES (c_droga, 'Dolor y fiebre', 'Acetaminofén 500 mg x 10', 'Tabletas', 3500, 1),
           (c_droga, 'Dolor y fiebre', 'Ibuprofeno 400 mg x 10', 'Tabletas', 5200, 2),
           (c_droga, 'Hidratación', 'Suero oral 500 ml', 'Sabor naranja', 4800, 1),
           (c_droga, 'Cuidado personal', 'Repelente 120 ml', 'Contra mosquitos', 14500, 1),
           (c_droga, 'Cuidado personal', 'Protector solar FPS 50', '60 ml', 32000, 2);

    -- ─── Mercadito El Puerto ───
    INSERT INTO productos_domicilio (comercio_id, seccion, nombre, descripcion, precio, orden)
    VALUES (c_mercado, 'Frutas', 'Banano x libra', NULL, 2500, 1),
           (c_mercado, 'Frutas', 'Chontaduro x 6', 'Cocinado, con sal y miel', 6000, 2),
           (c_mercado, 'Abarrotes', 'Arroz 1 kg', NULL, 4900, 1),
           (c_mercado, 'Abarrotes', 'Aceite 1 L', NULL, 11900, 2),
           (c_mercado, 'Lácteos', 'Leche 1 L', 'Entera', 4600, 1);
    INSERT INTO productos_domicilio (comercio_id, seccion, nombre, descripcion, precio, orden, solo_mayores)
    VALUES (c_mercado, 'Bebidas', 'Cerveza lata 330 ml', 'Solo mayores de 18 años', 3800, 1, true);

    -- ─── Licorera La Bahía (+18) ───
    INSERT INTO productos_domicilio (comercio_id, seccion, nombre, descripcion, precio, orden, solo_mayores)
    VALUES (c_licor, 'Cerveza', 'Six pack cerveza 330 ml', 'Lata', 21000, 1, true),
           (c_licor, 'Aguardiente', 'Aguardiente media', '375 ml', 32000, 1, true),
           (c_licor, 'Ron', 'Ron añejo botella', '750 ml', 65000, 1, true);
    INSERT INTO productos_domicilio (comercio_id, seccion, nombre, descripcion, precio, orden, solo_mayores)
    VALUES (c_licor, 'Mezcladores', 'Hielo bolsa 2 kg', NULL, 4000, 1, false) RETURNING id INTO p;

    -- ─── Panadería La Marea (abre solo de 5:00 a 5:30 a. m.: sirve para probar "cerrado") ───
    INSERT INTO productos_domicilio (comercio_id, seccion, nombre, precio, orden)
    VALUES (c_pan, 'Panes', 'Pandebono x 4', 6000, 1), (c_pan, 'Panes', 'Pan de coco', 3000, 2);

    -- ─── Cupones simulados ───
    INSERT INTO cupones_domicilio (codigo, descripcion, tipo, valor, tope_descuento, minimo_compra, solo_primer_pedido, usos_por_cliente)
    VALUES ('BIENVENIDA20', '20 % de descuento en tu primer domicilio (hasta $10.000)', 'porcentaje', 20, 10000, 20000, true, 1),
           ('DOMIGRATIS', 'Domicilio gratis en compras desde $25.000', 'envio', 50000, NULL, 25000, false, 3),
           ('PACIFICO5', '$5.000 de descuento en Burger Pacífico', 'valor', 5000, NULL, 15000, false, 5);
    UPDATE cupones_domicilio SET comercio_id = c_burger WHERE codigo = 'PACIFICO5';
    INSERT INTO cupones_domicilio (codigo, descripcion, tipo, valor, minimo_compra, usos_por_cliente, vence_en)
    VALUES ('VENCIDO10', 'Cupón de prueba que ya venció', 'valor', 10000, 0, 1, now() - INTERVAL '1 day');

    RAISE NOTICE 'Catálogo demo cargado: 5 comercios y 4 cupones';
END $$;
