-- MoviFY · Esquema base (V1)
-- Generado con pg_dump --schema-only a partir del volcado de producción del 31-may-2026.
-- En la base de producción NO se ejecuta: Flyway la marca como línea base (baseline-on-migrate).
-- En una base local vacía crea todas las tablas.


CREATE TABLE public.calificaciones (
    id bigint NOT NULL,
    calificacion integer NOT NULL,
    comentario character varying(500),
    fecha timestamp(6) without time zone,
    servicio_id bigint NOT NULL,
    usuario_id bigint NOT NULL
);

CREATE SEQUENCE public.calificaciones_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.calificaciones_id_seq OWNED BY public.calificaciones.id;

CREATE TABLE public.cola_offline (
    id bigint NOT NULL,
    fecha_creacion timestamp(6) without time zone,
    intentos integer,
    payload text,
    sync boolean,
    tipo character varying(255),
    usuario_id bigint NOT NULL
);

CREATE SEQUENCE public.cola_offline_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.cola_offline_id_seq OWNED BY public.cola_offline.id;

CREATE TABLE public.conductor_activo (
    conductor_id character varying(255) NOT NULL,
    foto character varying(255),
    lat double precision NOT NULL,
    lng double precision NOT NULL,
    nombre character varying(255) NOT NULL
);

CREATE TABLE public.conductores (
    id bigint NOT NULL,
    estado character varying(255),
    fecha_verificacion timestamp(6) without time zone,
    licencia character varying(255),
    soat character varying(255),
    usuario_id bigint,
    cedula_archivo bytea,
    cedula_tipo character varying(255),
    licencia_archivo bytea,
    licencia_tipo character varying(255),
    modelo character varying(255),
    placa character varying(255),
    soat_archivo bytea,
    soat_tipo character varying(255),
    tarjeta_archivo bytea,
    tarjeta_tipo character varying(255),
    cedula character varying(255),
    tarjeta_propiedad character varying(255),
    ganancias_hoy double precision DEFAULT 0,
    ganancias_semana double precision DEFAULT 0,
    viajes_hoy integer DEFAULT 0,
    viajes_total integer DEFAULT 0,
    latitud double precision,
    longitud double precision,
    en_linea boolean DEFAULT false
);

CREATE SEQUENCE public.conductores_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.conductores_id_seq OWNED BY public.conductores.id;

CREATE TABLE public.encomiendas (
    id bigint NOT NULL,
    destinatario character varying(255),
    peso double precision,
    telefono_destinatario character varying(255),
    tipo character varying(255),
    servicio_id bigint NOT NULL
);

CREATE SEQUENCE public.encomiendas_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.encomiendas_id_seq OWNED BY public.encomiendas.id;

CREATE TABLE public.fotos_evidencia (
    id bigint NOT NULL,
    descripcion character varying(500),
    fecha timestamp(6) without time zone,
    sync boolean,
    tipo character varying(255),
    url character varying(500),
    servicio_id bigint NOT NULL
);

CREATE SEQUENCE public.fotos_evidencia_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.fotos_evidencia_id_seq OWNED BY public.fotos_evidencia.id;

CREATE TABLE public.historial_pagos (
    id bigint NOT NULL,
    estado character varying(255),
    fecha_pago timestamp(6) without time zone,
    metodo character varying(255),
    monto double precision NOT NULL,
    referencia character varying(255),
    servicio_id bigint NOT NULL
);

CREATE SEQUENCE public.historial_pagos_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.historial_pagos_id_seq OWNED BY public.historial_pagos.id;

CREATE TABLE public.notificacion_viaje (
    id character varying(255) NOT NULL,
    conductor_id character varying(255) NOT NULL,
    estado character varying(255) NOT NULL,
    fecha_envio timestamp(6) without time zone,
    origen character varying(255) NOT NULL,
    servicio_id character varying(255) NOT NULL
);

CREATE TABLE public.notificaciones (
    id bigint NOT NULL,
    fecha_creacion timestamp(6) without time zone,
    leida boolean,
    mensaje character varying(1000),
    tipo character varying(255),
    titulo character varying(255),
    usuario_id bigint NOT NULL,
    servicio_id bigint
);

CREATE TABLE public.notificaciones_conductor (
    id bigint NOT NULL,
    servicio_id bigint NOT NULL,
    conductor_id bigint NOT NULL,
    estado_notificacion character varying(50) NOT NULL,
    fecha_creacion timestamp without time zone DEFAULT now(),
    fecha_respuesta timestamp without time zone
);

CREATE SEQUENCE public.notificaciones_conductor_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.notificaciones_conductor_id_seq OWNED BY public.notificaciones_conductor.id;

CREATE SEQUENCE public.notificaciones_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.notificaciones_id_seq OWNED BY public.notificaciones.id;


CREATE TABLE public.servicio_activo (
    id bigint NOT NULL,
    estado character varying(255),
    fecha_actualizacion timestamp(6) without time zone,
    fecha_inicio timestamp(6) without time zone,
    conductor_id bigint,
    servicio_id bigint NOT NULL
);

CREATE SEQUENCE public.servicio_activo_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.servicio_activo_id_seq OWNED BY public.servicio_activo.id;

CREATE TABLE public.servicio_viaje (
    id character varying(255) NOT NULL,
    conductor_id character varying(255),
    conductor_nombre character varying(255),
    destino_lat double precision NOT NULL,
    destino_lng double precision NOT NULL,
    distancia_km double precision NOT NULL,
    estado character varying(255) NOT NULL,
    fecha_creacion timestamp(6) without time zone,
    origen_lat double precision NOT NULL,
    origen_lng double precision NOT NULL,
    tarifa double precision NOT NULL,
    usuario_id character varying(255) NOT NULL
);

CREATE TABLE public.servicios (
    id bigint NOT NULL,
    destino character varying(255),
    estado character varying(255),
    fecha_finalizacion timestamp(6) without time zone,
    fecha_solicitud timestamp(6) without time zone,
    monto_total double precision,
    origen character varying(255),
    tipo character varying(255),
    conductor_id bigint,
    usuario_id bigint,
    vehiculo_id bigint,
    destino_lat double precision,
    destino_lng double precision,
    distancia_km double precision,
    fecha_fin timestamp(6) with time zone,
    fecha_inicio timestamp(6) with time zone,
    origen_lat double precision,
    origen_lng double precision,
    tarifa double precision,
    descripcion character varying(255),
    nombre_remitente character varying(150),
    telefono_remitente character varying(20),
    nombre_destinatario character varying(150),
    telefono_destinatario character varying(20),
    tipo_paquete character varying(50),
    peso_kg character varying(10),
    metodo_pago character varying(30),
    foto_paquete character varying(255),
    calificacion_usuario integer,
    comentario_usuario text,
    calificacion_conductor integer
);

CREATE SEQUENCE public.servicios_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.servicios_id_seq OWNED BY public.servicios.id;

CREATE TABLE public.sesiones (
    id bigint NOT NULL,
    activa boolean,
    dispositivo character varying(255),
    fecha_expiracion timestamp(6) without time zone,
    fecha_inicio timestamp(6) without time zone,
    token character varying(500) NOT NULL,
    usuario_id bigint NOT NULL
);

CREATE SEQUENCE public.sesiones_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.sesiones_id_seq OWNED BY public.sesiones.id;

CREATE TABLE public.tokens_recuperacion (
    id bigint NOT NULL,
    fecha_expiracion timestamp(6) without time zone NOT NULL,
    token character varying(255) NOT NULL,
    usado boolean,
    usuario_id bigint NOT NULL
);

CREATE SEQUENCE public.tokens_recuperacion_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.tokens_recuperacion_id_seq OWNED BY public.tokens_recuperacion.id;

CREATE TABLE public.ubicaciones (
    id bigint NOT NULL,
    latitud double precision NOT NULL,
    longitud double precision NOT NULL,
    sync boolean,
    "timestamp" timestamp(6) without time zone,
    servicio_id bigint NOT NULL
);

CREATE SEQUENCE public.ubicaciones_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.ubicaciones_id_seq OWNED BY public.ubicaciones.id;

CREATE TABLE public.usuarios (
    id bigint NOT NULL,
    correo character varying(255) NOT NULL,
    estado character varying(255),
    fecha_registro timestamp(6) without time zone,
    nombre character varying(255),
    password character varying(255),
    rol character varying(255),
    telefono character varying(255),
    foto character varying(255),
    token_expiracion timestamp(6) without time zone,
    token_recuperacion character varying(255)
);

CREATE SEQUENCE public.usuarios_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.usuarios_id_seq OWNED BY public.usuarios.id;

CREATE TABLE public.vehiculos (
    id bigint NOT NULL,
    estado character varying(255),
    marca character varying(255),
    modelo character varying(255),
    placa character varying(255) NOT NULL,
    conductor_id bigint NOT NULL
);

CREATE SEQUENCE public.vehiculos_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.vehiculos_id_seq OWNED BY public.vehiculos.id;

CREATE TABLE public.viajes (
    id bigint NOT NULL,
    destino character varying(255),
    estado character varying(255),
    fecha timestamp(6) without time zone,
    precio double precision,
    tipo character varying(255),
    conductor_id bigint,
    destino_lat double precision,
    destino_lng double precision,
    origen_lat double precision,
    origen_lng double precision,
    tarifa double precision,
    usuario_id bigint
);

CREATE SEQUENCE public.viajes_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.viajes_id_seq OWNED BY public.viajes.id;

ALTER TABLE ONLY public.calificaciones ALTER COLUMN id SET DEFAULT nextval('public.calificaciones_id_seq'::regclass);

ALTER TABLE ONLY public.cola_offline ALTER COLUMN id SET DEFAULT nextval('public.cola_offline_id_seq'::regclass);

ALTER TABLE ONLY public.conductores ALTER COLUMN id SET DEFAULT nextval('public.conductores_id_seq'::regclass);

ALTER TABLE ONLY public.encomiendas ALTER COLUMN id SET DEFAULT nextval('public.encomiendas_id_seq'::regclass);

ALTER TABLE ONLY public.fotos_evidencia ALTER COLUMN id SET DEFAULT nextval('public.fotos_evidencia_id_seq'::regclass);

ALTER TABLE ONLY public.historial_pagos ALTER COLUMN id SET DEFAULT nextval('public.historial_pagos_id_seq'::regclass);

ALTER TABLE ONLY public.notificaciones ALTER COLUMN id SET DEFAULT nextval('public.notificaciones_id_seq'::regclass);

ALTER TABLE ONLY public.notificaciones_conductor ALTER COLUMN id SET DEFAULT nextval('public.notificaciones_conductor_id_seq'::regclass);


ALTER TABLE ONLY public.servicio_activo ALTER COLUMN id SET DEFAULT nextval('public.servicio_activo_id_seq'::regclass);

ALTER TABLE ONLY public.servicios ALTER COLUMN id SET DEFAULT nextval('public.servicios_id_seq'::regclass);

ALTER TABLE ONLY public.sesiones ALTER COLUMN id SET DEFAULT nextval('public.sesiones_id_seq'::regclass);

ALTER TABLE ONLY public.tokens_recuperacion ALTER COLUMN id SET DEFAULT nextval('public.tokens_recuperacion_id_seq'::regclass);

ALTER TABLE ONLY public.ubicaciones ALTER COLUMN id SET DEFAULT nextval('public.ubicaciones_id_seq'::regclass);

ALTER TABLE ONLY public.usuarios ALTER COLUMN id SET DEFAULT nextval('public.usuarios_id_seq'::regclass);

ALTER TABLE ONLY public.vehiculos ALTER COLUMN id SET DEFAULT nextval('public.vehiculos_id_seq'::regclass);

ALTER TABLE ONLY public.viajes ALTER COLUMN id SET DEFAULT nextval('public.viajes_id_seq'::regclass);

ALTER TABLE ONLY public.calificaciones
    ADD CONSTRAINT calificaciones_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.cola_offline
    ADD CONSTRAINT cola_offline_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.conductor_activo
    ADD CONSTRAINT conductor_activo_pkey PRIMARY KEY (conductor_id);

ALTER TABLE ONLY public.conductores
    ADD CONSTRAINT conductores_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.encomiendas
    ADD CONSTRAINT encomiendas_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.fotos_evidencia
    ADD CONSTRAINT fotos_evidencia_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.historial_pagos
    ADD CONSTRAINT historial_pagos_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.notificacion_viaje
    ADD CONSTRAINT notificacion_viaje_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.notificaciones_conductor
    ADD CONSTRAINT notificaciones_conductor_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.notificaciones
    ADD CONSTRAINT notificaciones_pkey PRIMARY KEY (id);


ALTER TABLE ONLY public.servicio_activo
    ADD CONSTRAINT servicio_activo_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.servicio_viaje
    ADD CONSTRAINT servicio_viaje_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.servicios
    ADD CONSTRAINT servicios_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.sesiones
    ADD CONSTRAINT sesiones_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.tokens_recuperacion
    ADD CONSTRAINT tokens_recuperacion_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.ubicaciones
    ADD CONSTRAINT ubicaciones_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.vehiculos
    ADD CONSTRAINT uk_a8w6omovfa10q8eyjalqas391 UNIQUE (placa);

ALTER TABLE ONLY public.tokens_recuperacion
    ADD CONSTRAINT uk_b6ta4ijts7ya8242ds9m8unem UNIQUE (token);

ALTER TABLE ONLY public.usuarios
    ADD CONSTRAINT uk_cdmw5hxlfj78uf4997i3qyyw5 UNIQUE (correo);

ALTER TABLE ONLY public.conductores
    ADD CONSTRAINT uk_gob5vmbufyl68s4hfi9thbh9q UNIQUE (usuario_id);

ALTER TABLE ONLY public.encomiendas
    ADD CONSTRAINT uk_oa0h5rvcusbp3j5exflsw9kq6 UNIQUE (servicio_id);

ALTER TABLE ONLY public.servicio_activo
    ADD CONSTRAINT uk_ob6c3vdbdd6d9q700myq12hmn UNIQUE (servicio_id);

ALTER TABLE ONLY public.usuarios
    ADD CONSTRAINT usuarios_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.vehiculos
    ADD CONSTRAINT vehiculos_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.viajes
    ADD CONSTRAINT viajes_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.notificaciones
    ADD CONSTRAINT fk1mxbjb81ft61gwlh0kabubndc FOREIGN KEY (usuario_id) REFERENCES public.usuarios(id);

ALTER TABLE ONLY public.cola_offline
    ADD CONSTRAINT fk3i0prfdhh9dlr336250hl1wd5 FOREIGN KEY (usuario_id) REFERENCES public.usuarios(id);

ALTER TABLE ONLY public.vehiculos
    ADD CONSTRAINT fk523ge5k2sgfkw26ssb34drfct FOREIGN KEY (conductor_id) REFERENCES public.conductores(id);

ALTER TABLE ONLY public.servicio_activo
    ADD CONSTRAINT fk8c4tsjf6qhb3tsprcvqsefbng FOREIGN KEY (conductor_id) REFERENCES public.conductores(id);

ALTER TABLE ONLY public.servicios
    ADD CONSTRAINT fkc51i1g5av1e8utfl6g6xuhwu3 FOREIGN KEY (vehiculo_id) REFERENCES public.vehiculos(id);

ALTER TABLE ONLY public.tokens_recuperacion
    ADD CONSTRAINT fkcg3huoowelf1adwidv7ayfps4 FOREIGN KEY (usuario_id) REFERENCES public.usuarios(id);

ALTER TABLE ONLY public.viajes
    ADD CONSTRAINT fkev6teav9yjsdakyr116f88ecs FOREIGN KEY (conductor_id) REFERENCES public.conductores(id);

ALTER TABLE ONLY public.servicios
    ADD CONSTRAINT fkfusd4o58vy2qsc2ctjmrchvj6 FOREIGN KEY (conductor_id) REFERENCES public.conductores(id);

ALTER TABLE ONLY public.calificaciones
    ADD CONSTRAINT fkhva1qtmpv79838jrigrj7gjk1 FOREIGN KEY (servicio_id) REFERENCES public.servicios(id);

ALTER TABLE ONLY public.encomiendas
    ADD CONSTRAINT fkjylaofirnhyq78q37grtqwke FOREIGN KEY (servicio_id) REFERENCES public.servicios(id);

ALTER TABLE ONLY public.sesiones
    ADD CONSTRAINT fkkalqoqi1ewlk1dod9yn8034bx FOREIGN KEY (usuario_id) REFERENCES public.usuarios(id);

ALTER TABLE ONLY public.fotos_evidencia
    ADD CONSTRAINT fklecxy363vwh4cmk8knfrm88nv FOREIGN KEY (servicio_id) REFERENCES public.servicios(id);

ALTER TABLE ONLY public.ubicaciones
    ADD CONSTRAINT fkltfvw3pfgi2pdd3ygapokewu0 FOREIGN KEY (servicio_id) REFERENCES public.servicios(id);

ALTER TABLE ONLY public.calificaciones
    ADD CONSTRAINT fkm1utmk8ppppf1tapx0cvc5564 FOREIGN KEY (usuario_id) REFERENCES public.usuarios(id);

ALTER TABLE ONLY public.servicio_activo
    ADD CONSTRAINT fkmihg7koxlfv1rhs0cf70smrpq FOREIGN KEY (servicio_id) REFERENCES public.servicios(id);

ALTER TABLE ONLY public.servicios
    ADD CONSTRAINT fkmyixw936gjt5nb18lpmi80yxd FOREIGN KEY (usuario_id) REFERENCES public.usuarios(id);

ALTER TABLE ONLY public.historial_pagos
    ADD CONSTRAINT fkofircd4ppktaiyswg91ln75a0 FOREIGN KEY (servicio_id) REFERENCES public.servicios(id);

ALTER TABLE ONLY public.conductores
    ADD CONSTRAINT fkoj9v93wn70iy0yn3rxrkcrin7 FOREIGN KEY (usuario_id) REFERENCES public.usuarios(id);

ALTER TABLE ONLY public.notificaciones_conductor
    ADD CONSTRAINT notificaciones_conductor_conductor_id_fkey FOREIGN KEY (conductor_id) REFERENCES public.conductores(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.notificaciones_conductor
    ADD CONSTRAINT notificaciones_conductor_servicio_id_fkey FOREIGN KEY (servicio_id) REFERENCES public.servicios(id) ON DELETE CASCADE;

