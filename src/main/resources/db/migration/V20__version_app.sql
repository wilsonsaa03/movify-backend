-- =====================================================================
--  MoviFY · V20 — Versión mínima de la app
--
--  La app consulta GET /api/catalogos/app-version al abrir. Si la versión instalada es menor que
--  app_version_minima, muestra "Actualiza MoviFY" y no deja seguir (por ejemplo, cuando un cambio del
--  backend ya no es compatible con las versiones viejas). Si es menor que app_version_ultima, solo
--  sugiere actualizar. El admin las cambia en Configuración.
-- =====================================================================

INSERT INTO configuracion_sistema (clave, valor, descripcion) VALUES
    ('app_version_minima', '1.0.0', 'Versión mínima de la app: las más viejas deben actualizar para seguir (ej. 1.2.0)'),
    ('app_version_ultima', '1.0.0', 'Última versión publicada en la tienda: a las anteriores se les sugiere actualizar'),
    ('app_url_android',    'https://play.google.com/store/apps/details?id=co.movify.app',
                           'Enlace de MoviFY en Google Play (botón "Actualizar")')
ON CONFLICT (clave) DO NOTHING;
