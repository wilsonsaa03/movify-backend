-- Nota de voz del punto de referencia ("la casa azul de dos pisos"): el cliente la graba al pedir
-- y el conductor la escucha en la solicitud. Se guarda como archivo (ArchivoServicio); aquí va el nombre.
ALTER TABLE servicios
    ADD COLUMN nota_voz_archivo  VARCHAR(80),
    ADD COLUMN nota_voz_segundos SMALLINT CHECK (nota_voz_segundos BETWEEN 1 AND 60);

COMMENT ON COLUMN servicios.nota_voz_archivo  IS 'Nota de voz del punto de referencia (nombre del archivo en app.documentos.dir)';
COMMENT ON COLUMN servicios.nota_voz_segundos IS 'Duración de la nota de voz en segundos';
