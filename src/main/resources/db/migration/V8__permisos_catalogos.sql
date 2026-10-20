-- =====================================================================
--  MoviFY · V8 — CRUD de datos básicos (tarea 1.5)
--
--  El admin administra desde el panel los catálogos de la plataforma
--  (motivos de cancelación y tipos de documento) con /api/admin/catalogos/...
--  Soporte solo los consulta.
--
--  OJO: los permisos van dentro del JWT; el admin debe volver a iniciar sesión
--  para recibir los nuevos.
-- =====================================================================

INSERT INTO modulos (codigo, nombre, icono, ruta_frontend, modulo_padre, orden) VALUES
    ('catalogos', 'Datos básicos', 'list', '/admin/catalogos', NULL, 30);

INSERT INTO permisos (codigo, modulo, accion, descripcion, es_sensible) VALUES
    ('catalogos.ver',       'catalogos', 'ver',       'Ver los datos básicos de la plataforma', false),
    ('catalogos.gestionar', 'catalogos', 'gestionar', 'Crear, editar y eliminar datos básicos', true);

INSERT INTO rol_permisos (rol, permiso) VALUES
    ('admin',   'catalogos.ver'),
    ('admin',   'catalogos.gestionar'),
    ('soporte', 'catalogos.ver');
