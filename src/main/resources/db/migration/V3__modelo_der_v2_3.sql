-- =====================================================================
--  MoviFY · V3 — Reestructuración al modelo de datos DER v2.3 (45 tablas)
--
--  Decisión del equipo (octubre 2026): EMPEZAR CON LA BASE LIMPIA.
--  1) Se borra todo lo que crearon V1 y V2 (y cualquier tabla suelta que hubiera
--     quedado en producción), menos el historial de Flyway.
--  2) Se crea el modelo v2.3 completo: catálogos, seguridad (RBAC), usuarios,
--     conductores, vehículos, documentos, tarifas, servicios y soporte, con sus
--     triggers, funciones, vistas y datos semilla.
--
--  Único cambio frente al DER: password_hash ya no es "solo admin". Clientes y
--  conductores entran con su celular + una clave de 4 dígitos (BCrypt).
-- =====================================================================

DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN SELECT viewname FROM pg_views WHERE schemaname = 'public' LOOP
        EXECUTE format('DROP VIEW IF EXISTS public.%I CASCADE', r.viewname);
    END LOOP;
    FOR r IN SELECT tablename FROM pg_tables
              WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history' LOOP
        EXECUTE format('DROP TABLE IF EXISTS public.%I CASCADE', r.tablename);
    END LOOP;
    FOR r IN SELECT sequence_name FROM information_schema.sequences WHERE sequence_schema = 'public' LOOP
        EXECUTE format('DROP SEQUENCE IF EXISTS public.%I CASCADE', r.sequence_name);
    END LOOP;
    FOR r IN SELECT p.oid::regprocedure AS firma
               FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
              WHERE n.nspname = 'public' AND p.prokind IN ('f', 'p')
                AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.objid = p.oid AND d.deptype = 'e') LOOP
        EXECUTE format('DROP ROUTINE IF EXISTS %s CASCADE', r.firma);
    END LOOP;
END $$;

-- =====================================================================
--  MoviFY — Modelo de datos (PostgreSQL 14+ / probado en 16)
--  Dabliu Soluciones · Proyecto Integrador · Univalle Sede Pacífico
--  Versión 2.3 — octubre 2026 (solo Buenaventura, catálogo central de estados, ajustes al prototipo)
--
--  Alcance: transporte (moto / taxi), domicilios y encomiendas.
--  Roles: cliente, conductor, admin (un usuario puede tener varios).
--  Acceso: celular + clave de 4 dígitos (código SMS para registrarse y recuperarla),
--          Google, y contraseña para admin y soporte.
--  Sin pagos en línea: el servicio se liquida en efectivo por fuera.
--  Sin PostGIS: distancias con fórmula esférica + prefiltro por caja.
--
--  Ejecutar sobre una base vacía:
--    psql -U dev_movify -d movify_local -v ON_ERROR_STOP=1 -f movify_modelo_datos.sql
--
--  Convención para el backend (JdbcTemplate): antes de cada UPDATE que cambie
--  estados, ejecutar en la MISMA transacción
--    SELECT set_config('movify.usuario_id', :idUsuario, true),
--           set_config('movify.actor',      :rol,       true);   -- cliente|conductor|admin|sistema
--  Con eso los triggers validan quién puede hacer cada transición y la auditoría
--  queda con nombre propio.
-- =====================================================================

SET LOCAL client_min_messages = warning;

-- =====================================================================
-- 0. UTILIDADES
-- =====================================================================

CREATE OR REPLACE FUNCTION fn_set_actualizado_en()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.actualizado_en := now();
    RETURN NEW;
END $$;

-- Distancia en km entre dos coordenadas (ley esférica de cosenos).
-- GREATEST/LEAST evita el error de dominio de acos() por redondeo.
CREATE OR REPLACE FUNCTION fn_distancia_km(
    lat1 DOUBLE PRECISION, lng1 DOUBLE PRECISION,
    lat2 DOUBLE PRECISION, lng2 DOUBLE PRECISION)
RETURNS DOUBLE PRECISION
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT 6371.0 * acos(GREATEST(-1.0, LEAST(1.0,
             cos(radians(lat1)) * cos(radians(lat2)) * cos(radians(lng2) - radians(lng1))
           + sin(radians(lat1)) * sin(radians(lat2)))));
$$;

-- Usuario y rol que el backend declaró con set_config (NULL si no lo hizo).
CREATE OR REPLACE FUNCTION fn_actor_usuario()
RETURNS BIGINT LANGUAGE sql STABLE AS $$
    SELECT NULLIF(current_setting('movify.usuario_id', true), '')::BIGINT;
$$;

CREATE OR REPLACE FUNCTION fn_actor_rol()
RETURNS VARCHAR LANGUAGE sql STABLE AS $$
    SELECT NULLIF(current_setting('movify.actor', true), '');
$$;

-- Bitácoras inmutables: nadie edita ni borra el pasado.
CREATE OR REPLACE FUNCTION fn_bloquear_modificacion()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'La tabla % es de solo inserción', TG_TABLE_NAME
          USING ERRCODE = 'insufficient_privilege';
END $$;


-- =====================================================================
-- 1. CATÁLOGOS Y CONFIGURACIÓN
-- =====================================================================

CREATE TABLE roles (
    codigo          VARCHAR(20)  PRIMARY KEY CHECK (codigo ~ '^[a-z_]{3,20}$'),
    nombre          VARCHAR(50)  NOT NULL,
    descripcion     VARCHAR(200),
    es_sistema      BOOLEAN      NOT NULL DEFAULT false,   -- los de sistema no se borran ni renombran
    activo          BOOLEAN      NOT NULL DEFAULT true
);

CREATE TABLE tipos_vehiculo (
    codigo              VARCHAR(10) PRIMARY KEY,
    nombre              VARCHAR(40) NOT NULL,
    capacidad_pasajeros SMALLINT    NOT NULL CHECK (capacidad_pasajeros BETWEEN 1 AND 8)
);

CREATE TABLE tipos_servicio (
    codigo          VARCHAR(20)  PRIMARY KEY,
    nombre          VARCHAR(40)  NOT NULL,
    descripcion     VARCHAR(200)
);

CREATE TABLE servicio_vehiculo_permitido (
    tipo_servicio   VARCHAR(20) NOT NULL REFERENCES tipos_servicio(codigo),
    tipo_vehiculo   VARCHAR(10) NOT NULL REFERENCES tipos_vehiculo(codigo),
    PRIMARY KEY (tipo_servicio, tipo_vehiculo)
);

-- ---------------------------------------------------------------------
-- CATÁLOGO CENTRAL DE ESTADOS ("celda de estados")
--  Un solo catálogo para todas las tablas con ciclo de vida. Cada tabla tiene:
--    estado         → el código del estado actual (lo que escribe el backend)
--    estado_ambito  → columna GENERADA y fija ('usuario', 'servicio'...)
--  y una FK compuesta (estado_ambito, estado) → estados(ambito, codigo).
--  Así un documento nunca puede quedar en un estado de servicio, el panel
--  muestra nombre y color desde la base, y agregar un estado es un INSERT.
-- ---------------------------------------------------------------------
CREATE TABLE estados (
    ambito          VARCHAR(20)  NOT NULL CHECK (ambito IN ('usuario','conductor','documento','servicio',
                                                              'oferta','alerta_sos','ticket')),
    codigo          VARCHAR(20)  NOT NULL CHECK (codigo ~ '^[a-z_]{3,20}$'),
    nombre          VARCHAR(50)  NOT NULL,                 -- texto que ve el usuario en la app
    descripcion     VARCHAR(200),
    color           CHAR(7)      NOT NULL DEFAULT '#8A94A6' CHECK (color ~ '^#[0-9A-F]{6}$'),  -- chip en la UI
    es_inicial      BOOLEAN      NOT NULL DEFAULT false,
    es_final        BOOLEAN      NOT NULL DEFAULT false,
    orden           SMALLINT     NOT NULL,
    PRIMARY KEY (ambito, codigo),
    CHECK (NOT (es_inicial AND es_final))
);
CREATE UNIQUE INDEX ux_estados_inicial ON estados (ambito) WHERE es_inicial;

-- Máquina de estados de TODOS los ámbitos: qué cambio está permitido y quién puede hacerlo.
CREATE TABLE transiciones_estado (
    ambito          VARCHAR(20) NOT NULL,
    estado_origen   VARCHAR(20) NOT NULL,
    estado_destino  VARCHAR(20) NOT NULL,
    actor           VARCHAR(20) NOT NULL CHECK (actor IN ('cliente','conductor','soporte','admin','sistema')),
    PRIMARY KEY (ambito, estado_origen, estado_destino, actor),
    FOREIGN KEY (ambito, estado_origen)  REFERENCES estados (ambito, codigo),
    FOREIGN KEY (ambito, estado_destino) REFERENCES estados (ambito, codigo),
    CHECK (estado_origen <> estado_destino)
);

CREATE TABLE motivos_cancelacion (
    id              SMALLINT     PRIMARY KEY,
    actor           VARCHAR(20)  NOT NULL CHECK (actor IN ('cliente','conductor','admin','sistema')),  -- admin = plataforma (admin y soporte)
    descripcion     VARCHAR(120) NOT NULL,
    activo          BOOLEAN      NOT NULL DEFAULT true
);

CREATE TABLE tipos_documento (
    codigo                  VARCHAR(30)  PRIMARY KEY,
    nombre                  VARCHAR(80)  NOT NULL,
    aplica_a                VARCHAR(10)  NOT NULL CHECK (aplica_a IN ('conductor','vehiculo')),
    requiere_vencimiento    BOOLEAN      NOT NULL DEFAULT false,
    obligatorio_moto        BOOLEAN      NOT NULL DEFAULT false,
    obligatorio_taxi        BOOLEAN      NOT NULL DEFAULT false
);

CREATE TABLE configuracion_sistema (
    clave           VARCHAR(60)  PRIMARY KEY,
    valor           VARCHAR(200) NOT NULL,
    descripcion     VARCHAR(200),
    actualizado_en  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_configuracion_actualizado
    BEFORE UPDATE ON configuracion_sistema FOR EACH ROW EXECUTE FUNCTION fn_set_actualizado_en();

CREATE OR REPLACE FUNCTION fn_config_num(p_clave VARCHAR, p_defecto NUMERIC)
RETURNS NUMERIC LANGUAGE sql STABLE AS $$
    SELECT COALESCE((SELECT valor::NUMERIC FROM configuracion_sistema WHERE clave = p_clave), p_defecto);
$$;

-- Términos, política de privacidad y autorización de tratamiento de datos (Ley 1581 de 2012).
CREATE TABLE documentos_legales (
    id              SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tipo            VARCHAR(20)  NOT NULL CHECK (tipo IN ('terminos','privacidad','tratamiento_datos')),
    version         VARCHAR(10)  NOT NULL,
    url             VARCHAR(300) NOT NULL,
    vigente_desde   DATE         NOT NULL DEFAULT CURRENT_DATE,
    obligatorio     BOOLEAN      NOT NULL DEFAULT true,
    activo          BOOLEAN      NOT NULL DEFAULT true,
    CONSTRAINT ux_documentos_legales_version UNIQUE (tipo, version)
);
CREATE UNIQUE INDEX ux_documentos_legales_activo ON documentos_legales (tipo) WHERE activo;


-- =====================================================================
-- 2. USUARIOS, ACCESO, DISPOSITIVOS Y SEGURIDAD PERSONAL
-- =====================================================================

CREATE TABLE usuarios (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombres               VARCHAR(80)  NOT NULL,
    apellidos             VARCHAR(80),
    telefono              VARCHAR(16),                   -- +57 y 10 dígitos: +573001234567
    telefono_verificado   BOOLEAN      NOT NULL DEFAULT false,
    email                 VARCHAR(120),
    email_verificado      BOOLEAN      NOT NULL DEFAULT false,
    google_sub            VARCHAR(64),                   -- id estable de la cuenta Google
    password_hash         VARCHAR(100),                  -- BCrypt: clave de 4 dígitos (app) o contraseña (admin/soporte)
    foto_url              VARCHAR(300),
    estado                VARCHAR(20)  NOT NULL DEFAULT 'activo',
    estado_ambito         VARCHAR(20)  NOT NULL GENERATED ALWAYS AS ('usuario') STORED,
    motivo_bloqueo        VARCHAR(300),
    calificacion_promedio NUMERIC(3,2) NOT NULL DEFAULT 5.00 CHECK (calificacion_promedio BETWEEN 1 AND 5),
    total_calificaciones  INTEGER      NOT NULL DEFAULT 0 CHECK (total_calificaciones >= 0),
    ultimo_acceso         TIMESTAMPTZ,
    creado_en             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actualizado_en        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    eliminado_en          TIMESTAMPTZ,
    CONSTRAINT ck_usuarios_telefono_formato
        CHECK (telefono IS NULL OR telefono ~ '^\+573[0-9]{9}$'),          -- celular colombiano
    CONSTRAINT ck_usuarios_email_formato
        CHECK (email IS NULL OR email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    CONSTRAINT ck_usuarios_metodo_acceso
        CHECK (estado = 'eliminado' OR telefono IS NOT NULL OR google_sub IS NOT NULL OR password_hash IS NOT NULL),
    CONSTRAINT ck_usuarios_eliminado
        CHECK ((estado = 'eliminado') = (eliminado_en IS NOT NULL)),
    CONSTRAINT ck_usuarios_bloqueo_motivo
        CHECK (estado <> 'bloqueado' OR motivo_bloqueo IS NOT NULL)
);
CREATE UNIQUE INDEX ux_usuarios_telefono   ON usuarios (telefono)     WHERE telefono   IS NOT NULL;
CREATE UNIQUE INDEX ux_usuarios_google_sub ON usuarios (google_sub)   WHERE google_sub IS NOT NULL;
CREATE UNIQUE INDEX ux_usuarios_email      ON usuarios (lower(email)) WHERE email      IS NOT NULL;
CREATE TRIGGER trg_usuarios_actualizado
    BEFORE UPDATE ON usuarios FOR EACH ROW EXECUTE FUNCTION fn_set_actualizado_en();

CREATE TABLE usuario_roles (
    usuario_id      BIGINT      NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    rol             VARCHAR(20) NOT NULL REFERENCES roles(codigo),
    asignado_por    BIGINT      REFERENCES usuarios(id),
    asignado_en     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (usuario_id, rol)
);
CREATE INDEX ix_usuario_roles_rol ON usuario_roles (rol);


-- =====================================================================
-- 2B. CATÁLOGO DE SEGURIDAD (RBAC: módulos → permisos → roles → usuarios)
--
--  Modelo: un permiso es "modulo.accion" (ej. conductores.aprobar). Los roles
--  agrupan permisos; un usuario hereda los permisos de todos sus roles y puede
--  tener excepciones individuales (conceder o revocar, con vencimiento).
--  En Spring Security cada permiso es una authority: @PreAuthorize("hasAuthority('conductores.aprobar')").
--  En Angular, v_menu_usuario arma el menú y los guards por ruta.
-- =====================================================================

CREATE TABLE modulos (
    codigo          VARCHAR(30)  PRIMARY KEY CHECK (codigo ~ '^[a-z_]{3,30}$'),
    nombre          VARCHAR(60)  NOT NULL,
    descripcion     VARCHAR(200),
    icono           VARCHAR(40),                  -- nombre del ícono en el frontend
    ruta_frontend   VARCHAR(100),                 -- ruta de Angular, ej. /admin/conductores
    modulo_padre    VARCHAR(30)  REFERENCES modulos(codigo),
    orden           SMALLINT     NOT NULL DEFAULT 0,
    activo          BOOLEAN      NOT NULL DEFAULT true,
    CHECK (modulo_padre IS NULL OR modulo_padre <> codigo)
);

CREATE TABLE permisos (
    codigo          VARCHAR(60)  PRIMARY KEY,
    modulo          VARCHAR(30)  NOT NULL REFERENCES modulos(codigo),
    accion          VARCHAR(30)  NOT NULL CHECK (accion ~ '^[a-z_]{2,30}$'),
    descripcion     VARCHAR(200) NOT NULL,
    es_sensible     BOOLEAN      NOT NULL DEFAULT false,   -- acciones de alto impacto (se auditan siempre)
    activo          BOOLEAN      NOT NULL DEFAULT true,
    CONSTRAINT ux_permisos_modulo_accion UNIQUE (modulo, accion),
    CONSTRAINT ck_permisos_codigo CHECK (codigo = modulo || '.' || accion)
);

CREATE TABLE rol_permisos (
    rol             VARCHAR(20)  NOT NULL REFERENCES roles(codigo) ON DELETE CASCADE,
    permiso         VARCHAR(60)  NOT NULL REFERENCES permisos(codigo) ON DELETE CASCADE,
    asignado_por    BIGINT       REFERENCES usuarios(id),
    asignado_en     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (rol, permiso)
);
CREATE INDEX ix_rol_permisos_permiso ON rol_permisos (permiso);

-- Excepciones por usuario: conceder un permiso extra o revocar uno heredado del rol.
CREATE TABLE usuario_permisos (
    usuario_id      BIGINT       NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    permiso         VARCHAR(60)  NOT NULL REFERENCES permisos(codigo) ON DELETE CASCADE,
    tipo            VARCHAR(10)  NOT NULL CHECK (tipo IN ('conceder','revocar')),
    motivo          VARCHAR(200) NOT NULL,
    vence_en        TIMESTAMPTZ,
    asignado_por    BIGINT       REFERENCES usuarios(id),
    asignado_en     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (usuario_id, permiso),
    CHECK (vence_en IS NULL OR vence_en > asignado_en)
);
CREATE INDEX ix_usuario_permisos_permiso ON usuario_permisos (permiso);

-- Bitácora de inicios de sesión (exitosos y fallidos). Alimenta el bloqueo temporal.
CREATE TABLE registro_accesos (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id      BIGINT       REFERENCES usuarios(id) ON DELETE SET NULL,
    identificador   VARCHAR(120) NOT NULL,          -- teléfono o email que se intentó
    metodo          VARCHAR(10)  NOT NULL CHECK (metodo IN ('sms','google','password','refresh')),
    resultado       VARCHAR(25)  NOT NULL CHECK (resultado IN (
                        'exitoso','codigo_invalido','codigo_vencido','password_invalido',
                        'token_google_invalido','usuario_inexistente','cuenta_bloqueada',
                        'cuenta_eliminada','acceso_bloqueado_temporal','sin_permiso')),
    ip_origen       VARCHAR(45),
    user_agent      VARCHAR(300),
    plataforma      VARCHAR(10)  CHECK (plataforma IN ('android','ios','web')),
    creado_en       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_accesos_usuario_fecha ON registro_accesos (usuario_id, creado_en DESC) WHERE usuario_id IS NOT NULL;
CREATE INDEX ix_accesos_ip_fecha      ON registro_accesos (ip_origen, creado_en DESC) WHERE ip_origen IS NOT NULL;
CREATE INDEX ix_accesos_fallidos      ON registro_accesos (creado_en DESC) WHERE resultado <> 'exitoso';
CREATE INDEX brin_accesos_fecha       ON registro_accesos USING brin (creado_en);

-- Bloqueo temporal por intentos fallidos (distinto del bloqueo administrativo usuarios.estado).
ALTER TABLE usuarios ADD COLUMN acceso_bloqueado_hasta TIMESTAMPTZ;

-- Prueba de la autorización de tratamiento de datos que exige la ley.
CREATE TABLE aceptaciones_legales (
    usuario_id      BIGINT      NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    documento_id    SMALLINT    NOT NULL REFERENCES documentos_legales(id),
    aceptado_en     TIMESTAMPTZ NOT NULL DEFAULT now(),
    ip_origen       VARCHAR(45),
    plataforma      VARCHAR(10) CHECK (plataforma IN ('android','ios','web')),
    PRIMARY KEY (usuario_id, documento_id)
);
CREATE INDEX ix_aceptaciones_documento ON aceptaciones_legales (documento_id);

-- Códigos SMS de un solo uso. Se guarda el hash, nunca el código plano.
CREATE TABLE codigos_verificacion (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    telefono        VARCHAR(16)  NOT NULL CHECK (telefono ~ '^\+573[0-9]{9}$'),
    codigo_hash     VARCHAR(100) NOT NULL,
    proposito       VARCHAR(20)  NOT NULL DEFAULT 'login'
                    CHECK (proposito IN ('login','registro','cambio_telefono')),
    intentos        SMALLINT     NOT NULL DEFAULT 0 CHECK (intentos BETWEEN 0 AND 20),
    expira_en       TIMESTAMPTZ  NOT NULL,
    usado_en        TIMESTAMPTZ,
    ip_origen       VARCHAR(45),
    creado_en       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (expira_en > creado_en)
);
CREATE INDEX ix_codigos_telefono_fecha ON codigos_verificacion (telefono, creado_en DESC);
CREATE INDEX ix_codigos_ip_fecha       ON codigos_verificacion (ip_origen, creado_en DESC) WHERE ip_origen IS NOT NULL;

CREATE TABLE dispositivos (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id      BIGINT       NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    plataforma      VARCHAR(10)  NOT NULL CHECK (plataforma IN ('android','ios','web')),
    token_push      VARCHAR(300) NOT NULL,
    modelo          VARCHAR(80),
    version_app     VARCHAR(20),
    activo          BOOLEAN      NOT NULL DEFAULT true,
    ultima_conexion TIMESTAMPTZ  NOT NULL DEFAULT now(),
    creado_en       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_dispositivos_token UNIQUE (token_push)
);
CREATE INDEX ix_dispositivos_usuario ON dispositivos (usuario_id) WHERE activo;

-- Refresh tokens del JWT (cerrar sesión y revocar por dispositivo).
CREATE TABLE sesiones (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id      BIGINT       NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    dispositivo_id  BIGINT       REFERENCES dispositivos(id) ON DELETE SET NULL,
    refresh_hash    VARCHAR(100) NOT NULL UNIQUE,
    metodo_acceso   VARCHAR(10)  NOT NULL CHECK (metodo_acceso IN ('sms','google','password')),
    ip_origen       VARCHAR(45),
    expira_en       TIMESTAMPTZ  NOT NULL,
    revocada_en     TIMESTAMPTZ,
    creado_en       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (expira_en > creado_en)
);
CREATE INDEX ix_sesiones_usuario_activas ON sesiones (usuario_id) WHERE revocada_en IS NULL;
CREATE INDEX ix_sesiones_dispositivo     ON sesiones (dispositivo_id) WHERE dispositivo_id IS NOT NULL;

CREATE TABLE direcciones_favoritas (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id      BIGINT           NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    etiqueta        VARCHAR(30)      NOT NULL,
    direccion       VARCHAR(200)     NOT NULL,
    barrio          VARCHAR(80),
    referencia      VARCHAR(200),
    lat             DOUBLE PRECISION NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lng             DOUBLE PRECISION NOT NULL CHECK (lng BETWEEN -180 AND 180),
    creado_en       TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT ux_direcciones_etiqueta UNIQUE (usuario_id, etiqueta)
);

CREATE TABLE contactos_emergencia (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id      BIGINT      NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    nombre          VARCHAR(80) NOT NULL,
    telefono        VARCHAR(16) NOT NULL CHECK (telefono ~ '^\+57(3[0-9]{9}|60[0-9]{8})$'),
    parentesco      VARCHAR(30),
    creado_en       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_contactos_telefono UNIQUE (usuario_id, telefono)
);

-- Un cliente puede bloquear a un conductor (y viceversa): no se vuelven a emparejar.
CREATE TABLE usuarios_bloqueados (
    usuario_id      BIGINT       NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    bloqueado_id    BIGINT       NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    motivo          VARCHAR(200),
    creado_en       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (usuario_id, bloqueado_id),
    CHECK (usuario_id <> bloqueado_id)
);
CREATE INDEX ix_bloqueados_bloqueado ON usuarios_bloqueados (bloqueado_id);


-- =====================================================================
-- 3. CONDUCTORES, UBICACIÓN, VEHÍCULOS Y DOCUMENTOS
-- =====================================================================

CREATE TABLE conductores (
    usuario_id               BIGINT       PRIMARY KEY REFERENCES usuarios(id) ON DELETE CASCADE,
    tipo_identificacion      VARCHAR(5)   NOT NULL CHECK (tipo_identificacion IN ('CC','CE','PPT')),
    numero_identificacion    VARCHAR(20)  NOT NULL CHECK (numero_identificacion ~ '^[0-9A-Z]{4,20}$'),
    fecha_nacimiento         DATE         NOT NULL,
    categorias_licencia      VARCHAR(2)[] NOT NULL,
    estado                   VARCHAR(20)  NOT NULL DEFAULT 'pendiente',
    estado_ambito            VARCHAR(20)  NOT NULL GENERATED ALWAYS AS ('conductor') STORED,
    motivo_estado            VARCHAR(300),
    verificado_por           BIGINT       REFERENCES usuarios(id),
    verificado_en            TIMESTAMPTZ,
    disponible               BOOLEAN      NOT NULL DEFAULT false,
    calificacion_promedio    NUMERIC(3,2) NOT NULL DEFAULT 5.00 CHECK (calificacion_promedio BETWEEN 1 AND 5),
    total_calificaciones     INTEGER      NOT NULL DEFAULT 0 CHECK (total_calificaciones >= 0),
    total_servicios          INTEGER      NOT NULL DEFAULT 0 CHECK (total_servicios >= 0),
    total_cancelaciones      INTEGER      NOT NULL DEFAULT 0 CHECK (total_cancelaciones >= 0),
    creado_en                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actualizado_en           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_conductores_identificacion UNIQUE (tipo_identificacion, numero_identificacion),
    CONSTRAINT ck_conductores_categorias
        CHECK (cardinality(categorias_licencia) > 0
               AND categorias_licencia <@ ARRAY['A1','A2','B1','B2','B3','C1','C2','C3']::VARCHAR(2)[]),
    CONSTRAINT ck_conductores_disponible_aprobado
        CHECK (NOT disponible OR estado = 'aprobado'),
    CONSTRAINT ck_conductores_rechazo_motivo
        CHECK (estado NOT IN ('rechazado','suspendido') OR motivo_estado IS NOT NULL)
);
CREATE INDEX ix_conductores_estado ON conductores (estado, creado_en);
CREATE TRIGGER trg_conductores_actualizado
    BEFORE UPDATE ON conductores FOR EACH ROW EXECUTE FUNCTION fn_set_actualizado_en();

-- Ubicación en vivo separada de conductores: se actualiza cada pocos segundos,
-- así el bloat de esas escrituras queda en una tabla pequeña y no en el perfil.
-- Escritura recomendada: INSERT ... ON CONFLICT (conductor_id) DO UPDATE.
CREATE TABLE ubicacion_conductores (
    conductor_id    BIGINT           PRIMARY KEY REFERENCES conductores(usuario_id) ON DELETE CASCADE,
    lat             DOUBLE PRECISION NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lng             DOUBLE PRECISION NOT NULL CHECK (lng BETWEEN -180 AND 180),
    rumbo_grados    SMALLINT         CHECK (rumbo_grados BETWEEN 0 AND 359),
    velocidad_kmh   NUMERIC(5,1)     CHECK (velocidad_kmh >= 0),
    precision_m     NUMERIC(6,1)     CHECK (precision_m >= 0),
    actualizado_en  TIMESTAMPTZ      NOT NULL DEFAULT now()
) WITH (fillfactor = 70, autovacuum_vacuum_scale_factor = 0.05);
CREATE INDEX ix_ubicacion_conductores_geo ON ubicacion_conductores (lat, lng);

-- Turnos en línea del conductor (horas conectado, métricas de oferta).
CREATE TABLE conductor_conexiones (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conductor_id    BIGINT      NOT NULL REFERENCES conductores(usuario_id) ON DELETE CASCADE,
    inicio          TIMESTAMPTZ NOT NULL DEFAULT now(),
    fin             TIMESTAMPTZ,
    CHECK (fin IS NULL OR fin >= inicio)
);
CREATE UNIQUE INDEX ux_conexiones_abierta ON conductor_conexiones (conductor_id) WHERE fin IS NULL;
CREATE INDEX ix_conexiones_conductor ON conductor_conexiones (conductor_id, inicio DESC);

CREATE TABLE vehiculos (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conductor_id        BIGINT      NOT NULL REFERENCES conductores(usuario_id) ON DELETE CASCADE,
    tipo_vehiculo       VARCHAR(10) NOT NULL REFERENCES tipos_vehiculo(codigo),
    placa               VARCHAR(7)  NOT NULL,
    marca               VARCHAR(40) NOT NULL,
    linea               VARCHAR(40),
    modelo_anio         SMALLINT    NOT NULL CHECK (modelo_anio BETWEEN 1990 AND 2100),
    color               VARCHAR(30) NOT NULL,
    empresa_afiliadora  VARCHAR(80),
    numero_interno      VARCHAR(10),
    activo              BOOLEAN     NOT NULL DEFAULT true,
    creado_en           TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_en      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_vehiculos_placa UNIQUE (placa),
    -- Clave alterna: permite que servicios valide conductor + vehículo + tipo con una sola FK.
    CONSTRAINT ux_vehiculos_id_conductor_tipo UNIQUE (id, conductor_id, tipo_vehiculo),
    CONSTRAINT ck_vehiculos_placa CHECK (
        (tipo_vehiculo = 'taxi' AND placa ~ '^[A-Z]{3}[0-9]{3}$') OR
        (tipo_vehiculo = 'moto' AND placa ~ '^[A-Z]{3}[0-9]{2}[A-Z]$')),
    CONSTRAINT ck_vehiculos_taxi_empresa
        CHECK (tipo_vehiculo <> 'taxi' OR empresa_afiliadora IS NOT NULL)
);
CREATE UNIQUE INDEX ux_vehiculos_activo_por_conductor ON vehiculos (conductor_id) WHERE activo;
CREATE TRIGGER trg_vehiculos_actualizado
    BEFORE UPDATE ON vehiculos FOR EACH ROW EXECUTE FUNCTION fn_set_actualizado_en();

-- Documentos con historial: al cargar uno nuevo, el anterior pasa a vigente = false (trigger).
CREATE TABLE documentos_conductor (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conductor_id        BIGINT       NOT NULL REFERENCES conductores(usuario_id) ON DELETE CASCADE,
    vehiculo_id         BIGINT       REFERENCES vehiculos(id) ON DELETE CASCADE,
    tipo_documento      VARCHAR(30)  NOT NULL REFERENCES tipos_documento(codigo),
    ruta_archivo        VARCHAR(300) NOT NULL,
    nombre_original     VARCHAR(150),
    mime_type           VARCHAR(60)  NOT NULL
                        CHECK (mime_type IN ('image/jpeg','image/png','image/webp','application/pdf')),
    tamano_bytes        INTEGER      NOT NULL CHECK (tamano_bytes BETWEEN 1 AND 10485760),
    fecha_vencimiento   DATE,
    estado              VARCHAR(20)  NOT NULL DEFAULT 'pendiente',
    estado_ambito       VARCHAR(20)  NOT NULL GENERATED ALWAYS AS ('documento') STORED,
    observacion         VARCHAR(300),
    revisado_por        BIGINT       REFERENCES usuarios(id),
    revisado_en         TIMESTAMPTZ,
    vigente             BOOLEAN      NOT NULL DEFAULT true,
    creado_en           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_documentos_rechazo CHECK (estado <> 'rechazado' OR observacion IS NOT NULL)
);
CREATE UNIQUE INDEX ux_documentos_vigente
    ON documentos_conductor (conductor_id, tipo_documento, COALESCE(vehiculo_id, 0)) WHERE vigente;
CREATE INDEX ix_documentos_vehiculo ON documentos_conductor (vehiculo_id) WHERE vehiculo_id IS NOT NULL;
CREATE INDEX ix_documentos_vencimiento
    ON documentos_conductor (fecha_vencimiento) WHERE vigente AND fecha_vencimiento IS NOT NULL;
CREATE INDEX ix_documentos_pendientes ON documentos_conductor (creado_en) WHERE vigente AND estado = 'pendiente';


-- =====================================================================
-- 4. TARIFAS (sin pagos en línea: solo valor estimado / final en efectivo)
-- =====================================================================

-- Los valores de una tarifa no se editan: se crea una nueva y se desactiva la anterior,
-- así cada servicio conserva la tarifa con la que se cobró.
CREATE TABLE tarifas (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tipo_servicio         VARCHAR(20)   NOT NULL,
    tipo_vehiculo         VARCHAR(10)   NOT NULL,
    tarifa_base           NUMERIC(10,2) NOT NULL CHECK (tarifa_base   >= 0),
    valor_km              NUMERIC(10,2) NOT NULL CHECK (valor_km      >= 0),
    valor_minuto          NUMERIC(10,2) NOT NULL DEFAULT 0 CHECK (valor_minuto >= 0),
    tarifa_minima         NUMERIC(10,2) NOT NULL CHECK (tarifa_minima >= 0),
    recargo_nocturno_pct  NUMERIC(5,2)  NOT NULL DEFAULT 0 CHECK (recargo_nocturno_pct BETWEEN 0 AND 100),
    vigente_desde         DATE          NOT NULL DEFAULT CURRENT_DATE,
    activo                BOOLEAN       NOT NULL DEFAULT true,
    creado_por            BIGINT        REFERENCES usuarios(id),
    creado_en             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_tarifas_combinacion FOREIGN KEY (tipo_servicio, tipo_vehiculo)
        REFERENCES servicio_vehiculo_permitido (tipo_servicio, tipo_vehiculo),
    CONSTRAINT ux_tarifas_id_combinacion UNIQUE (id, tipo_servicio, tipo_vehiculo)
);
CREATE UNIQUE INDEX ux_tarifas_activa ON tarifas (tipo_servicio, tipo_vehiculo) WHERE activo;

CREATE OR REPLACE FUNCTION fn_tarifas_inmutables()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.tarifa_base, NEW.valor_km, NEW.valor_minuto, NEW.tarifa_minima, NEW.recargo_nocturno_pct,
        NEW.tipo_servicio, NEW.tipo_vehiculo)
       IS DISTINCT FROM
       (OLD.tarifa_base, OLD.valor_km, OLD.valor_minuto, OLD.tarifa_minima, OLD.recargo_nocturno_pct,
        OLD.tipo_servicio, OLD.tipo_vehiculo) THEN
        RAISE EXCEPTION 'Los valores de una tarifa no se modifican: crea una nueva y desactiva la anterior'
              USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_tarifas_inmutables
    BEFORE UPDATE ON tarifas FOR EACH ROW EXECUTE FUNCTION fn_tarifas_inmutables();


-- =====================================================================
-- 5. SERVICIOS (supertipo) + DETALLE POR TIPO (subtipos)
-- =====================================================================

CREATE TABLE servicios (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tipo_servicio         VARCHAR(20)   NOT NULL,
    tipo_vehiculo         VARCHAR(10)   NOT NULL,
    cliente_id            BIGINT        NOT NULL REFERENCES usuarios(id),
    conductor_id          BIGINT        REFERENCES conductores(usuario_id),
    vehiculo_id           BIGINT,
    estado                VARCHAR(20)  NOT NULL DEFAULT 'buscando',
    estado_ambito         VARCHAR(20)  NOT NULL GENERATED ALWAYS AS ('servicio') STORED,

    origen_direccion      VARCHAR(200)     NOT NULL,
    origen_barrio         VARCHAR(80),
    origen_referencia     VARCHAR(200),
    origen_lat            DOUBLE PRECISION NOT NULL CHECK (origen_lat  BETWEEN -90  AND 90),
    origen_lng            DOUBLE PRECISION NOT NULL CHECK (origen_lng  BETWEEN -180 AND 180),
    destino_direccion     VARCHAR(200)     NOT NULL,
    destino_barrio        VARCHAR(80),
    destino_referencia    VARCHAR(200),
    destino_lat           DOUBLE PRECISION NOT NULL CHECK (destino_lat BETWEEN -90  AND 90),
    destino_lng           DOUBLE PRECISION NOT NULL CHECK (destino_lng BETWEEN -180 AND 180),

    distancia_km          NUMERIC(7,2)  CHECK (distancia_km >= 0),
    duracion_estimada_min INTEGER       CHECK (duracion_estimada_min >= 0),
    tarifa_id             BIGINT,
    valor_estimado        NUMERIC(12,2) CHECK (valor_estimado >= 0),
    valor_final           NUMERIC(12,2) CHECK (valor_final    >= 0),
    metodo_pago           VARCHAR(15)   NOT NULL DEFAULT 'efectivo' CHECK (metodo_pago IN ('efectivo')),
    pasajeros             SMALLINT      NOT NULL DEFAULT 1 CHECK (pasajeros BETWEEN 1 AND 8),
    notas_cliente         VARCHAR(300),

    motivo_cancelacion_id SMALLINT      REFERENCES motivos_cancelacion(id),
    cancelado_por         BIGINT        REFERENCES usuarios(id),
    detalle_cancelacion   VARCHAR(300),

    solicitado_en         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expira_busqueda_en    TIMESTAMPTZ,
    asignado_en           TIMESTAMPTZ,
    llegada_origen_en     TIMESTAMPTZ,
    iniciado_en           TIMESTAMPTZ,
    finalizado_en         TIMESTAMPTZ,
    cancelado_en          TIMESTAMPTZ,
    actualizado_en        TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ux_servicios_id_tipo UNIQUE (id, tipo_servicio),
    CONSTRAINT fk_servicios_combinacion FOREIGN KEY (tipo_servicio, tipo_vehiculo)
        REFERENCES servicio_vehiculo_permitido (tipo_servicio, tipo_vehiculo),
    -- El vehículo debe ser del conductor asignado y del tipo que pidió el cliente.
    CONSTRAINT fk_servicios_vehiculo_conductor FOREIGN KEY (vehiculo_id, conductor_id, tipo_vehiculo)
        REFERENCES vehiculos (id, conductor_id, tipo_vehiculo),
    -- La tarifa debe ser del mismo servicio y vehículo.
    CONSTRAINT fk_servicios_tarifa FOREIGN KEY (tarifa_id, tipo_servicio, tipo_vehiculo)
        REFERENCES tarifas (id, tipo_servicio, tipo_vehiculo),
    CONSTRAINT ck_servicios_cliente_distinto  CHECK (conductor_id IS NULL OR conductor_id <> cliente_id),
    CONSTRAINT ck_servicios_moto_un_pasajero  CHECK (tipo_vehiculo <> 'moto' OR pasajeros = 1),
    CONSTRAINT ck_servicios_pasajeros_solo_transporte CHECK (tipo_servicio = 'transporte' OR pasajeros = 1),
    CONSTRAINT ck_servicios_origen_destino
        CHECK (NOT (origen_lat = destino_lat AND origen_lng = destino_lng)),
    CONSTRAINT ck_servicios_con_conductor
        CHECK (estado NOT IN ('asignado','en_origen','en_curso','finalizado')
               OR (conductor_id IS NOT NULL AND vehiculo_id IS NOT NULL)),
    CONSTRAINT ck_servicios_sin_conductor
        CHECK (estado NOT IN ('buscando','expirado') OR (conductor_id IS NULL AND vehiculo_id IS NULL)),
    CONSTRAINT ck_servicios_cancelacion
        CHECK (estado <> 'cancelado' OR motivo_cancelacion_id IS NOT NULL),
    CONSTRAINT ck_servicios_finalizado_valor
        CHECK (estado <> 'finalizado' OR valor_final IS NOT NULL),
    CONSTRAINT ck_servicios_fechas CHECK (
        (asignado_en   IS NULL OR asignado_en   >= solicitado_en) AND
        (iniciado_en   IS NULL OR asignado_en   IS NULL OR iniciado_en   >= asignado_en) AND
        (finalizado_en IS NULL OR iniciado_en   IS NULL OR finalizado_en >= iniciado_en))
);

-- Un conductor no puede tener dos servicios activos (y bloquea la doble aceptación).
CREATE UNIQUE INDEX ux_servicios_un_activo_por_conductor
    ON servicios (conductor_id) WHERE estado IN ('asignado','en_origen','en_curso');
-- Un cliente no puede pedir dos viajes de transporte a la vez (domicilios sí puede).
CREATE UNIQUE INDEX ux_servicios_transporte_activo_por_cliente
    ON servicios (cliente_id)
    WHERE tipo_servicio = 'transporte' AND estado IN ('buscando','asignado','en_origen','en_curso');
CREATE INDEX ix_servicios_buscando
    ON servicios (tipo_vehiculo, origen_lat, origen_lng) WHERE estado = 'buscando';
CREATE INDEX ix_servicios_expiracion ON servicios (expira_busqueda_en) WHERE estado = 'buscando';
CREATE INDEX ix_servicios_cliente    ON servicios (cliente_id,   solicitado_en DESC);
CREATE INDEX ix_servicios_conductor  ON servicios (conductor_id, solicitado_en DESC) WHERE conductor_id IS NOT NULL;
CREATE INDEX ix_servicios_vehiculo   ON servicios (vehiculo_id) WHERE vehiculo_id IS NOT NULL;
CREATE INDEX ix_servicios_estado     ON servicios (estado, solicitado_en DESC);
CREATE INDEX brin_servicios_fecha   ON servicios USING brin (solicitado_en);

CREATE TABLE servicio_domicilio (
    servicio_id           BIGINT        PRIMARY KEY,
    tipo_servicio         VARCHAR(20)   NOT NULL DEFAULT 'domicilio' CHECK (tipo_servicio = 'domicilio'),
    establecimiento       VARCHAR(120),
    descripcion_pedido    VARCHAR(500)  NOT NULL,
    requiere_compra       BOOLEAN       NOT NULL DEFAULT false,
    valor_productos_aprox NUMERIC(12,2) CHECK (valor_productos_aprox >= 0),
    valor_compra_real     NUMERIC(12,2) CHECK (valor_compra_real >= 0),   -- lo que pagó el conductor
    telefono_contacto     VARCHAR(16)   CHECK (telefono_contacto IS NULL OR telefono_contacto ~ '^\+57(3[0-9]{9}|60[0-9]{8})$'),
    FOREIGN KEY (servicio_id, tipo_servicio) REFERENCES servicios (id, tipo_servicio) ON DELETE CASCADE,
    CHECK (NOT requiere_compra OR valor_productos_aprox IS NOT NULL)
);

CREATE TABLE servicio_domicilio_items (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    servicio_id           BIGINT        NOT NULL REFERENCES servicio_domicilio(servicio_id) ON DELETE CASCADE,
    descripcion           VARCHAR(150)  NOT NULL,
    cantidad              SMALLINT      NOT NULL DEFAULT 1 CHECK (cantidad BETWEEN 1 AND 99),
    precio_unitario_aprox NUMERIC(12,2) CHECK (precio_unitario_aprox >= 0),
    notas                 VARCHAR(200)
);
CREATE INDEX ix_domicilio_items_servicio ON servicio_domicilio_items (servicio_id);

CREATE TABLE servicio_encomienda (
    servicio_id           BIGINT        PRIMARY KEY,
    tipo_servicio         VARCHAR(20)   NOT NULL DEFAULT 'encomienda' CHECK (tipo_servicio = 'encomienda'),
    remitente_nombre      VARCHAR(120)  NOT NULL,
    remitente_telefono    VARCHAR(16)   NOT NULL CHECK (remitente_telefono    ~ '^\+57(3[0-9]{9}|60[0-9]{8})$'),
    destinatario_nombre   VARCHAR(120)  NOT NULL,
    destinatario_telefono VARCHAR(16)   NOT NULL CHECK (destinatario_telefono ~ '^\+57(3[0-9]{9}|60[0-9]{8})$'),
    descripcion_paquete   VARCHAR(300)  NOT NULL,
    tamano                VARCHAR(10)   NOT NULL CHECK (tamano IN ('pequeno','mediano','grande')),
    peso_kg               NUMERIC(5,2)  NOT NULL CHECK (peso_kg > 0 AND peso_kg <= 30),
    fragil                BOOLEAN       NOT NULL DEFAULT false,
    valor_declarado       NUMERIC(12,2) CHECK (valor_declarado >= 0),
    codigo_entrega        CHAR(4)       NOT NULL CHECK (codigo_entrega ~ '^[0-9]{4}$'),
    entregado_a           VARCHAR(120),
    FOREIGN KEY (servicio_id, tipo_servicio) REFERENCES servicios (id, tipo_servicio) ON DELETE CASCADE
);

-- Fotos de recogida, entrega, factura de compra o incidente.
CREATE TABLE evidencias_servicio (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    servicio_id     BIGINT       NOT NULL REFERENCES servicios(id) ON DELETE CASCADE,
    tipo            VARCHAR(20)  NOT NULL CHECK (tipo IN ('recogida','entrega','factura_compra','incidente')),
    ruta_archivo    VARCHAR(300) NOT NULL,
    mime_type       VARCHAR(60)  NOT NULL CHECK (mime_type IN ('image/jpeg','image/png','image/webp','application/pdf')),
    subido_por      BIGINT       NOT NULL REFERENCES usuarios(id),
    creado_en       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_evidencias_servicio ON evidencias_servicio (servicio_id);

-- Chat cliente ↔ conductor durante el servicio.
CREATE TABLE mensajes_servicio (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    servicio_id     BIGINT       NOT NULL REFERENCES servicios(id) ON DELETE CASCADE,
    remitente_id    BIGINT       NOT NULL REFERENCES usuarios(id),
    contenido       VARCHAR(500) NOT NULL CHECK (length(trim(contenido)) > 0),
    leido_en        TIMESTAMPTZ,
    creado_en       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_mensajes_servicio ON mensajes_servicio (servicio_id, creado_en);

-- Historial de cambios de estado de TODOS los ámbitos (lo llena un trigger genérico).
CREATE TABLE historial_estados (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ambito            VARCHAR(20)  NOT NULL,
    registro_id       BIGINT       NOT NULL,          -- id del usuario, servicio, documento...
    estado_anterior   VARCHAR(20),
    estado_nuevo      VARCHAR(20)  NOT NULL,
    actor_usuario_id  BIGINT       REFERENCES usuarios(id),
    actor_rol         VARCHAR(20)  NOT NULL DEFAULT 'sistema',
    motivo            VARCHAR(300),
    detalle           JSONB,                          -- ej. conductor que tenía el servicio
    creado_en         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    FOREIGN KEY (ambito, estado_nuevo) REFERENCES estados (ambito, codigo)
);
CREATE INDEX ix_historial_registro ON historial_estados (ambito, registro_id, creado_en);
CREATE INDEX brin_historial_fecha  ON historial_estados USING brin (creado_en);

CREATE TABLE servicio_ofertas (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    servicio_id     BIGINT       NOT NULL REFERENCES servicios(id) ON DELETE CASCADE,
    conductor_id    BIGINT       NOT NULL REFERENCES conductores(usuario_id),
    distancia_km    NUMERIC(7,2) NOT NULL CHECK (distancia_km >= 0),
    estado          VARCHAR(20)  NOT NULL DEFAULT 'enviada',
    estado_ambito   VARCHAR(20)  NOT NULL GENERATED ALWAYS AS ('oferta') STORED,
    enviada_en      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    respondida_en   TIMESTAMPTZ,
    CONSTRAINT ux_ofertas_servicio_conductor UNIQUE (servicio_id, conductor_id)
);
CREATE INDEX ix_ofertas_conductor_pendientes ON servicio_ofertas (conductor_id) WHERE estado IN ('enviada','vista');

-- Rastro GPS durante el servicio (alto volumen, solo inserciones; se purga con fn_purgar_datos).
CREATE TABLE seguimiento_ubicaciones (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    servicio_id     BIGINT           NOT NULL REFERENCES servicios(id) ON DELETE CASCADE,
    conductor_id    BIGINT           NOT NULL REFERENCES conductores(usuario_id),
    lat             DOUBLE PRECISION NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lng             DOUBLE PRECISION NOT NULL CHECK (lng BETWEEN -180 AND 180),
    velocidad_kmh   NUMERIC(5,1)     CHECK (velocidad_kmh >= 0),
    rumbo_grados    SMALLINT         CHECK (rumbo_grados BETWEEN 0 AND 359),
    precision_m     NUMERIC(6,1)     CHECK (precision_m >= 0),
    registrado_en   TIMESTAMPTZ      NOT NULL DEFAULT now()
);
CREATE INDEX ix_seguimiento_servicio ON seguimiento_ubicaciones (servicio_id, registrado_en DESC);
CREATE INDEX brin_seguimiento_fecha  ON seguimiento_ubicaciones USING brin (registrado_en);

CREATE TABLE calificaciones (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    servicio_id     BIGINT       NOT NULL REFERENCES servicios(id) ON DELETE CASCADE,
    calificador_id  BIGINT       NOT NULL REFERENCES usuarios(id),
    calificado_id   BIGINT       NOT NULL REFERENCES usuarios(id),
    puntuacion      SMALLINT     NOT NULL CHECK (puntuacion BETWEEN 1 AND 5),
    comentario      VARCHAR(300),
    creado_en       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_calificaciones_una_por_servicio UNIQUE (servicio_id, calificador_id),
    CHECK (calificador_id <> calificado_id)
);
CREATE INDEX ix_calificaciones_calificado ON calificaciones (calificado_id, creado_en DESC);


-- =====================================================================
-- 6. SEGURIDAD, SOPORTE (PQRS), NOTIFICACIONES Y AUDITORÍA
-- =====================================================================

CREATE TABLE alertas_sos (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id      BIGINT           NOT NULL REFERENCES usuarios(id),
    servicio_id     BIGINT           REFERENCES servicios(id),
    lat             DOUBLE PRECISION CHECK (lat BETWEEN -90 AND 90),
    lng             DOUBLE PRECISION CHECK (lng BETWEEN -180 AND 180),
    estado          VARCHAR(20)  NOT NULL DEFAULT 'abierta',
    estado_ambito   VARCHAR(20)  NOT NULL GENERATED ALWAYS AS ('alerta_sos') STORED,
    atendida_por    BIGINT           REFERENCES usuarios(id),
    atendida_en     TIMESTAMPTZ,
    notas           VARCHAR(500),
    creado_en       TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CHECK (estado IN ('abierta','en_atencion') OR atendida_en IS NOT NULL)
);
CREATE INDEX ix_alertas_abiertas ON alertas_sos (creado_en) WHERE estado IN ('abierta','en_atencion');
CREATE INDEX ix_alertas_servicio ON alertas_sos (servicio_id) WHERE servicio_id IS NOT NULL;

-- Peticiones, quejas, reclamos y sugerencias (Ley 1755 de 2015: 15 días hábiles ≈ 21 calendario).
CREATE TABLE tickets_soporte (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id              BIGINT        NOT NULL REFERENCES usuarios(id),
    servicio_id             BIGINT        REFERENCES servicios(id),
    tipo                    VARCHAR(25)   NOT NULL CHECK (tipo IN ('peticion','queja','reclamo','sugerencia',
                                                     'objeto_olvidado','cobro_indebido','incidente_seguridad')),
    asunto                  VARCHAR(120)  NOT NULL,
    descripcion             VARCHAR(2000) NOT NULL,
    estado                  VARCHAR(20)  NOT NULL DEFAULT 'abierto',
    estado_ambito           VARCHAR(20)  NOT NULL GENERATED ALWAYS AS ('ticket') STORED,
    prioridad               VARCHAR(10)   NOT NULL DEFAULT 'media'
                            CHECK (prioridad IN ('baja','media','alta','urgente')),
    asignado_a              BIGINT        REFERENCES usuarios(id),
    respuesta               VARCHAR(2000),
    fecha_limite_respuesta  DATE          NOT NULL DEFAULT (CURRENT_DATE + 21),
    creado_en               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    actualizado_en          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    resuelto_en             TIMESTAMPTZ,
    CHECK (estado NOT IN ('resuelto','cerrado') OR respuesta IS NOT NULL)
);
CREATE INDEX ix_tickets_abiertos ON tickets_soporte (prioridad, fecha_limite_respuesta) WHERE estado IN ('abierto','en_proceso');
CREATE INDEX ix_tickets_usuario  ON tickets_soporte (usuario_id, creado_en DESC);
CREATE INDEX ix_tickets_servicio ON tickets_soporte (servicio_id) WHERE servicio_id IS NOT NULL;

CREATE TABLE notificaciones (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id      BIGINT       NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    servicio_id     BIGINT       REFERENCES servicios(id) ON DELETE SET NULL,
    tipo            VARCHAR(30)  NOT NULL CHECK (tipo IN ('nueva_solicitud','estado_servicio','mensaje',
                                         'verificacion','documento_por_vencer','soporte','sos','sistema')),
    titulo          VARCHAR(100) NOT NULL,
    cuerpo          VARCHAR(300) NOT NULL,
    datos           JSONB,
    enviada_push    BOOLEAN      NOT NULL DEFAULT false,
    leida_en        TIMESTAMPTZ,
    creado_en       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_notificaciones_usuario   ON notificaciones (usuario_id, creado_en DESC);
CREATE INDEX ix_notificaciones_no_leidas ON notificaciones (usuario_id) WHERE leida_en IS NULL;

-- Acciones explícitas del administrador (aprobar, bloquear, responder PQRS...).
CREATE TABLE auditoria_admin (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    admin_id        BIGINT       NOT NULL REFERENCES usuarios(id),
    accion          VARCHAR(40)  NOT NULL,
    entidad         VARCHAR(40)  NOT NULL,
    entidad_id      BIGINT       NOT NULL,
    detalle         JSONB,
    creado_en       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_auditoria_admin_entidad ON auditoria_admin (entidad, entidad_id);
CREATE INDEX ix_auditoria_admin_admin   ON auditoria_admin (admin_id, creado_en DESC);

-- Auditoría automática (triggers) de cambios sensibles: roles, estados, tarifas, vehículos, configuración.
CREATE TABLE auditoria_cambios (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tabla           VARCHAR(60)  NOT NULL,
    operacion       CHAR(1)      NOT NULL CHECK (operacion IN ('I','U','D')),
    registro_id     VARCHAR(60)  NOT NULL,
    datos_antes     JSONB,
    datos_despues   JSONB,
    usuario_id      BIGINT,
    actor_rol       VARCHAR(20),
    creado_en       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_auditoria_cambios_registro ON auditoria_cambios (tabla, registro_id, creado_en DESC);
CREATE INDEX brin_auditoria_cambios_fecha  ON auditoria_cambios USING brin (creado_en);


-- =====================================================================
-- 6B. CELDA DE ESTADOS: amarre de cada tabla al catálogo y máquina de estados genérica
-- =====================================================================

ALTER TABLE usuarios             ADD CONSTRAINT fk_usuarios_estado    FOREIGN KEY (estado_ambito, estado) REFERENCES estados (ambito, codigo);
ALTER TABLE conductores          ADD CONSTRAINT fk_conductores_estado FOREIGN KEY (estado_ambito, estado) REFERENCES estados (ambito, codigo);
ALTER TABLE documentos_conductor ADD CONSTRAINT fk_documentos_estado  FOREIGN KEY (estado_ambito, estado) REFERENCES estados (ambito, codigo);
ALTER TABLE servicios            ADD CONSTRAINT fk_servicios_estado   FOREIGN KEY (estado_ambito, estado) REFERENCES estados (ambito, codigo);
ALTER TABLE servicio_ofertas     ADD CONSTRAINT fk_ofertas_estado     FOREIGN KEY (estado_ambito, estado) REFERENCES estados (ambito, codigo);
ALTER TABLE alertas_sos          ADD CONSTRAINT fk_alertas_estado     FOREIGN KEY (estado_ambito, estado) REFERENCES estados (ambito, codigo);
ALTER TABLE tickets_soporte      ADD CONSTRAINT fk_tickets_estado     FOREIGN KEY (estado_ambito, estado) REFERENCES estados (ambito, codigo);

-- Valida que un registro nuevo nazca en el estado inicial de su ámbito y que cada cambio
-- esté en transiciones_estado para el actor declarado. Uso: EXECUTE FUNCTION fn_estado_validar('ambito').
CREATE OR REPLACE FUNCTION fn_estado_validar()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_ambito VARCHAR(20) := TG_ARGV[0];
    v_actor  VARCHAR(20) := fn_actor_rol();
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT 1 FROM estados WHERE ambito = v_ambito AND codigo = NEW.estado AND es_inicial) THEN
            RAISE EXCEPTION 'Un registro nuevo de % debe iniciar en su estado inicial (recibido: %)', v_ambito, NEW.estado
                  USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.estado = OLD.estado THEN
        RETURN NEW;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM transiciones_estado t
                    WHERE t.ambito = v_ambito AND t.estado_origen = OLD.estado AND t.estado_destino = NEW.estado
                      AND (v_actor IS NULL OR t.actor = v_actor)) THEN
        RAISE EXCEPTION 'Cambio de estado no permitido en %: % -> % (actor: %)', v_ambito, OLD.estado, NEW.estado,
              COALESCE(v_actor, 'sin definir') USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

-- Registra en historial_estados cada estado inicial y cada cambio.
-- Uso: EXECUTE FUNCTION fn_estado_historial('ambito', 'columna_pk').
CREATE OR REPLACE FUNCTION fn_estado_historial()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_ambito VARCHAR(20) := TG_ARGV[0];
    jn       JSONB := to_jsonb(NEW);
    jo       JSONB := CASE WHEN TG_OP = 'UPDATE' THEN to_jsonb(OLD) END;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.estado IS NOT DISTINCT FROM OLD.estado THEN
        RETURN NULL;
    END IF;
    INSERT INTO historial_estados (ambito, registro_id, estado_anterior, estado_nuevo,
                                   actor_usuario_id, actor_rol, motivo, detalle)
    VALUES (v_ambito,
            (jn->>TG_ARGV[1])::BIGINT,
            jo->>'estado',
            NEW.estado,
            COALESCE(fn_actor_usuario(),
                     CASE WHEN TG_OP = 'INSERT' AND v_ambito = 'servicio' THEN (jn->>'cliente_id')::BIGINT END),
            COALESCE(fn_actor_rol(),
                     CASE WHEN TG_OP = 'INSERT' AND v_ambito = 'servicio' THEN 'cliente' ELSE 'sistema' END),
            COALESCE(jn->>'motivo_estado', jn->>'motivo_bloqueo', jn->>'observacion', jn->>'detalle_cancelacion'),
            CASE WHEN v_ambito = 'servicio' THEN jsonb_strip_nulls(jsonb_build_object(
                     'conductor_id', COALESCE(NULLIF(jn->'conductor_id', 'null'), NULLIF(jo->'conductor_id', 'null')),
                     'motivo_cancelacion_id', NULLIF(jn->'motivo_cancelacion_id', 'null'))) END);
    RETURN NULL;
END $$;

-- =====================================================================
-- 7. FUNCIONES DE NEGOCIO
-- =====================================================================

-- Precio de un servicio con una tarifa concreta (recargo nocturno en hora de Colombia).
CREATE OR REPLACE FUNCTION fn_calcular_tarifa(
    p_tarifa_id BIGINT, p_km NUMERIC, p_min NUMERIC, p_momento TIMESTAMPTZ DEFAULT now())
RETURNS NUMERIC LANGUAGE plpgsql STABLE AS $$
DECLARE
    t       tarifas%ROWTYPE;
    v_valor NUMERIC;
    v_hora  INTEGER;
BEGIN
    SELECT * INTO t FROM tarifas WHERE id = p_tarifa_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Tarifa % no existe', p_tarifa_id USING ERRCODE = 'no_data_found';
    END IF;

    v_valor := t.tarifa_base + COALESCE(p_km, 0) * t.valor_km + COALESCE(p_min, 0) * t.valor_minuto;
    v_hora  := EXTRACT(HOUR FROM p_momento AT TIME ZONE 'America/Bogota');
    IF v_hora >= fn_config_num('hora_inicio_nocturno', 21) OR v_hora < fn_config_num('hora_fin_nocturno', 5) THEN
        v_valor := v_valor * (1 + t.recargo_nocturno_pct / 100);
    END IF;
    v_valor := GREATEST(v_valor, t.tarifa_minima);
    RETURN ceil(v_valor / 100) * 100;                 -- redondeo a la centena (efectivo)
END $$;

-- Cotización antes de pedir (la usa la pantalla "Solicitar ...").
CREATE OR REPLACE FUNCTION fn_cotizar(
    p_tipo_servicio VARCHAR, p_tipo_vehiculo VARCHAR,
    p_km NUMERIC, p_min NUMERIC DEFAULT 0)
RETURNS NUMERIC LANGUAGE plpgsql STABLE AS $$
DECLARE
    v_tarifa BIGINT;
BEGIN
    SELECT id INTO v_tarifa FROM tarifas
     WHERE tipo_servicio = p_tipo_servicio AND tipo_vehiculo = p_tipo_vehiculo AND activo;
    IF v_tarifa IS NULL THEN
        RAISE EXCEPTION 'No hay tarifa activa para % en %', p_tipo_servicio, p_tipo_vehiculo
              USING ERRCODE = 'no_data_found';
    END IF;
    RETURN fn_calcular_tarifa(v_tarifa, p_km, p_min, now());
END $$;


-- =====================================================================
-- 8. TRIGGERS DE NEGOCIO
-- =====================================================================

-- 8.1 Conductores: mayoría de edad, aprobación con documentos completos,
--     desconexión automática al rechazar/suspender, conexión solo con ubicación reciente.
CREATE OR REPLACE FUNCTION fn_conductores_validar()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_tipo       VARCHAR(10);
    v_vehiculo   BIGINT;
    v_faltantes  TEXT;
BEGIN
    IF TG_OP = 'INSERT' OR NEW.fecha_nacimiento IS DISTINCT FROM OLD.fecha_nacimiento THEN
        IF NEW.fecha_nacimiento > CURRENT_DATE - INTERVAL '18 years' THEN
            RAISE EXCEPTION 'El conductor debe ser mayor de edad' USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    IF TG_OP = 'INSERT' THEN
        NEW.estado := 'pendiente';
        NEW.disponible := false;
        RETURN NEW;
    END IF;

    IF NEW.estado IS DISTINCT FROM OLD.estado THEN
        NEW.verificado_en  := now();
        NEW.verificado_por := COALESCE(fn_actor_usuario(), NEW.verificado_por);

        IF NEW.estado <> 'aprobado' THEN
            NEW.disponible := false;
        ELSE
            IF NOT EXISTS (SELECT 1 FROM usuarios WHERE id = NEW.usuario_id AND telefono_verificado) THEN
                RAISE EXCEPTION 'No se puede aprobar: el conductor no ha verificado su celular'
                      USING ERRCODE = 'check_violation';
            END IF;
            SELECT id, tipo_vehiculo INTO v_vehiculo, v_tipo
              FROM vehiculos WHERE conductor_id = NEW.usuario_id AND activo;
            IF v_vehiculo IS NULL THEN
                RAISE EXCEPTION 'No se puede aprobar: el conductor no tiene vehículo activo'
                      USING ERRCODE = 'check_violation';
            END IF;

            SELECT string_agg(td.nombre, ', ' ORDER BY td.nombre) INTO v_faltantes
              FROM tipos_documento td
             WHERE (CASE v_tipo WHEN 'moto' THEN td.obligatorio_moto ELSE td.obligatorio_taxi END)
               AND NOT EXISTS (
                     SELECT 1 FROM documentos_conductor d
                      WHERE d.conductor_id   = NEW.usuario_id
                        AND d.tipo_documento = td.codigo
                        AND d.vigente AND d.estado = 'aprobado'
                        AND (d.fecha_vencimiento IS NULL OR d.fecha_vencimiento >= CURRENT_DATE)
                        AND (td.aplica_a = 'conductor' OR d.vehiculo_id = v_vehiculo));
            IF v_faltantes IS NOT NULL THEN
                RAISE EXCEPTION 'No se puede aprobar: faltan documentos aprobados y vigentes: %', v_faltantes
                      USING ERRCODE = 'check_violation';
            END IF;
            NEW.motivo_estado := NULL;
        END IF;
    END IF;

    IF NEW.disponible AND NOT OLD.disponible THEN
        IF NEW.estado <> 'aprobado' THEN
            RAISE EXCEPTION 'Solo los conductores aprobados pueden conectarse (estado: %)', NEW.estado
                  USING ERRCODE = 'check_violation';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM vehiculos WHERE conductor_id = NEW.usuario_id AND activo) THEN
            RAISE EXCEPTION 'No puedes conectarte sin un vehículo activo' USING ERRCODE = 'check_violation';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM ubicacion_conductores u
                        WHERE u.conductor_id = NEW.usuario_id
                          AND u.actualizado_en > now() - make_interval(secs => fn_config_num('ubicacion_vigencia_seg', 300)::INT)) THEN
            RAISE EXCEPTION 'Envía tu ubicación antes de conectarte' USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    RETURN NEW;
END $$;
CREATE TRIGGER trg_conductores_validar
    BEFORE INSERT OR UPDATE ON conductores FOR EACH ROW EXECUTE FUNCTION fn_conductores_validar();

-- 8.1b Perfil y rol siempre coinciden: crear el perfil de conductor le asigna el rol.
--      (En el prototipo la persona elige "usuario" o "conductor" al crear la cuenta.)
CREATE OR REPLACE FUNCTION fn_conductores_asignar_rol()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO usuario_roles (usuario_id, rol) VALUES (NEW.usuario_id, 'conductor')
    ON CONFLICT (usuario_id, rol) DO NOTHING;
    RETURN NULL;
END $$;
CREATE TRIGGER trg_conductores_asignar_rol
    AFTER INSERT ON conductores FOR EACH ROW EXECUTE FUNCTION fn_conductores_asignar_rol();

CREATE OR REPLACE FUNCTION fn_contactos_tope()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (SELECT count(*) FROM contactos_emergencia WHERE usuario_id = NEW.usuario_id)
       >= fn_config_num('max_contactos_emergencia', 3) THEN
        RAISE EXCEPTION 'Máximo % contactos de emergencia', fn_config_num('max_contactos_emergencia', 3)
              USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_contactos_tope
    BEFORE INSERT ON contactos_emergencia FOR EACH ROW EXECUTE FUNCTION fn_contactos_tope();

-- 8.2 Turnos: abre y cierra conductor_conexiones según disponible.
CREATE OR REPLACE FUNCTION fn_conductores_conexiones()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.disponible AND NOT OLD.disponible THEN
        INSERT INTO conductor_conexiones (conductor_id) VALUES (NEW.usuario_id);
    ELSIF NOT NEW.disponible AND OLD.disponible THEN
        UPDATE conductor_conexiones SET fin = now()
         WHERE conductor_id = NEW.usuario_id AND fin IS NULL;
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER trg_conductores_conexiones
    AFTER UPDATE ON conductores FOR EACH ROW
    WHEN (OLD.disponible IS DISTINCT FROM NEW.disponible)   -- también cuando otro trigger lo cambia
    EXECUTE FUNCTION fn_conductores_conexiones();

-- 8.3 Vehículos: licencia acorde (moto A1/A2, taxi C1–C3) y desconexión si se queda sin vehículo activo.
CREATE OR REPLACE FUNCTION fn_vehiculos_validar()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_categorias VARCHAR(2)[];
BEGIN
    SELECT categorias_licencia INTO v_categorias FROM conductores WHERE usuario_id = NEW.conductor_id;
    IF NEW.tipo_vehiculo = 'moto' AND NOT (v_categorias && ARRAY['A1','A2']::VARCHAR(2)[]) THEN
        RAISE EXCEPTION 'Para moto se requiere licencia A1 o A2' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.tipo_vehiculo = 'taxi' AND NOT (v_categorias && ARRAY['C1','C2','C3']::VARCHAR(2)[]) THEN
        RAISE EXCEPTION 'Para taxi (servicio público) se requiere licencia C1, C2 o C3' USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.activo AND NOT NEW.activo
       AND EXISTS (SELECT 1 FROM servicios WHERE vehiculo_id = NEW.id
                    AND estado IN ('asignado','en_origen','en_curso')) THEN
        RAISE EXCEPTION 'No se puede desactivar un vehículo con un servicio en curso' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_vehiculos_validar
    BEFORE INSERT OR UPDATE ON vehiculos FOR EACH ROW EXECUTE FUNCTION fn_vehiculos_validar();

CREATE OR REPLACE FUNCTION fn_vehiculos_despues()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT NEW.activo THEN
        UPDATE conductores SET disponible = false
         WHERE usuario_id = NEW.conductor_id AND disponible
           AND NOT EXISTS (SELECT 1 FROM vehiculos WHERE conductor_id = NEW.conductor_id AND activo);
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER trg_vehiculos_despues
    AFTER UPDATE ON vehiculos FOR EACH ROW
    WHEN (OLD.activo AND NOT NEW.activo)
    EXECUTE FUNCTION fn_vehiculos_despues();

-- 8.4 Documentos: vehículo obligatorio si el documento es del vehículo, vencimiento obligatorio
--     cuando aplica, reemplazo automático del vigente y sello de revisión.
CREATE OR REPLACE FUNCTION fn_documentos_validar()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    td tipos_documento%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT * INTO td FROM tipos_documento WHERE codigo = NEW.tipo_documento;
        IF td.aplica_a = 'vehiculo' THEN
            IF NEW.vehiculo_id IS NULL OR NOT EXISTS (
                   SELECT 1 FROM vehiculos WHERE id = NEW.vehiculo_id AND conductor_id = NEW.conductor_id) THEN
                RAISE EXCEPTION 'El documento % debe asociarse a un vehículo del conductor', td.nombre
                      USING ERRCODE = 'check_violation';
            END IF;
        ELSE
            NEW.vehiculo_id := NULL;
        END IF;
        IF td.requiere_vencimiento AND NEW.fecha_vencimiento IS NULL THEN
            RAISE EXCEPTION 'El documento % requiere fecha de vencimiento', td.nombre USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.fecha_vencimiento IS NOT NULL AND NEW.fecha_vencimiento < CURRENT_DATE THEN
            RAISE EXCEPTION 'El documento % ya está vencido', td.nombre USING ERRCODE = 'check_violation';
        END IF;
        NEW.estado := 'pendiente';
        IF NEW.vigente THEN
            UPDATE documentos_conductor SET vigente = false
             WHERE conductor_id = NEW.conductor_id AND tipo_documento = NEW.tipo_documento
               AND COALESCE(vehiculo_id, 0) = COALESCE(NEW.vehiculo_id, 0) AND vigente;
        END IF;
    ELSIF NEW.estado IS DISTINCT FROM OLD.estado THEN
        NEW.revisado_en  := now();
        NEW.revisado_por := COALESCE(fn_actor_usuario(), NEW.revisado_por);
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_documentos_validar
    BEFORE INSERT OR UPDATE ON documentos_conductor FOR EACH ROW EXECUTE FUNCTION fn_documentos_validar();

-- 8.5 Servicios — al crear: cliente activo con celular verificado, origen dentro de la cobertura
--     de Buenaventura, capacidad, tarifa y vencimiento de búsqueda.
CREATE OR REPLACE FUNCTION fn_servicios_antes_insertar()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    u usuarios%ROWTYPE;
BEGIN
    SELECT * INTO u FROM usuarios WHERE id = NEW.cliente_id;
    IF u.estado IS DISTINCT FROM 'activo' THEN
        RAISE EXCEPTION 'La cuenta del cliente no está activa' USING ERRCODE = 'check_violation';
    END IF;
    -- El conductor necesita poder llamar al cliente: quien entra con Google debe verificar su celular.
    IF NOT u.telefono_verificado THEN
        RAISE EXCEPTION 'Verifica tu número de celular antes de pedir un servicio' USING ERRCODE = 'check_violation';
    END IF;

    IF fn_distancia_km(fn_config_num('cobertura_lat', 3.8801)::FLOAT, fn_config_num('cobertura_lng', -77.0312)::FLOAT,
                       NEW.origen_lat, NEW.origen_lng) > fn_config_num('cobertura_radio_km', 15) THEN
        RAISE EXCEPTION 'Por ahora MoviFY solo opera en Buenaventura: el origen está fuera de la zona de cobertura'
              USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.pasajeros > (SELECT capacidad_pasajeros FROM tipos_vehiculo WHERE codigo = NEW.tipo_vehiculo) THEN
        RAISE EXCEPTION 'El vehículo % no admite % pasajeros', NEW.tipo_vehiculo, NEW.pasajeros
              USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.estado <> 'buscando' OR NEW.conductor_id IS NOT NULL OR NEW.vehiculo_id IS NOT NULL
       OR NEW.valor_final IS NOT NULL THEN
        RAISE EXCEPTION 'Un servicio nuevo nace en estado buscando, sin conductor ni valor final'
              USING ERRCODE = 'check_violation';
    END IF;
    NEW.solicitado_en := now();
    NEW.expira_busqueda_en := now() + make_interval(secs => fn_config_num('tiempo_busqueda_seg', 120)::INT);

    IF NEW.tarifa_id IS NULL THEN
        SELECT id INTO NEW.tarifa_id FROM tarifas
         WHERE tipo_servicio = NEW.tipo_servicio AND tipo_vehiculo = NEW.tipo_vehiculo AND activo;
    END IF;
    IF NEW.valor_estimado IS NULL AND NEW.tarifa_id IS NOT NULL AND NEW.distancia_km IS NOT NULL THEN
        NEW.valor_estimado := fn_calcular_tarifa(NEW.tarifa_id, NEW.distancia_km,
                                                 COALESCE(NEW.duracion_estimada_min, 0), now());
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_servicios_antes_insertar
    BEFORE INSERT ON servicios FOR EACH ROW EXECUTE FUNCTION fn_servicios_antes_insertar();

-- 8.6 Servicios — al actualizar: transición permitida PARA ESE ACTOR, conductor apto,
--     sin bloqueos, sin aceptar solicitudes vencidas, fechas por etapa y reglas de cierre.
CREATE OR REPLACE FUNCTION fn_servicios_validar_estado()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_actor VARCHAR(20) := fn_actor_rol();
BEGIN
    NEW.actualizado_en := now();

    IF NEW.estado = OLD.estado THEN
        IF NEW.conductor_id IS DISTINCT FROM OLD.conductor_id OR NEW.vehiculo_id IS DISTINCT FROM OLD.vehiculo_id THEN
            RAISE EXCEPTION 'El conductor de un servicio solo cambia con una transición de estado'
                  USING ERRCODE = 'check_violation';
        END IF;
        IF (SELECT es_final FROM estados WHERE ambito = 'servicio' AND codigo = OLD.estado)
           AND (NEW.valor_final, NEW.cliente_id, NEW.origen_lat, NEW.destino_lat)
               IS DISTINCT FROM (OLD.valor_final, OLD.cliente_id, OLD.origen_lat, OLD.destino_lat) THEN
            RAISE EXCEPTION 'Un servicio cerrado no se modifica' USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM transiciones_estado t
                    WHERE t.ambito = 'servicio'
                      AND t.estado_origen = OLD.estado AND t.estado_destino = NEW.estado
                      AND (v_actor IS NULL OR t.actor = v_actor)) THEN
        RAISE EXCEPTION 'Transición no permitida: % -> % (actor: %)', OLD.estado, NEW.estado,
              COALESCE(v_actor, 'sin definir') USING ERRCODE = 'check_violation';
    END IF;

    CASE NEW.estado
        WHEN 'asignado' THEN
            IF OLD.expira_busqueda_en < now() THEN
                RAISE EXCEPTION 'La solicitud % ya expiró', OLD.id USING ERRCODE = 'check_violation';
            END IF;
            IF NOT EXISTS (SELECT 1 FROM conductores c JOIN usuarios u ON u.id = c.usuario_id
                            WHERE c.usuario_id = NEW.conductor_id
                              AND c.estado = 'aprobado' AND u.estado = 'activo') THEN
                RAISE EXCEPTION 'El conductor no está aprobado o su cuenta no está activa'
                      USING ERRCODE = 'check_violation';
            END IF;
            IF EXISTS (SELECT 1 FROM usuarios_bloqueados b
                        WHERE (b.usuario_id = NEW.cliente_id   AND b.bloqueado_id = NEW.conductor_id)
                           OR (b.usuario_id = NEW.conductor_id AND b.bloqueado_id = NEW.cliente_id)) THEN
                RAISE EXCEPTION 'Este conductor no puede atender a este cliente' USING ERRCODE = 'check_violation';
            END IF;
            NEW.asignado_en := now();
        WHEN 'en_origen' THEN
            NEW.llegada_origen_en := now();
        WHEN 'en_curso' THEN
            NEW.iniciado_en := now();
        WHEN 'finalizado' THEN
            NEW.finalizado_en := now();
            NEW.valor_final   := COALESCE(NEW.valor_final, NEW.valor_estimado);
            IF NEW.tipo_servicio = 'encomienda' AND NOT EXISTS (
                   SELECT 1 FROM servicio_encomienda WHERE servicio_id = NEW.id AND entregado_a IS NOT NULL) THEN
                RAISE EXCEPTION 'Registra a quién se entregó la encomienda antes de finalizar'
                      USING ERRCODE = 'check_violation';
            END IF;
            IF NEW.tipo_servicio = 'domicilio' AND EXISTS (
                   SELECT 1 FROM servicio_domicilio
                    WHERE servicio_id = NEW.id AND requiere_compra AND valor_compra_real IS NULL) THEN
                RAISE EXCEPTION 'Registra el valor real de la compra antes de finalizar el domicilio'
                      USING ERRCODE = 'check_violation';
            END IF;
        WHEN 'cancelado' THEN
            NEW.cancelado_en  := now();
            NEW.cancelado_por := COALESCE(NEW.cancelado_por, fn_actor_usuario());
            IF v_actor IS NOT NULL AND NEW.motivo_cancelacion_id IS NOT NULL AND NOT EXISTS (
                   SELECT 1 FROM motivos_cancelacion
                    WHERE id = NEW.motivo_cancelacion_id AND activo
                      AND actor = CASE WHEN v_actor = 'soporte' THEN 'admin' ELSE v_actor END) THEN
                RAISE EXCEPTION 'El motivo de cancelación no corresponde a un %', v_actor
                      USING ERRCODE = 'check_violation';
            END IF;
        WHEN 'expirado' THEN
            NEW.cancelado_en          := now();
            NEW.motivo_cancelacion_id := COALESCE(NEW.motivo_cancelacion_id, 30);
        WHEN 'buscando' THEN                          -- el conductor soltó el servicio
            NEW.conductor_id       := NULL;
            NEW.vehiculo_id        := NULL;
            NEW.asignado_en        := NULL;
            NEW.expira_busqueda_en := now() + make_interval(secs => fn_config_num('tiempo_busqueda_seg', 120)::INT);
        ELSE NULL;
    END CASE;

    RETURN NEW;
END $$;
CREATE TRIGGER trg_servicios_validar_estado
    BEFORE UPDATE ON servicios FOR EACH ROW EXECUTE FUNCTION fn_servicios_validar_estado();

-- 8.7 Servicios — después: ofertas y contadores del conductor.
CREATE OR REPLACE FUNCTION fn_servicios_despues()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_actor_id  BIGINT      := fn_actor_usuario();
    v_actor_rol VARCHAR(20) := fn_actor_rol();
BEGIN
    -- El historial lo registra el trigger genérico fn_estado_historial.
    IF NEW.estado IS NOT DISTINCT FROM OLD.estado THEN
        RETURN NULL;
    END IF;

    IF NEW.estado = 'asignado' THEN
        UPDATE servicio_ofertas
           SET estado = CASE WHEN conductor_id = NEW.conductor_id THEN 'aceptada' ELSE 'expirada' END,
               respondida_en = now()
         WHERE servicio_id = NEW.id AND estado IN ('enviada','vista');

    ELSIF NEW.estado = 'buscando' AND OLD.conductor_id IS NOT NULL THEN
        UPDATE conductores SET total_cancelaciones = total_cancelaciones + 1 WHERE usuario_id = OLD.conductor_id;
        UPDATE servicio_ofertas SET estado = 'rechazada', respondida_en = now()
         WHERE servicio_id = NEW.id AND conductor_id = OLD.conductor_id;
        UPDATE servicio_ofertas SET estado = 'enviada', respondida_en = NULL, enviada_en = now()
         WHERE servicio_id = NEW.id AND estado = 'expirada';

    ELSIF (SELECT es_final FROM estados WHERE ambito = 'servicio' AND codigo = NEW.estado) THEN
        UPDATE servicio_ofertas SET estado = 'expirada', respondida_en = now()
         WHERE servicio_id = NEW.id AND estado IN ('enviada','vista');
        IF NEW.estado = 'finalizado' THEN
            UPDATE conductores SET total_servicios = total_servicios + 1 WHERE usuario_id = NEW.conductor_id;
        ELSIF NEW.estado = 'cancelado' AND v_actor_rol = 'conductor' THEN
            UPDATE conductores SET total_cancelaciones = total_cancelaciones + 1 WHERE usuario_id = NEW.conductor_id;
        END IF;
    END IF;

    RETURN NULL;
END $$;
CREATE TRIGGER trg_servicios_despues
    AFTER UPDATE ON servicios FOR EACH ROW EXECUTE FUNCTION fn_servicios_despues();

-- 8.8 Encomiendas: la moto no lleva paquetes grandes ni pesados.
CREATE OR REPLACE FUNCTION fn_encomienda_validar()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (SELECT tipo_vehiculo FROM servicios WHERE id = NEW.servicio_id) = 'moto'
       AND (NEW.tamano = 'grande' OR NEW.peso_kg > fn_config_num('peso_max_moto_kg', 15)) THEN
        RAISE EXCEPTION 'Paquetes grandes o de más de % kg se envían en taxi', fn_config_num('peso_max_moto_kg', 15)
              USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_encomienda_validar
    BEFORE INSERT OR UPDATE ON servicio_encomienda FOR EACH ROW EXECUTE FUNCTION fn_encomienda_validar();

-- 8.9 Chat: solo cliente y conductor del servicio, y solo mientras está activo.
CREATE OR REPLACE FUNCTION fn_mensajes_validar()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM servicios s
                    WHERE s.id = NEW.servicio_id
                      AND NEW.remitente_id IN (s.cliente_id, s.conductor_id)
                      AND s.estado IN ('asignado','en_origen','en_curso')) THEN
        RAISE EXCEPTION 'Solo el cliente y el conductor pueden escribir, y solo con el servicio activo'
              USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_mensajes_validar
    BEFORE INSERT ON mensajes_servicio FOR EACH ROW EXECUTE FUNCTION fn_mensajes_validar();

-- 8.10 Rastro GPS: solo el conductor asignado y con el servicio activo.
CREATE OR REPLACE FUNCTION fn_seguimiento_validar()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM servicios s
                    WHERE s.id = NEW.servicio_id AND s.conductor_id = NEW.conductor_id
                      AND s.estado IN ('asignado','en_origen','en_curso')) THEN
        RAISE EXCEPTION 'Ubicación rechazada: el servicio % no está activo para ese conductor', NEW.servicio_id
              USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_seguimiento_validar
    BEFORE INSERT ON seguimiento_ubicaciones FOR EACH ROW EXECUTE FUNCTION fn_seguimiento_validar();

-- 8.11 Calificaciones: servicio finalizado, entre las dos partes, dentro del plazo;
--      actualiza el promedio del conductor o del cliente.
CREATE OR REPLACE FUNCTION fn_calificaciones_procesar()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    s servicios%ROWTYPE;
BEGIN
    SELECT * INTO s FROM servicios WHERE id = NEW.servicio_id;

    IF s.estado <> 'finalizado' THEN
        RAISE EXCEPTION 'Solo se califican servicios finalizados (servicio %, estado %)', s.id, s.estado
              USING ERRCODE = 'check_violation';
    END IF;
    IF s.finalizado_en < now() - make_interval(days => fn_config_num('dias_para_calificar', 7)::INT) THEN
        RAISE EXCEPTION 'El plazo para calificar el servicio % ya venció', s.id USING ERRCODE = 'check_violation';
    END IF;
    IF NOT ((NEW.calificador_id = s.cliente_id   AND NEW.calificado_id = s.conductor_id) OR
            (NEW.calificador_id = s.conductor_id AND NEW.calificado_id = s.cliente_id)) THEN
        RAISE EXCEPTION 'La calificación debe ser entre el cliente y el conductor del servicio %', s.id
              USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.calificado_id = s.conductor_id THEN
        UPDATE conductores
           SET calificacion_promedio = ROUND((calificacion_promedio * total_calificaciones + NEW.puntuacion)
                                             / (total_calificaciones + 1)::NUMERIC, 2),
               total_calificaciones  = total_calificaciones + 1
         WHERE usuario_id = s.conductor_id;
    ELSE
        UPDATE usuarios
           SET calificacion_promedio = ROUND((calificacion_promedio * total_calificaciones + NEW.puntuacion)
                                             / (total_calificaciones + 1)::NUMERIC, 2),
               total_calificaciones  = total_calificaciones + 1
         WHERE id = s.cliente_id;
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER trg_calificaciones_procesar
    AFTER INSERT ON calificaciones FOR EACH ROW EXECUTE FUNCTION fn_calificaciones_procesar();

-- 8.12 PQRS: fecha de resolución automática.
CREATE OR REPLACE FUNCTION fn_tickets_actualizar()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.actualizado_en := now();
    IF NEW.estado IN ('resuelto','cerrado') AND OLD.estado NOT IN ('resuelto','cerrado') THEN
        NEW.resuelto_en := now();
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_tickets_actualizar
    BEFORE UPDATE ON tickets_soporte FOR EACH ROW EXECUTE FUNCTION fn_tickets_actualizar();

-- 8.13 Auditoría automática de cambios sensibles (nunca guarda el hash de la contraseña).
CREATE OR REPLACE FUNCTION fn_auditar_cambios()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_antes   JSONB := CASE WHEN TG_OP <> 'INSERT' THEN to_jsonb(OLD) - 'password_hash' END;
    v_despues JSONB := CASE WHEN TG_OP <> 'DELETE' THEN to_jsonb(NEW) - 'password_hash' END;
    v_fila    JSONB := COALESCE(v_despues, v_antes);
BEGIN
    INSERT INTO auditoria_cambios (tabla, operacion, registro_id, datos_antes, datos_despues, usuario_id, actor_rol)
    VALUES (TG_TABLE_NAME, left(TG_OP, 1),
            COALESCE(v_fila->>'id', v_fila->>'usuario_id', v_fila->>'clave', v_fila->>'rol',
                     v_fila->>'codigo', '?')
              || CASE WHEN TG_TABLE_NAME = 'usuario_roles' THEN ':' || (v_fila->>'rol')
                      WHEN v_fila ? 'permiso'           THEN ':' || (v_fila->>'permiso')
                      ELSE '' END,
            v_antes, v_despues, fn_actor_usuario(), fn_actor_rol());
    RETURN NULL;
END $$;

CREATE TRIGGER trg_auditoria_usuario_roles AFTER INSERT OR DELETE ON usuario_roles
    FOR EACH ROW EXECUTE FUNCTION fn_auditar_cambios();
CREATE TRIGGER trg_auditoria_usuarios AFTER UPDATE ON usuarios
    FOR EACH ROW WHEN (OLD.estado IS DISTINCT FROM NEW.estado
                    OR OLD.telefono IS DISTINCT FROM NEW.telefono
                    OR OLD.email IS DISTINCT FROM NEW.email)
    EXECUTE FUNCTION fn_auditar_cambios();
CREATE TRIGGER trg_auditoria_conductores AFTER UPDATE ON conductores
    FOR EACH ROW WHEN (OLD.estado IS DISTINCT FROM NEW.estado)
    EXECUTE FUNCTION fn_auditar_cambios();
CREATE TRIGGER trg_auditoria_vehiculos AFTER INSERT OR UPDATE OR DELETE ON vehiculos
    FOR EACH ROW EXECUTE FUNCTION fn_auditar_cambios();
CREATE TRIGGER trg_auditoria_tarifas AFTER INSERT OR UPDATE ON tarifas
    FOR EACH ROW EXECUTE FUNCTION fn_auditar_cambios();
CREATE TRIGGER trg_auditoria_configuracion AFTER UPDATE ON configuracion_sistema
    FOR EACH ROW EXECUTE FUNCTION fn_auditar_cambios();

-- 8.15 Seguridad: roles de sistema protegidos, nunca quedarse sin administrador,
--      roles inactivos no se asignan y bloqueo temporal por intentos fallidos.
CREATE OR REPLACE FUNCTION fn_roles_proteger()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND OLD.es_sistema THEN
        RAISE EXCEPTION 'El rol % es de sistema y no se puede eliminar', OLD.codigo USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.es_sistema
       AND (NEW.codigo <> OLD.codigo OR NOT NEW.es_sistema OR NOT NEW.activo) THEN
        RAISE EXCEPTION 'El rol % es de sistema: no se renombra ni se desactiva', OLD.codigo USING ERRCODE = 'check_violation';
    END IF;
    RETURN COALESCE(NEW, OLD);
END $$;
CREATE TRIGGER trg_roles_proteger
    BEFORE UPDATE OR DELETE ON roles FOR EACH ROW EXECUTE FUNCTION fn_roles_proteger();

CREATE OR REPLACE FUNCTION fn_usuario_roles_proteger()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT 1 FROM roles WHERE codigo = NEW.rol AND activo) THEN
            RAISE EXCEPTION 'El rol % está inactivo', NEW.rol USING ERRCODE = 'check_violation';
        END IF;
        NEW.asignado_por := COALESCE(NEW.asignado_por, fn_actor_usuario());
        RETURN NEW;
    END IF;

    IF OLD.rol = 'conductor' AND EXISTS (SELECT 1 FROM conductores
                                          WHERE usuario_id = OLD.usuario_id AND estado = 'aprobado') THEN
        RAISE EXCEPTION 'Suspende primero al conductor antes de quitarle el rol' USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.rol = 'admin' AND NOT EXISTS (
           SELECT 1 FROM usuario_roles ur JOIN usuarios u ON u.id = ur.usuario_id
            WHERE ur.rol = 'admin' AND u.estado = 'activo' AND ur.usuario_id <> OLD.usuario_id) THEN
        RAISE EXCEPTION 'No se puede quitar el último administrador activo' USING ERRCODE = 'check_violation';
    END IF;
    RETURN OLD;
END $$;
CREATE TRIGGER trg_usuario_roles_proteger
    BEFORE INSERT OR DELETE ON usuario_roles FOR EACH ROW EXECUTE FUNCTION fn_usuario_roles_proteger();

-- También impide bloquear o eliminar al último administrador activo.
CREATE OR REPLACE FUNCTION fn_usuarios_ultimo_admin()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.estado = 'activo' AND NEW.estado <> 'activo'
       AND EXISTS (SELECT 1 FROM usuario_roles WHERE usuario_id = OLD.id AND rol = 'admin')
       AND NOT EXISTS (SELECT 1 FROM usuario_roles ur JOIN usuarios u ON u.id = ur.usuario_id
                        WHERE ur.rol = 'admin' AND u.estado = 'activo' AND u.id <> OLD.id) THEN
        RAISE EXCEPTION 'No se puede bloquear o eliminar al último administrador activo' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_usuarios_ultimo_admin
    BEFORE UPDATE OF estado ON usuarios FOR EACH ROW EXECUTE FUNCTION fn_usuarios_ultimo_admin();

CREATE OR REPLACE FUNCTION fn_usuario_permisos_sellar()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.asignado_por := COALESCE(NEW.asignado_por, fn_actor_usuario());
    RETURN NEW;
END $$;
CREATE TRIGGER trg_usuario_permisos_sellar
    BEFORE INSERT ON usuario_permisos FOR EACH ROW EXECUTE FUNCTION fn_usuario_permisos_sellar();
CREATE TRIGGER trg_rol_permisos_sellar
    BEFORE INSERT ON rol_permisos FOR EACH ROW EXECUTE FUNCTION fn_usuario_permisos_sellar();

-- N intentos fallidos en M minutos → acceso bloqueado temporalmente; un acceso exitoso lo limpia.
CREATE OR REPLACE FUNCTION fn_registro_accesos_bloqueo()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_fallidos INTEGER;
    v_ventana  INTERVAL := make_interval(mins => fn_config_num('minutos_ventana_intentos', 15)::INT);
BEGIN
    IF NEW.usuario_id IS NULL THEN
        RETURN NULL;
    END IF;
    IF NEW.resultado = 'exitoso' THEN
        UPDATE usuarios SET acceso_bloqueado_hasta = NULL, ultimo_acceso = NEW.creado_en WHERE id = NEW.usuario_id;
        RETURN NULL;
    END IF;
    IF NEW.resultado IN ('codigo_invalido','password_invalido','token_google_invalido') THEN
        SELECT count(*) INTO v_fallidos
          FROM registro_accesos
         WHERE usuario_id = NEW.usuario_id
           AND resultado IN ('codigo_invalido','password_invalido','token_google_invalido')
           AND creado_en > now() - v_ventana
           AND creado_en > COALESCE((SELECT max(creado_en) FROM registro_accesos
                                      WHERE usuario_id = NEW.usuario_id AND resultado = 'exitoso'), '-infinity');
        IF v_fallidos >= fn_config_num('max_intentos_fallidos', 5) THEN
            UPDATE usuarios
               SET acceso_bloqueado_hasta = now() + make_interval(mins => fn_config_num('minutos_bloqueo_acceso', 15)::INT)
             WHERE id = NEW.usuario_id;
        END IF;
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER trg_registro_accesos_bloqueo
    AFTER INSERT ON registro_accesos FOR EACH ROW EXECUTE FUNCTION fn_registro_accesos_bloqueo();

CREATE TRIGGER trg_auditoria_rol_permisos AFTER INSERT OR DELETE ON rol_permisos
    FOR EACH ROW EXECUTE FUNCTION fn_auditar_cambios();
CREATE TRIGGER trg_auditoria_usuario_permisos AFTER INSERT OR UPDATE OR DELETE ON usuario_permisos
    FOR EACH ROW EXECUTE FUNCTION fn_auditar_cambios();
CREATE TRIGGER trg_auditoria_roles AFTER INSERT OR UPDATE OR DELETE ON roles
    FOR EACH ROW EXECUTE FUNCTION fn_auditar_cambios();
CREATE TRIGGER trg_registro_accesos_inmutable BEFORE UPDATE ON registro_accesos
    FOR EACH ROW EXECUTE FUNCTION fn_bloquear_modificacion();

-- 8.16 Celda de estados: validación y historial genéricos.
--      El prefijo trg_00_ hace que la validación corra antes que los demás triggers (PostgreSQL los ordena por nombre).
--      (servicios valida sus transiciones en fn_servicios_validar_estado porque además revisa conductor, bloqueos, etc.)
CREATE TRIGGER trg_00_estado_validar BEFORE INSERT OR UPDATE OF estado ON usuarios
    FOR EACH ROW EXECUTE FUNCTION fn_estado_validar('usuario');
CREATE TRIGGER trg_00_estado_validar BEFORE INSERT OR UPDATE OF estado ON conductores
    FOR EACH ROW EXECUTE FUNCTION fn_estado_validar('conductor');
CREATE TRIGGER trg_00_estado_validar BEFORE INSERT OR UPDATE OF estado ON documentos_conductor
    FOR EACH ROW EXECUTE FUNCTION fn_estado_validar('documento');
CREATE TRIGGER trg_00_estado_validar BEFORE INSERT OR UPDATE OF estado ON alertas_sos
    FOR EACH ROW EXECUTE FUNCTION fn_estado_validar('alerta_sos');
CREATE TRIGGER trg_00_estado_validar BEFORE INSERT OR UPDATE OF estado ON tickets_soporte
    FOR EACH ROW EXECUTE FUNCTION fn_estado_validar('ticket');

CREATE TRIGGER trg_estado_historial AFTER INSERT OR UPDATE OF estado ON usuarios
    FOR EACH ROW EXECUTE FUNCTION fn_estado_historial('usuario', 'id');
CREATE TRIGGER trg_estado_historial AFTER INSERT OR UPDATE OF estado ON conductores
    FOR EACH ROW EXECUTE FUNCTION fn_estado_historial('conductor', 'usuario_id');
CREATE TRIGGER trg_estado_historial AFTER INSERT OR UPDATE OF estado ON documentos_conductor
    FOR EACH ROW EXECUTE FUNCTION fn_estado_historial('documento', 'id');
CREATE TRIGGER trg_estado_historial AFTER INSERT OR UPDATE OF estado ON servicios
    FOR EACH ROW EXECUTE FUNCTION fn_estado_historial('servicio', 'id');
CREATE TRIGGER trg_estado_historial AFTER INSERT OR UPDATE OF estado ON alertas_sos
    FOR EACH ROW EXECUTE FUNCTION fn_estado_historial('alerta_sos', 'id');
CREATE TRIGGER trg_estado_historial AFTER INSERT OR UPDATE OF estado ON tickets_soporte
    FOR EACH ROW EXECUTE FUNCTION fn_estado_historial('ticket', 'id');

-- 8.14 Bitácoras de solo inserción.
CREATE TRIGGER trg_historial_inmutable   BEFORE UPDATE ON historial_estados
    FOR EACH ROW EXECUTE FUNCTION fn_bloquear_modificacion();
CREATE TRIGGER trg_auditoria_inmutable   BEFORE UPDATE OR DELETE ON auditoria_cambios
    FOR EACH ROW EXECUTE FUNCTION fn_bloquear_modificacion();
CREATE TRIGGER trg_auditoria_admin_inmutable BEFORE UPDATE OR DELETE ON auditoria_admin
    FOR EACH ROW EXECUTE FUNCTION fn_bloquear_modificacion();
CREATE TRIGGER trg_aceptaciones_inmutable BEFORE UPDATE ON aceptaciones_legales
    FOR EACH ROW EXECUTE FUNCTION fn_bloquear_modificacion();
CREATE TRIGGER trg_calificaciones_inmutable BEFORE UPDATE ON calificaciones
    FOR EACH ROW EXECUTE FUNCTION fn_bloquear_modificacion();


-- =====================================================================
-- 9. FUNCIONES DE CONSULTA Y MANTENIMIENTO
-- =====================================================================

-- Conductores disponibles cerca de un punto (caja + distancia real), sin bloqueados.
CREATE OR REPLACE FUNCTION fn_conductores_cercanos(
    p_lat           DOUBLE PRECISION,
    p_lng           DOUBLE PRECISION,
    p_radio_km      DOUBLE PRECISION,
    p_tipo_vehiculo VARCHAR,
    p_cliente_id    BIGINT DEFAULT NULL)
RETURNS TABLE (conductor_id BIGINT, vehiculo_id BIGINT, distancia_km DOUBLE PRECISION)
LANGUAGE sql STABLE AS $$
    SELECT c.usuario_id, v.id, fn_distancia_km(p_lat, p_lng, u.lat, u.lng) AS dist
      FROM ubicacion_conductores u
      JOIN conductores c ON c.usuario_id = u.conductor_id
      JOIN usuarios   us ON us.id = c.usuario_id AND us.estado = 'activo'
      JOIN vehiculos  v  ON v.conductor_id = c.usuario_id AND v.activo AND v.tipo_vehiculo = p_tipo_vehiculo
     WHERE c.disponible
       AND c.estado = 'aprobado'
       AND u.actualizado_en > now() - make_interval(secs => fn_config_num('ubicacion_vigencia_seg', 300)::INT)
       AND u.lat BETWEEN p_lat - p_radio_km / 111.045 AND p_lat + p_radio_km / 111.045
       AND u.lng BETWEEN p_lng - p_radio_km / (111.045 * cos(radians(p_lat)))
                     AND p_lng + p_radio_km / (111.045 * cos(radians(p_lat)))
       AND fn_distancia_km(p_lat, p_lng, u.lat, u.lng) <= p_radio_km
       AND (p_cliente_id IS NULL OR c.usuario_id <> p_cliente_id)
       AND NOT EXISTS (SELECT 1 FROM servicios s
                        WHERE s.conductor_id = c.usuario_id AND s.estado IN ('asignado','en_origen','en_curso'))
       AND NOT EXISTS (SELECT 1 FROM usuarios_bloqueados b
                        WHERE (b.usuario_id = p_cliente_id AND b.bloqueado_id = c.usuario_id)
                           OR (b.usuario_id = c.usuario_id AND b.bloqueado_id = p_cliente_id))
     ORDER BY dist
     LIMIT 20;
$$;

-- Solicitudes vigentes cerca de un conductor, según su vehículo activo.
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
       AND s.cliente_id <> c.usuario_id
       AND s.origen_lat BETWEEN u.lat - p_radio_km / 111.045 AND u.lat + p_radio_km / 111.045
       AND fn_distancia_km(u.lat, u.lng, s.origen_lat, s.origen_lng) <= p_radio_km
       AND NOT EXISTS (SELECT 1 FROM usuarios_bloqueados b
                        WHERE (b.usuario_id = s.cliente_id AND b.bloqueado_id = c.usuario_id)
                           OR (b.usuario_id = c.usuario_id AND b.bloqueado_id = s.cliente_id))
     ORDER BY dist, s.solicitado_en
     LIMIT 30;
$$;

-- Tarea programada (cada minuto): expira solicitudes sin conductor.
CREATE OR REPLACE FUNCTION fn_expirar_solicitudes()
RETURNS INTEGER LANGUAGE plpgsql AS $$
DECLARE
    v_total INTEGER;
BEGIN
    PERFORM set_config('movify.actor', 'sistema', true);
    UPDATE servicios SET estado = 'expirado'
     WHERE estado = 'buscando' AND expira_busqueda_en < now();
    GET DIAGNOSTICS v_total = ROW_COUNT;
    RETURN v_total;
END $$;

-- Tarea programada (diaria): suspende conductores con documentos obligatorios vencidos.
CREATE OR REPLACE FUNCTION fn_suspender_documentos_vencidos()
RETURNS INTEGER LANGUAGE plpgsql AS $$
DECLARE
    v_total INTEGER;
BEGIN
    PERFORM set_config('movify.actor', 'sistema', true);
    UPDATE documentos_conductor SET estado = 'vencido'
     WHERE vigente AND estado IN ('pendiente','aprobado') AND fecha_vencimiento < CURRENT_DATE;
    UPDATE conductores c
       SET estado = 'suspendido',
           motivo_estado = 'Documento obligatorio vencido'
     WHERE c.estado = 'aprobado'
       AND EXISTS (SELECT 1 FROM documentos_conductor d
                     JOIN tipos_documento td ON td.codigo = d.tipo_documento
                     LEFT JOIN vehiculos v   ON v.conductor_id = c.usuario_id AND v.activo
                    WHERE d.conductor_id = c.usuario_id AND d.vigente
                      AND d.estado = 'vencido'
                      AND (CASE v.tipo_vehiculo WHEN 'moto' THEN td.obligatorio_moto ELSE td.obligatorio_taxi END))
       AND NOT EXISTS (SELECT 1 FROM servicios s
                        WHERE s.conductor_id = c.usuario_id AND s.estado IN ('asignado','en_origen','en_curso'));
    GET DIAGNOSTICS v_total = ROW_COUNT;
    RETURN v_total;
END $$;

-- Eliminación de cuenta (derecho de supresión, Ley 1581; también lo exige Google Play).
-- Anonimiza en lugar de borrar para conservar la trazabilidad de los servicios.
-- Los archivos (fotos, documentos) los borra el backend del disco.
CREATE OR REPLACE FUNCTION fn_eliminar_cuenta(p_usuario_id BIGINT)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM servicios
                WHERE (cliente_id = p_usuario_id OR conductor_id = p_usuario_id)
                  AND estado IN ('buscando','asignado','en_origen','en_curso')) THEN
        RAISE EXCEPTION 'No se puede eliminar la cuenta con un servicio activo' USING ERRCODE = 'check_violation';
    END IF;
    PERFORM set_config('movify.actor', 'sistema', true);

    UPDATE conductores SET disponible = false WHERE usuario_id = p_usuario_id AND disponible;
    DELETE FROM ubicacion_conductores  WHERE conductor_id = p_usuario_id;
    DELETE FROM sesiones               WHERE usuario_id = p_usuario_id;
    DELETE FROM dispositivos           WHERE usuario_id = p_usuario_id;
    DELETE FROM direcciones_favoritas  WHERE usuario_id = p_usuario_id;
    DELETE FROM contactos_emergencia   WHERE usuario_id = p_usuario_id;
    DELETE FROM usuarios_bloqueados    WHERE usuario_id = p_usuario_id;

    UPDATE usuarios
       SET nombres = 'Usuario eliminado', apellidos = NULL, telefono = NULL, telefono_verificado = false,
           email = NULL, email_verificado = false, google_sub = NULL, password_hash = NULL,
           foto_url = NULL, motivo_bloqueo = NULL, estado = 'eliminado', eliminado_en = now()
     WHERE id = p_usuario_id AND estado <> 'eliminado';
END $$;

-- Tarea programada (semanal): limpia datos temporales según las políticas de retención.
CREATE OR REPLACE FUNCTION fn_purgar_datos()
RETURNS TABLE (tabla TEXT, filas BIGINT) LANGUAGE plpgsql AS $$
DECLARE
    n BIGINT;
BEGIN
    DELETE FROM codigos_verificacion WHERE creado_en < now() - INTERVAL '2 days';
    GET DIAGNOSTICS n = ROW_COUNT; tabla := 'codigos_verificacion'; filas := n; RETURN NEXT;

    DELETE FROM sesiones WHERE COALESCE(revocada_en, expira_en) < now() - INTERVAL '30 days';
    GET DIAGNOSTICS n = ROW_COUNT; tabla := 'sesiones'; filas := n; RETURN NEXT;

    DELETE FROM seguimiento_ubicaciones
     WHERE registrado_en < now() - make_interval(days => fn_config_num('retencion_seguimiento_dias', 180)::INT);
    GET DIAGNOSTICS n = ROW_COUNT; tabla := 'seguimiento_ubicaciones'; filas := n; RETURN NEXT;

    DELETE FROM registro_accesos WHERE creado_en < now() - INTERVAL '365 days';
    GET DIAGNOSTICS n = ROW_COUNT; tabla := 'registro_accesos'; filas := n; RETURN NEXT;

    DELETE FROM usuario_permisos WHERE vence_en < now();
    GET DIAGNOSTICS n = ROW_COUNT; tabla := 'usuario_permisos'; filas := n; RETURN NEXT;

    DELETE FROM notificaciones WHERE leida_en < now() - INTERVAL '90 days';
    GET DIAGNOSTICS n = ROW_COUNT; tabla := 'notificaciones'; filas := n; RETURN NEXT;

    DELETE FROM ubicacion_conductores u
     WHERE u.actualizado_en < now() - INTERVAL '1 day'
       AND NOT EXISTS (SELECT 1 FROM conductores c WHERE c.usuario_id = u.conductor_id AND c.disponible);
    GET DIAGNOSTICS n = ROW_COUNT; tabla := 'ubicacion_conductores'; filas := n; RETURN NEXT;
END $$;


-- =====================================================================
-- 10. VISTAS PARA EL PANEL ADMINISTRATIVO
-- =====================================================================

CREATE OR REPLACE VIEW v_servicios_admin AS
SELECT s.id, s.tipo_servicio, s.tipo_vehiculo, s.estado, e.nombre AS estado_nombre,
       cli.id AS cliente_id, trim(cli.nombres || ' ' || COALESCE(cli.apellidos, '')) AS cliente,
       cli.telefono AS cliente_telefono,
       s.conductor_id, trim(con.nombres || ' ' || COALESCE(con.apellidos, '')) AS conductor,
       v.placa, s.origen_direccion, s.destino_direccion, s.distancia_km,
       s.valor_estimado, s.valor_final, s.solicitado_en, s.finalizado_en, s.cancelado_en,
       mc.descripcion AS motivo_cancelacion
  FROM servicios s
  JOIN estados e               ON e.ambito = 'servicio' AND e.codigo = s.estado
  JOIN usuarios cli             ON cli.id = s.cliente_id
  LEFT JOIN usuarios con        ON con.id = s.conductor_id
  LEFT JOIN vehiculos v         ON v.id   = s.vehiculo_id
  LEFT JOIN motivos_cancelacion mc ON mc.id = s.motivo_cancelacion_id;

CREATE OR REPLACE VIEW v_conductores_pendientes AS
SELECT c.usuario_id, u.nombres, u.apellidos, u.telefono, c.tipo_identificacion, c.numero_identificacion,
       c.categorias_licencia, v.tipo_vehiculo, v.placa, c.creado_en,
       COUNT(d.id) FILTER (WHERE d.vigente)                            AS documentos_cargados,
       COUNT(d.id) FILTER (WHERE d.vigente AND d.estado = 'pendiente')  AS documentos_por_revisar,
       COUNT(d.id) FILTER (WHERE d.vigente AND d.estado = 'aprobado')   AS documentos_aprobados
  FROM conductores c
  JOIN usuarios u                  ON u.id = c.usuario_id
  LEFT JOIN vehiculos v            ON v.conductor_id = c.usuario_id AND v.activo
  LEFT JOIN documentos_conductor d ON d.conductor_id = c.usuario_id
 WHERE c.estado = 'pendiente'
 GROUP BY c.usuario_id, u.id, v.id;

CREATE OR REPLACE VIEW v_conductores_en_linea AS
SELECT c.usuario_id, u.nombres, v.tipo_vehiculo, v.placa, ub.lat, ub.lng, ub.actualizado_en,
       EXISTS (SELECT 1 FROM servicios s WHERE s.conductor_id = c.usuario_id
                AND s.estado IN ('asignado','en_origen','en_curso')) AS ocupado
  FROM conductores c
  JOIN usuarios u                   ON u.id = c.usuario_id
  JOIN ubicacion_conductores ub     ON ub.conductor_id = c.usuario_id
  LEFT JOIN vehiculos v             ON v.conductor_id = c.usuario_id AND v.activo
 WHERE c.disponible;

CREATE OR REPLACE VIEW v_documentos_por_vencer AS
SELECT d.conductor_id, u.nombres, u.telefono, d.tipo_documento, d.fecha_vencimiento,
       d.fecha_vencimiento - CURRENT_DATE AS dias_restantes
  FROM documentos_conductor d
  JOIN usuarios u ON u.id = d.conductor_id
 WHERE d.vigente AND d.fecha_vencimiento IS NOT NULL
   AND d.fecha_vencimiento <= CURRENT_DATE + 30;

CREATE OR REPLACE VIEW v_resumen_diario AS
SELECT (s.solicitado_en AT TIME ZONE 'America/Bogota')::DATE AS fecha,
       s.tipo_servicio, s.tipo_vehiculo,
       COUNT(*)                                          AS solicitados,
       COUNT(*) FILTER (WHERE s.estado = 'finalizado')   AS finalizados,
       COUNT(*) FILTER (WHERE s.estado = 'cancelado')    AS cancelados,
       COUNT(*) FILTER (WHERE s.estado = 'expirado')     AS sin_conductor,
       ROUND(AVG(EXTRACT(EPOCH FROM (s.asignado_en - s.solicitado_en)) / 60)
             FILTER (WHERE s.asignado_en IS NOT NULL)::NUMERIC, 1) AS min_promedio_asignacion,
       COALESCE(SUM(s.valor_final) FILTER (WHERE s.estado = 'finalizado'), 0) AS total_cobrado
  FROM servicios s
 GROUP BY 1, 2, 3;

-- Historial legible de un servicio (pantalla de seguimiento y panel admin).
CREATE OR REPLACE VIEW v_historial_servicios AS
SELECT h.registro_id AS servicio_id, h.creado_en, h.estado_anterior, h.estado_nuevo,
       e.nombre AS estado_nombre, e.color, h.actor_rol, h.actor_usuario_id,
       (h.detalle->>'conductor_id')::BIGINT AS conductor_id, h.motivo
  FROM historial_estados h
  JOIN estados e ON e.ambito = h.ambito AND e.codigo = h.estado_nuevo
 WHERE h.ambito = 'servicio';

CREATE OR REPLACE VIEW v_pqrs_pendientes AS
SELECT t.id, t.tipo, t.prioridad, t.asunto, t.estado, t.usuario_id, t.servicio_id,
       t.fecha_limite_respuesta, t.fecha_limite_respuesta - CURRENT_DATE AS dias_restantes,
       (t.fecha_limite_respuesta < CURRENT_DATE) AS vencida
  FROM tickets_soporte t
 WHERE t.estado IN ('abierto','en_proceso');


-- =====================================================================
-- 10B. SEGURIDAD: PERMISOS EFECTIVOS, MENÚ Y VALIDACIÓN DE ACCESO
-- =====================================================================

-- Permisos efectivos = (permisos de sus roles activos − revocados vigentes) ∪ concedidos vigentes.
-- Es lo que el backend mete como authorities en el JWT al iniciar sesión.
CREATE OR REPLACE VIEW v_permisos_efectivos AS
SELECT ur.usuario_id, p.codigo AS permiso, p.modulo, 'rol:' || ur.rol AS origen
  FROM usuario_roles ur
  JOIN roles r         ON r.codigo = ur.rol AND r.activo
  JOIN rol_permisos rp ON rp.rol = ur.rol
  JOIN permisos p      ON p.codigo = rp.permiso AND p.activo
 WHERE NOT EXISTS (SELECT 1 FROM usuario_permisos up
                    WHERE up.usuario_id = ur.usuario_id AND up.permiso = p.codigo
                      AND up.tipo = 'revocar' AND (up.vence_en IS NULL OR up.vence_en > now()))
UNION
SELECT up.usuario_id, p.codigo, p.modulo, 'excepcion'
  FROM usuario_permisos up
  JOIN permisos p ON p.codigo = up.permiso AND p.activo
 WHERE up.tipo = 'conceder' AND (up.vence_en IS NULL OR up.vence_en > now());

-- Menú de Angular: módulos donde el usuario tiene al menos un permiso.
CREATE OR REPLACE VIEW v_menu_usuario AS
SELECT DISTINCT pe.usuario_id, m.codigo AS modulo, m.nombre, m.icono, m.ruta_frontend,
       m.modulo_padre, m.orden
  FROM v_permisos_efectivos pe
  JOIN modulos m ON m.codigo = pe.modulo AND m.activo AND m.ruta_frontend IS NOT NULL;

-- Matriz rol × permiso (para el documento de seguridad y el panel admin).
CREATE OR REPLACE VIEW v_matriz_permisos AS
SELECT p.modulo, p.codigo AS permiso, p.descripcion, p.es_sensible,
       bool_or(rp.rol = 'cliente')   AS cliente,
       bool_or(rp.rol = 'conductor') AS conductor,
       bool_or(rp.rol = 'soporte')   AS soporte,
       bool_or(rp.rol = 'admin')     AS admin
  FROM permisos p
  LEFT JOIN rol_permisos rp ON rp.permiso = p.codigo
 GROUP BY p.modulo, p.codigo, p.descripcion, p.es_sensible;

-- IPs con muchos intentos fallidos en la última hora (posible ataque de fuerza bruta).
CREATE OR REPLACE VIEW v_accesos_sospechosos AS
SELECT ip_origen, count(*) AS intentos_fallidos, count(DISTINCT identificador) AS cuentas_distintas,
       min(creado_en) AS desde, max(creado_en) AS hasta
  FROM registro_accesos
 WHERE resultado <> 'exitoso' AND creado_en > now() - INTERVAL '1 hour' AND ip_origen IS NOT NULL
 GROUP BY ip_origen
HAVING count(*) >= 10;

-- ¿El usuario tiene el permiso? (cuenta activa y no bloqueada temporalmente).
CREATE OR REPLACE FUNCTION fn_tiene_permiso(p_usuario_id BIGINT, p_permiso VARCHAR)
RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT EXISTS (
        SELECT 1 FROM v_permisos_efectivos pe
          JOIN usuarios u ON u.id = pe.usuario_id
         WHERE pe.usuario_id = p_usuario_id AND pe.permiso = p_permiso
           AND u.estado = 'activo'
           AND (u.acceso_bloqueado_hasta IS NULL OR u.acceso_bloqueado_hasta < now()));
$$;

-- Lista de permisos para el JWT.
CREATE OR REPLACE FUNCTION fn_permisos_usuario(p_usuario_id BIGINT)
RETURNS TABLE (permiso VARCHAR) LANGUAGE sql STABLE AS $$
    SELECT DISTINCT pe.permiso FROM v_permisos_efectivos pe WHERE pe.usuario_id = p_usuario_id ORDER BY 1;
$$;

-- Validación previa al login: devuelve 'exitoso' o el motivo del rechazo
-- (mismos códigos que registro_accesos.resultado).
CREATE OR REPLACE FUNCTION fn_validar_acceso(p_usuario_id BIGINT)
RETURNS VARCHAR LANGUAGE sql STABLE AS $$
    SELECT CASE
             WHEN u.id IS NULL                                  THEN 'usuario_inexistente'
             WHEN u.estado = 'eliminado'                        THEN 'cuenta_eliminada'
             WHEN u.estado = 'bloqueado'                        THEN 'cuenta_bloqueada'
             WHEN u.acceso_bloqueado_hasta > now()              THEN 'acceso_bloqueado_temporal'
             WHEN NOT EXISTS (SELECT 1 FROM usuario_roles ur JOIN roles r ON r.codigo = ur.rol AND r.activo
                               WHERE ur.usuario_id = u.id)      THEN 'sin_permiso'
             ELSE 'exitoso'
           END
      FROM (SELECT p_usuario_id AS id_buscado) b
      LEFT JOIN usuarios u ON u.id = b.id_buscado;
$$;


-- =====================================================================
-- 11. DICCIONARIO DE DATOS (COMMENT ON)
-- =====================================================================

COMMENT ON TABLE roles                        IS 'Catálogo de roles de la plataforma: cliente (usuario), conductor, soporte y admin.';
COMMENT ON TABLE tipos_vehiculo               IS 'Catálogo de vehículos admitidos (moto, taxi) y su capacidad de pasajeros.';
COMMENT ON TABLE tipos_servicio               IS 'Catálogo de servicios: transporte, domicilio, encomienda.';
COMMENT ON TABLE servicio_vehiculo_permitido  IS 'Qué tipo de vehículo puede prestar qué servicio.';
COMMENT ON TABLE estados                      IS 'Catálogo central de estados de todas las tablas (ámbito + código), con nombre y color para la UI.';
COMMENT ON TABLE transiciones_estado          IS 'Máquina de estados de todos los ámbitos: transiciones permitidas y qué actor puede hacerlas.';
COMMENT ON TABLE motivos_cancelacion          IS 'Motivos de cancelación por actor.';
COMMENT ON TABLE tipos_documento              IS 'Documentos exigidos al conductor o al vehículo y si son obligatorios por tipo de vehículo.';
COMMENT ON TABLE configuracion_sistema        IS 'Parámetros operativos editables sin desplegar código.';
COMMENT ON TABLE documentos_legales           IS 'Versiones de términos, privacidad y autorización de tratamiento de datos.';
COMMENT ON TABLE usuarios                     IS 'Toda persona registrada. Los roles se asignan en usuario_roles.';
COMMENT ON TABLE usuario_roles                IS 'Roles de cada usuario (N:M).';
COMMENT ON TABLE modulos                      IS 'Catálogo de seguridad: módulos funcionales y su entrada de menú en Angular.';
COMMENT ON TABLE permisos                     IS 'Catálogo de seguridad: acciones permitidas, código modulo.accion (authority de Spring).';
COMMENT ON TABLE rol_permisos                 IS 'Catálogo de seguridad: permisos que otorga cada rol.';
COMMENT ON TABLE usuario_permisos             IS 'Excepciones por usuario: conceder o revocar un permiso, con vencimiento opcional.';
COMMENT ON TABLE registro_accesos             IS 'Bitácora de inicios de sesión exitosos y fallidos; dispara el bloqueo temporal.';
COMMENT ON COLUMN usuarios.acceso_bloqueado_hasta IS 'Bloqueo temporal por intentos fallidos (no confundir con estado = bloqueado).';
COMMENT ON TABLE aceptaciones_legales         IS 'Prueba de la aceptación de cada documento legal (Ley 1581 de 2012).';
COMMENT ON TABLE codigos_verificacion         IS 'Códigos SMS de un solo uso (hash), con intentos y vencimiento.';
COMMENT ON TABLE dispositivos                 IS 'Dispositivos del usuario y su token de notificaciones push.';
COMMENT ON TABLE sesiones                     IS 'Refresh tokens del JWT por dispositivo, revocables.';
COMMENT ON TABLE direcciones_favoritas        IS 'Direcciones guardadas por el usuario (Casa, Trabajo...).';
COMMENT ON TABLE contactos_emergencia         IS 'Contactos a avisar cuando el usuario activa el botón SOS.';
COMMENT ON TABLE usuarios_bloqueados          IS 'Bloqueos entre usuarios: no se vuelven a emparejar.';
COMMENT ON TABLE conductores                  IS 'Perfil 1:1 del usuario que conduce: identificación, licencia, verificación y métricas.';
COMMENT ON TABLE ubicacion_conductores        IS 'Última ubicación en vivo del conductor (tabla caliente, upsert).';
COMMENT ON TABLE conductor_conexiones         IS 'Turnos en línea del conductor (inicio y fin de cada conexión).';
COMMENT ON TABLE vehiculos                    IS 'Vehículos del conductor; solo uno activo a la vez.';
COMMENT ON TABLE documentos_conductor         IS 'Documentos cargados, con revisión, vencimiento e historial de versiones.';
COMMENT ON TABLE tarifas                      IS 'Tarifas por servicio y vehículo. Inmutables: se versionan.';
COMMENT ON TABLE servicios                    IS 'Supertipo de toda solicitud (transporte, domicilio, encomienda).';
COMMENT ON TABLE servicio_domicilio           IS 'Detalle de un domicilio.';
COMMENT ON TABLE servicio_domicilio_items     IS 'Productos pedidos en un domicilio.';
COMMENT ON TABLE servicio_encomienda          IS 'Detalle de una encomienda: remitente, destinatario, paquete y PIN de entrega.';
COMMENT ON TABLE evidencias_servicio          IS 'Fotos de recogida, entrega, factura de compra o incidente.';
COMMENT ON TABLE mensajes_servicio            IS 'Chat entre cliente y conductor durante el servicio.';
COMMENT ON TABLE historial_estados            IS 'Bitácora inmutable de cada cambio de estado de cualquier ámbito.';
COMMENT ON TABLE servicio_ofertas             IS 'Solicitudes enviadas a conductores cercanos y su respuesta.';
COMMENT ON TABLE seguimiento_ubicaciones      IS 'Rastro GPS del conductor durante el servicio.';
COMMENT ON TABLE calificaciones               IS 'Calificación mutua cliente ↔ conductor por servicio.';
COMMENT ON TABLE alertas_sos                  IS 'Alertas de emergencia activadas desde la app.';
COMMENT ON TABLE tickets_soporte              IS 'PQRS: peticiones, quejas, reclamos, sugerencias y objetos olvidados.';
COMMENT ON TABLE notificaciones               IS 'Bandeja de notificaciones del usuario.';
COMMENT ON TABLE auditoria_admin              IS 'Acciones explícitas de los administradores.';
COMMENT ON TABLE auditoria_cambios            IS 'Auditoría automática de cambios sensibles (antes/después en JSON).';

COMMENT ON COLUMN usuarios.telefono              IS 'Celular colombiano en formato +57XXXXXXXXXX (el prototipo fija el indicativo +57).';
COMMENT ON COLUMN usuarios.google_sub            IS 'Identificador estable de la cuenta Google (claim sub del id_token).';
COMMENT ON COLUMN usuarios.password_hash         IS 'BCrypt. Clave de 4 dígitos de clientes y conductores, o contraseña de admin y soporte. Nunca se guarda en texto plano.';
COMMENT ON COLUMN usuarios.calificacion_promedio IS 'Calificación del usuario como cliente.';
COMMENT ON COLUMN conductores.categorias_licencia IS 'Categorías de la licencia: moto A1/A2, taxi C1/C2/C3.';
COMMENT ON COLUMN servicios.expira_busqueda_en   IS 'Momento en que la solicitud pasa a expirado si nadie la acepta.';
COMMENT ON COLUMN servicios.valor_final          IS 'Valor cobrado en efectivo al cerrar el servicio.';
COMMENT ON COLUMN servicio_encomienda.codigo_entrega IS 'PIN de 4 dígitos que el destinatario le da al conductor.';


-- =====================================================================
-- 12. DATOS SEMILLA
-- =====================================================================

INSERT INTO roles (codigo, nombre, descripcion, es_sistema) VALUES
    ('cliente',   'Usuario',       'Pide taxi, moto, domicilios y encomiendas ("usuario" en la app)', true),
    ('conductor', 'Conductor',     'Presta servicios en moto o taxi una vez aprobado',            true),
    ('soporte',   'Soporte',       'Atiende PQRS y alertas SOS; consulta servicios y usuarios',   true),
    ('admin',     'Administrador', 'Control total de la plataforma',                              true);

-- ---------- Catálogo de seguridad ----------
INSERT INTO modulos (codigo, nombre, icono, ruta_frontend, modulo_padre, orden) VALUES
    ('inicio',        'Inicio',              'home',          '/inicio',               NULL,        1),
    ('perfil',        'Mi perfil',           'user',          '/perfil',               NULL,        2),
    ('servicios',     'Servicios',           'map-pin',       '/servicios',            NULL,        3),
    ('chat',          'Chat del servicio',   'message',       NULL,                    'servicios', 4),
    ('calificaciones','Calificaciones',      'star',          NULL,                    'servicios', 5),
    ('conductor',     'Panel del conductor', 'steering',      '/conductor',            NULL,        6),
    ('vehiculos',     'Vehículos',           'car',           '/conductor/vehiculos',  'conductor', 7),
    ('documentos',    'Documentos',          'file-check',    '/conductor/documentos', 'conductor', 8),
    ('seguridad_personal','Seguridad',       'shield',        '/seguridad',            NULL,        9),
    ('pqrs',          'Ayuda y PQRS',        'help',          '/pqrs',                 NULL,       10),
    ('admin_conductores','Conductores',      'id-card',       '/admin/conductores',    NULL,       20),
    ('admin_usuarios','Usuarios',            'users',         '/admin/usuarios',       NULL,       21),
    ('admin_servicios','Monitoreo de servicios','activity',   '/admin/servicios',      NULL,       22),
    ('admin_sos',     'Alertas SOS',         'siren',         '/admin/sos',            NULL,       23),
    ('admin_pqrs',    'Gestión de PQRS',     'inbox',         '/admin/pqrs',           NULL,       24),
    ('tarifas',       'Tarifas',             'tag',           '/admin/tarifas',        NULL,       25),
    ('reportes',      'Reportes',            'bar-chart',     '/admin/reportes',       NULL,       26),
    ('configuracion', 'Configuración',       'settings',      '/admin/configuracion',  NULL,       27),
    ('seguridad',     'Roles y permisos',    'lock',          '/admin/seguridad',      NULL,       28),
    ('auditoria',     'Auditoría',           'history',       '/admin/auditoria',      NULL,       29);

INSERT INTO permisos (codigo, modulo, accion, descripcion, es_sensible) VALUES
    ('inicio.ver',                    'inicio',            'ver',               'Ver la pantalla de inicio', false),
    ('perfil.ver',                    'perfil',            'ver',               'Ver su propio perfil', false),
    ('perfil.editar',                 'perfil',            'editar',            'Editar su perfil, foto y direcciones', false),
    ('perfil.eliminar_cuenta',        'perfil',            'eliminar_cuenta',   'Eliminar su propia cuenta', true),
    ('servicios.solicitar',           'servicios',         'solicitar',         'Solicitar transporte, domicilio o encomienda', false),
    ('servicios.ver_propios',         'servicios',         'ver_propios',       'Ver sus servicios (como cliente o conductor)', false),
    ('servicios.cancelar_propios',    'servicios',         'cancelar_propios',  'Cancelar sus propios servicios', false),
    ('servicios.ver_disponibles',     'servicios',         'ver_disponibles',   'Ver solicitudes cercanas disponibles', false),
    ('servicios.aceptar',             'servicios',         'aceptar',           'Aceptar una solicitud', false),
    ('servicios.actualizar_estado',   'servicios',         'actualizar_estado', 'Llegué, iniciar, finalizar el servicio', false),
    ('chat.usar',                     'chat',              'usar',              'Escribir en el chat de su servicio activo', false),
    ('calificaciones.crear',          'calificaciones',    'crear',             'Calificar al otro participante', false),
    ('conductor.ver',                 'conductor',         'ver',               'Ver el panel del conductor', false),
    ('conductor.disponibilidad',      'conductor',         'disponibilidad',    'Conectarse y desconectarse', false),
    ('vehiculos.gestionar_propios',   'vehiculos',         'gestionar_propios', 'Registrar y editar sus vehículos', false),
    ('documentos.cargar',             'documentos',        'cargar',            'Cargar sus documentos', false),
    ('seguridad_personal.activar_sos','seguridad_personal','activar_sos',       'Activar el botón SOS', false),
    ('seguridad_personal.gestionar_contactos','seguridad_personal','gestionar_contactos','Administrar contactos de emergencia y bloqueos', false),
    ('pqrs.crear',                    'pqrs',              'crear',             'Radicar una PQRS', false),
    ('pqrs.ver_propias',              'pqrs',              'ver_propias',       'Ver sus PQRS y respuestas', false),
    ('admin_conductores.ver',         'admin_conductores', 'ver',               'Ver conductores y sus documentos', false),
    ('admin_conductores.aprobar',     'admin_conductores', 'aprobar',           'Aprobar, rechazar o suspender conductores y documentos', true),
    ('admin_usuarios.ver',            'admin_usuarios',    'ver',               'Ver usuarios', false),
    ('admin_usuarios.bloquear',       'admin_usuarios',    'bloquear',          'Bloquear y desbloquear usuarios', true),
    ('admin_usuarios.asignar_roles',  'admin_usuarios',    'asignar_roles',     'Asignar o quitar roles a usuarios', true),
    ('admin_servicios.ver',           'admin_servicios',   'ver',               'Monitorear todos los servicios', false),
    ('admin_servicios.cancelar',      'admin_servicios',   'cancelar',          'Cancelar o cerrar cualquier servicio', true),
    ('admin_sos.ver',                 'admin_sos',         'ver',               'Ver alertas SOS', false),
    ('admin_sos.atender',             'admin_sos',         'atender',           'Atender y cerrar alertas SOS', false),
    ('admin_pqrs.ver',                'admin_pqrs',        'ver',               'Ver todas las PQRS', false),
    ('admin_pqrs.responder',          'admin_pqrs',        'responder',         'Responder y cerrar PQRS', false),
    ('tarifas.ver',                   'tarifas',           'ver',               'Ver tarifas', false),
    ('tarifas.gestionar',             'tarifas',           'gestionar',         'Crear y desactivar tarifas', true),
    ('reportes.ver',                  'reportes',          'ver',               'Ver reportes y estadísticas', false),
    ('reportes.exportar',             'reportes',          'exportar',          'Exportar reportes con datos personales', true),
    ('configuracion.ver',             'configuracion',     'ver',               'Ver parámetros del sistema', false),
    ('configuracion.editar',          'configuracion',     'editar',            'Editar parámetros y documentos legales', true),
    ('seguridad.ver',                 'seguridad',         'ver',               'Ver roles, permisos y accesos', false),
    ('seguridad.gestionar',           'seguridad',         'gestionar',         'Crear roles y asignar permisos o excepciones', true),
    ('auditoria.ver',                 'auditoria',         'ver',               'Consultar la auditoría', true);

-- Matriz rol × permiso.
INSERT INTO rol_permisos (rol, permiso)
SELECT r.rol, p.codigo
  FROM (VALUES
    -- Base común a cliente y conductor
    ('cliente','inicio.ver'), ('cliente','perfil.ver'), ('cliente','perfil.editar'), ('cliente','perfil.eliminar_cuenta'),
    ('cliente','servicios.solicitar'), ('cliente','servicios.ver_propios'), ('cliente','servicios.cancelar_propios'),
    ('cliente','chat.usar'), ('cliente','calificaciones.crear'),
    ('cliente','seguridad_personal.activar_sos'), ('cliente','seguridad_personal.gestionar_contactos'),
    ('cliente','pqrs.crear'), ('cliente','pqrs.ver_propias'),

    ('conductor','inicio.ver'), ('conductor','perfil.ver'), ('conductor','perfil.editar'), ('conductor','perfil.eliminar_cuenta'),
    ('conductor','servicios.ver_propios'), ('conductor','servicios.ver_disponibles'), ('conductor','servicios.aceptar'),
    ('conductor','servicios.actualizar_estado'), ('conductor','servicios.cancelar_propios'),
    ('conductor','chat.usar'), ('conductor','calificaciones.crear'),
    ('conductor','conductor.ver'), ('conductor','conductor.disponibilidad'),
    ('conductor','vehiculos.gestionar_propios'), ('conductor','documentos.cargar'),
    ('conductor','seguridad_personal.activar_sos'), ('conductor','seguridad_personal.gestionar_contactos'),
    ('conductor','pqrs.crear'), ('conductor','pqrs.ver_propias'),

    ('soporte','inicio.ver'), ('soporte','perfil.ver'), ('soporte','perfil.editar'),
    ('soporte','admin_servicios.ver'), ('soporte','admin_servicios.cancelar'),
    ('soporte','admin_usuarios.ver'), ('soporte','admin_conductores.ver'),
    ('soporte','admin_sos.ver'), ('soporte','admin_sos.atender'),
    ('soporte','admin_pqrs.ver'), ('soporte','admin_pqrs.responder'),
    ('soporte','tarifas.ver'), ('soporte','reportes.ver')
  ) AS r(rol, permiso)
  JOIN permisos p ON p.codigo = r.permiso
UNION ALL
-- El administrador tiene todos los permisos de gestión (no los de cliente/conductor:
-- si un admin también conduce, se le asigna además el rol conductor).
SELECT 'admin', p.codigo FROM permisos p
 WHERE p.modulo IN ('inicio','perfil','admin_conductores','admin_usuarios','admin_servicios','admin_sos',
                    'admin_pqrs','tarifas','reportes','configuracion','seguridad','auditoria')
   AND p.codigo <> 'perfil.eliminar_cuenta';

INSERT INTO tipos_vehiculo (codigo, nombre, capacidad_pasajeros) VALUES
    ('moto', 'Motocicleta', 1),
    ('taxi', 'Taxi',        4);

INSERT INTO tipos_servicio (codigo, nombre, descripcion) VALUES
    ('transporte', 'Transporte', 'Traslado de pasajeros en moto o taxi'),
    ('domicilio',  'Domicilio',  'Compra o recogida de productos y entrega al cliente'),
    ('encomienda', 'Encomienda', 'Envío de paquetes entre dos puntos de la ciudad');

INSERT INTO servicio_vehiculo_permitido (tipo_servicio, tipo_vehiculo) VALUES
    ('transporte', 'moto'),
    ('transporte', 'taxi'),
    ('domicilio',  'moto'),
    ('encomienda', 'moto'),
    ('encomienda', 'taxi');

-- ---------- Catálogo central de estados (nombres tal como se muestran en la app) ----------
INSERT INTO estados (ambito, codigo, nombre, descripcion, color, es_inicial, es_final, orden) VALUES
    ('usuario',    'activo',       'Activo',               'Puede usar la app',                          '#2E9E5B', true,  false, 1),
    ('usuario',    'bloqueado',    'Bloqueado',            'Bloqueado por la administración',            '#D64545', false, false, 2),
    ('usuario',    'eliminado',    'Cuenta eliminada',     'Datos anonimizados a solicitud del usuario', '#8A94A6', false, true,  3),

    ('conductor',  'pendiente',    'En revisión',          'Registro enviado, falta aprobar documentos', '#FE7B13', true,  false, 1),
    ('conductor',  'aprobado',     'Aprobado',             'Puede conectarse y recibir solicitudes',     '#2E9E5B', false, false, 2),
    ('conductor',  'rechazado',    'Rechazado',            'Debe corregir y volver a enviar',            '#D64545', false, false, 3),
    ('conductor',  'suspendido',   'Suspendido',           'Documento vencido o decisión de soporte',    '#8A94A6', false, false, 4),

    ('documento',  'pendiente',    'Por revisar',          NULL,                                         '#FE7B13', true,  false, 1),
    ('documento',  'aprobado',     'Aprobado',             NULL,                                         '#2E9E5B', false, false, 2),
    ('documento',  'rechazado',    'Rechazado',            'Ver observación',                            '#D64545', false, false, 3),
    ('documento',  'vencido',      'Vencido',              'Pasó su fecha de vencimiento',               '#8A94A6', false, false, 4),

    ('servicio',   'buscando',     'Buscando conductor',   NULL,                                         '#FE7B13', true,  false, 1),
    ('servicio',   'asignado',     'Conductor en camino',  NULL,                                         '#02AAB4', false, false, 2),
    ('servicio',   'en_origen',    'Tu conductor llegó',   NULL,                                         '#02AAB4', false, false, 3),
    ('servicio',   'en_curso',     'En camino al destino', NULL,                                         '#03274D', false, false, 4),
    ('servicio',   'finalizado',   'Finalizado',           NULL,                                         '#2E9E5B', false, true,  5),
    ('servicio',   'cancelado',    'Cancelado',            NULL,                                         '#D64545', false, true,  6),
    ('servicio',   'expirado',     'Sin conductor',        'Nadie aceptó a tiempo',                      '#8A94A6', false, true,  7),

    ('oferta',     'enviada',      'Enviada',              NULL,                                         '#FE7B13', true,  false, 1),
    ('oferta',     'vista',        'Vista',                NULL,                                         '#02AAB4', false, false, 2),
    ('oferta',     'aceptada',     'Aceptada',             NULL,                                         '#2E9E5B', false, false, 3),
    ('oferta',     'rechazada',    'Rechazada',            NULL,                                         '#D64545', false, false, 4),
    ('oferta',     'expirada',     'Expirada',             NULL,                                         '#8A94A6', false, false, 5),

    ('alerta_sos', 'abierta',      'Alerta activa',        NULL,                                         '#D64545', true,  false, 1),
    ('alerta_sos', 'en_atencion',  'En atención',          NULL,                                         '#FE7B13', false, false, 2),
    ('alerta_sos', 'atendida',     'Atendida',             NULL,                                         '#2E9E5B', false, true,  3),
    ('alerta_sos', 'falsa_alarma', 'Falsa alarma',         NULL,                                         '#8A94A6', false, true,  4),

    ('ticket',     'abierto',      'Recibida',             NULL,                                         '#FE7B13', true,  false, 1),
    ('ticket',     'en_proceso',   'En proceso',           NULL,                                         '#02AAB4', false, false, 2),
    ('ticket',     'resuelto',     'Respondida',           NULL,                                         '#2E9E5B', false, false, 3),
    ('ticket',     'cerrado',      'Cerrada',              NULL,                                         '#8A94A6', false, true,  4);

INSERT INTO transiciones_estado (ambito, estado_origen, estado_destino, actor) VALUES
    -- usuario
    ('usuario',   'activo',    'bloqueado',  'admin'),
    ('usuario',   'bloqueado', 'activo',     'admin'),
    ('usuario',   'activo',    'eliminado',  'sistema'),
    ('usuario',   'activo',    'eliminado',  'admin'),
    ('usuario',   'bloqueado', 'eliminado',  'sistema'),
    ('usuario',   'bloqueado', 'eliminado',  'admin'),
    -- conductor
    ('conductor', 'pendiente', 'aprobado',   'admin'),
    ('conductor', 'pendiente', 'rechazado',  'admin'),
    ('conductor', 'rechazado', 'pendiente',  'conductor'),
    ('conductor', 'rechazado', 'pendiente',  'admin'),
    ('conductor', 'aprobado',  'suspendido', 'admin'),
    ('conductor', 'aprobado',  'suspendido', 'soporte'),
    ('conductor', 'aprobado',  'suspendido', 'sistema'),
    ('conductor', 'suspendido','aprobado',   'admin'),
    ('conductor', 'suspendido','pendiente',  'conductor'),
    ('conductor', 'suspendido','pendiente',  'admin'),
    -- documento
    ('documento', 'pendiente', 'aprobado',   'admin'),
    ('documento', 'pendiente', 'rechazado',  'admin'),
    ('documento', 'aprobado',  'rechazado',  'admin'),
    ('documento', 'pendiente', 'vencido',    'sistema'),
    ('documento', 'aprobado',  'vencido',    'sistema'),
    -- servicio
    ('servicio',  'buscando',  'asignado',   'conductor'),
    ('servicio',  'buscando',  'cancelado',  'cliente'),
    ('servicio',  'buscando',  'cancelado',  'soporte'),
    ('servicio',  'buscando',  'cancelado',  'admin'),
    ('servicio',  'buscando',  'expirado',   'sistema'),
    ('servicio',  'asignado',  'en_origen',  'conductor'),
    ('servicio',  'asignado',  'buscando',   'conductor'),
    ('servicio',  'asignado',  'buscando',   'sistema'),
    ('servicio',  'asignado',  'cancelado',  'cliente'),
    ('servicio',  'asignado',  'cancelado',  'soporte'),
    ('servicio',  'asignado',  'cancelado',  'admin'),
    ('servicio',  'en_origen', 'en_curso',   'conductor'),
    ('servicio',  'en_origen', 'cancelado',  'cliente'),
    ('servicio',  'en_origen', 'cancelado',  'conductor'),
    ('servicio',  'en_origen', 'cancelado',  'soporte'),
    ('servicio',  'en_origen', 'cancelado',  'admin'),
    ('servicio',  'en_curso',  'finalizado', 'conductor'),
    ('servicio',  'en_curso',  'finalizado', 'soporte'),
    ('servicio',  'en_curso',  'finalizado', 'admin'),
    ('servicio',  'en_curso',  'cancelado',  'conductor'),
    ('servicio',  'en_curso',  'cancelado',  'soporte'),
    ('servicio',  'en_curso',  'cancelado',  'admin'),
    -- alerta SOS
    ('alerta_sos','abierta',     'en_atencion', 'soporte'),
    ('alerta_sos','abierta',     'en_atencion', 'admin'),
    ('alerta_sos','abierta',     'atendida',    'soporte'),
    ('alerta_sos','abierta',     'atendida',    'admin'),
    ('alerta_sos','abierta',     'falsa_alarma','cliente'),
    ('alerta_sos','abierta',     'falsa_alarma','conductor'),
    ('alerta_sos','abierta',     'falsa_alarma','soporte'),
    ('alerta_sos','abierta',     'falsa_alarma','admin'),
    ('alerta_sos','en_atencion', 'atendida',    'soporte'),
    ('alerta_sos','en_atencion', 'atendida',    'admin'),
    ('alerta_sos','en_atencion', 'falsa_alarma','soporte'),
    ('alerta_sos','en_atencion', 'falsa_alarma','admin'),
    -- PQRS
    ('ticket',    'abierto',    'en_proceso', 'soporte'),
    ('ticket',    'abierto',    'en_proceso', 'admin'),
    ('ticket',    'abierto',    'resuelto',   'soporte'),
    ('ticket',    'abierto',    'resuelto',   'admin'),
    ('ticket',    'en_proceso', 'resuelto',   'soporte'),
    ('ticket',    'en_proceso', 'resuelto',   'admin'),
    ('ticket',    'resuelto',   'en_proceso', 'cliente'),
    ('ticket',    'resuelto',   'en_proceso', 'conductor'),
    ('ticket',    'resuelto',   'cerrado',    'cliente'),
    ('ticket',    'resuelto',   'cerrado',    'conductor'),
    ('ticket',    'resuelto',   'cerrado',    'soporte'),
    ('ticket',    'resuelto',   'cerrado',    'admin'),
    ('ticket',    'resuelto',   'cerrado',    'sistema');

INSERT INTO motivos_cancelacion (id, actor, descripcion) VALUES
    (1,  'cliente',   'Ya no necesito el servicio'),
    (2,  'cliente',   'El conductor tarda demasiado'),
    (3,  'cliente',   'Me equivoqué en la dirección'),
    (4,  'cliente',   'Encontré otro medio de transporte'),
    (5,  'cliente',   'El conductor me pidió cancelar'),
    (10, 'conductor', 'El cliente no se presentó'),
    (11, 'conductor', 'Problema con el vehículo'),
    (12, 'conductor', 'Dirección de origen incorrecta'),
    (13, 'conductor', 'Me sentí inseguro'),
    (20, 'admin',     'Cancelado por soporte'),
    (21, 'admin',     'Alerta de seguridad'),
    (30, 'sistema',   'No se encontró conductor disponible');

INSERT INTO tipos_documento (codigo, nombre, aplica_a, requiere_vencimiento, obligatorio_moto, obligatorio_taxi) VALUES
    ('cedula',              'Documento de identidad',                 'conductor', false, true,  true),
    ('licencia_conduccion', 'Licencia de conducción',                 'conductor', true,  true,  true),
    ('tarjeta_control',     'Tarjeta de control (taxista)',           'conductor', true,  false, true),
    ('antecedentes',        'Certificado de antecedentes',            'conductor', false, false, false),
    ('soat',                'SOAT',                                   'vehiculo',  true,  true,  true),
    ('tecnomecanica',       'Revisión técnico-mecánica',              'vehiculo',  true,  true,  true),
    ('tarjeta_propiedad',   'Licencia de tránsito (tarjeta de propiedad)', 'vehiculo', false, true, true),
    ('tarjeta_operacion',   'Tarjeta de operación (taxi)',            'vehiculo',  true,  false, true);

-- Valores de ejemplo en COP: ajustarlos con el equipo antes de producción.
INSERT INTO tarifas (tipo_servicio, tipo_vehiculo, tarifa_base, valor_km, valor_minuto, tarifa_minima, recargo_nocturno_pct) VALUES
    ('transporte', 'moto', 2500,  900, 100, 4000, 10),
    ('transporte', 'taxi', 4500, 1500, 200, 7000, 15),
    ('domicilio',  'moto', 3000,  800,   0, 5000, 10),
    ('encomienda', 'moto', 4000,  900,   0, 6000,  0),
    ('encomienda', 'taxi', 6000, 1500,   0, 9000,  0);

INSERT INTO configuracion_sistema (clave, valor, descripcion) VALUES
    ('ciudad_operacion',           'Buenaventura', 'Única ciudad donde opera MoviFY por ahora'),
    ('cobertura_lat',              '3.8801',  'Centro de la zona de cobertura (latitud)'),
    ('cobertura_lng',              '-77.0312', 'Centro de la zona de cobertura (longitud)'),
    ('cobertura_radio_km',         '15',  'Radio de cobertura desde el centro'),
    ('radio_busqueda_km',          '5',   'Radio para buscar conductores cercanos'),
    ('tiempo_busqueda_seg',        '120', 'Segundos antes de marcar una solicitud como expirada'),
    ('ubicacion_vigencia_seg',     '300', 'Ubicación del conductor más vieja que esto se ignora'),
    ('otp_vigencia_seg',           '300', 'Validez del código SMS'),
    ('otp_max_intentos',           '5',   'Intentos fallidos permitidos por código'),
    ('otp_max_envios_hora',        '5',   'Códigos SMS por teléfono por hora'),
    ('hora_inicio_nocturno',       '21',  'Hora (0-23, Colombia) en que empieza el recargo nocturno'),
    ('hora_fin_nocturno',          '5',   'Hora (0-23, Colombia) en que termina el recargo nocturno'),
    ('dias_para_calificar',        '7',   'Días después de finalizar en que se permite calificar'),
    ('peso_max_moto_kg',           '15',  'Peso máximo de una encomienda en moto'),
    ('max_contactos_emergencia',   '3',   'Contactos de emergencia por usuario'),
    ('retencion_seguimiento_dias', '180', 'Días que se conserva el rastro GPS de los servicios'),
    ('max_intentos_fallidos',      '5',   'Intentos de inicio de sesión fallidos antes del bloqueo temporal'),
    ('minutos_ventana_intentos',   '15',  'Ventana en la que se cuentan los intentos fallidos'),
    ('minutos_bloqueo_acceso',     '15',  'Duración del bloqueo temporal de acceso'),
    ('horas_vida_access_token',    '1',   'Vigencia del JWT de acceso'),
    ('dias_vida_refresh_token',    '30',  'Vigencia del refresh token');

INSERT INTO documentos_legales (tipo, version, url) VALUES
    ('terminos',          '1.0', 'https://movify.ds2.eleueleo.com/legal/terminos-v1.pdf'),
    ('privacidad',        '1.0', 'https://movify.ds2.eleueleo.com/legal/privacidad-v1.pdf'),
    ('tratamiento_datos', '1.0', 'https://movify.ds2.eleueleo.com/legal/tratamiento-datos-v1.pdf');


-- =====================================================================
-- 13. SEGURIDAD EN EL SERVIDOR (ejecutar una vez como superusuario; ajustar nombres)
-- =====================================================================
-- ALTER DATABASE movify_db SET timezone = 'America/Bogota';
-- REVOKE CREATE ON SCHEMA public FROM PUBLIC;
--
-- Rol de la aplicación (Spring Boot): lee y escribe, no altera estructura.
-- CREATE ROLE movify_app LOGIN PASSWORD '***';
-- GRANT CONNECT ON DATABASE movify_db TO movify_app;
-- GRANT USAGE ON SCHEMA public TO movify_app;
-- GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO movify_app;
-- REVOKE UPDATE, DELETE ON auditoria_cambios, auditoria_admin, historial_estados FROM movify_app;
-- REVOKE UPDATE ON registro_accesos FROM movify_app;
-- GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO movify_app;
-- GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO movify_app;
--
-- Rol de solo lectura (reportes, sustentación).
-- CREATE ROLE movify_lectura LOGIN PASSWORD '***';
-- GRANT CONNECT ON DATABASE movify_db TO movify_lectura;
-- GRANT USAGE ON SCHEMA public TO movify_lectura;
-- GRANT SELECT ON ALL TABLES IN SCHEMA public TO movify_lectura;
--
-- Respaldo diario (cron del VPS o paso en GitHub Actions):
--   pg_dump -Fc -d movify_db -f /backups/movify_$(date +%F).dump   (conservar 14 días)

-- =====================================================================
-- 14. CONSULTAS DE REFERENCIA PARA EL BACKEND (JdbcTemplate)
-- =====================================================================
-- Actualizar ubicación del conductor (cada 5-10 s):
--   INSERT INTO ubicacion_conductores (conductor_id, lat, lng, rumbo_grados, velocidad_kmh, actualizado_en)
--   VALUES (:id, :lat, :lng, :rumbo, :vel, now())
--   ON CONFLICT (conductor_id) DO UPDATE
--      SET lat = EXCLUDED.lat, lng = EXCLUDED.lng, rumbo_grados = EXCLUDED.rumbo_grados,
--          velocidad_kmh = EXCLUDED.velocidad_kmh, actualizado_en = now();
--
-- Aceptar un servicio sin condiciones de carrera (0 filas = otro conductor ganó):
--   SELECT set_config('movify.usuario_id', :conductorId, true), set_config('movify.actor', 'conductor', true);
--   UPDATE servicios SET estado = 'asignado', conductor_id = :conductorId, vehiculo_id = :vehiculoId
--    WHERE id = :servicioId AND estado = 'buscando';
--
-- Cotizar antes de pedir:           SELECT fn_cotizar(1::SMALLINT, 'transporte', 'moto', 3.2, 9);
-- Conductores cercanos a un origen: SELECT * FROM fn_conductores_cercanos(3.8801, -77.0312, 5, 'moto', :clienteId);
-- Solicitudes para un conductor:    SELECT * FROM fn_servicios_disponibles(:conductorId, 5);
--
-- Tareas programadas (@Scheduled):
--   cada minuto: SELECT fn_expirar_solicitudes();
--   diaria:      SELECT fn_suspender_documentos_vencidos();
--   semanal:     SELECT * FROM fn_purgar_datos();
