# Encomiendas de MoviFY

Módulo logístico de MoviFY (desarrollado por DABLIU). Este documento resume la auditoría, la arquitectura,
lo que ya funciona (MVP, migración V25) y el plan por fases. Última actualización: 2026-11-18.

## 1. Auditoría (estado antes de V25)

| Pieza | Estado | Dónde |
|---|---|---|
| Encomienda de **un** paquete (tamaño, peso ≤ 30 kg, frágil, valor declarado, quién paga) | Existía | `servicio_encomienda`, `EncomiendaServicio` |
| PIN de entrega por SMS al destinatario | Existía | `EncomiendaServicio.crear`, Twilio (`SmsEnviador`) |
| Foto de entrega obligatoria desde la app | Existía | `evidencias_servicio`, `EvidenciaServicio` |
| Máquina de estados común (buscando → asignado → en_origen → en_curso → finalizado) con historial automático | Existía | `estados`, `transiciones_estado`, `historial_estados` (trigger) |
| Ofertas a conductores cercanos, GPS en tiempo real, WebSocket | Existía | `ViajeServicio.ofertar`, `tiemporeal/` |
| Pagos (efectivo; Nequi, Daviplata, tarjeta, PSE en modo prueba) | Existía | `servicios/pagos/` |
| Tarifas por servicio y vehículo (base + km + minutos + recargos) | Existía | `tarifas`, `fn_cotizar`, `TarifaServicio` |
| Precio según **peso, volumen, bultos, frágil, valor declarado** | **Faltaba** (el precio era igual al de un viaje) | — |
| Varios bultos, medidas, peso volumétrico | **Faltaba** | — |
| Número de guía y rastreo sin cuenta | **Faltaba** | — |
| Novedades de recogida y entrega (destinatario ausente…) | **Faltaba** | — |
| Validación de capacidad por vehículo | **Parcial** (solo "el mediano va en taxi", en la app) | — |

Stack: Spring Boot 3.5 + JdbcTemplate + Flyway + PostgreSQL 18; app Ionic 8 + Angular 22 + Capacitor 8; mapas
Leaflet + OpenStreetMap + OSRM. Vehículos operando hoy: **moto y taxi** (tabla `servicio_vehiculo_permitido`).

## 2. Arquitectura elegida

Encomienda **no es una app aparte**: es un tipo de `servicios` con su detalle en `servicio_encomienda`. Así reutiliza
estados, ofertas, GPS, chat, llamadas protegidas, SOS, pagos, calificaciones y panel. V25 solo **agrega** tablas y
columnas; no cambia ni borra nada existente.

```
servicios (1) ── (1) servicio_encomienda ── (N) encomienda_paquetes
                         │                 └─ (N) encomienda_novedades (inmutables)
                         ├─ numero_guia (MVF + 10 Crockford base32), token_publico (256 bits)
                         └─ cotizacion (JSONB con el desglose con el que se cobró)
historial_estados (trigger) ── línea de tiempo de estados
evidencias_servicio ── fotos de recogida y entrega
configuracion_sistema ── parámetros del cotizador (los edita el admin)
```

### Cotizador (`servicios/encomiendas/EncomiendaCotizador`)

```
total = tarifa de la ruta (fn_cotizar: base + km + min + recargo nocturno/lluvia)
      + kilos facturables sobre los incluidos × valor por kilo   (cada kilo empezado)
      + (bultos − 1) × valor por bulto adicional
      + bultos frágiles × recargo de manejo
      + valor declarado × % de protección   (solo si el admin la habilita; hoy 0 %)
peso facturable de cada bulto = máx(peso real, largo × ancho × alto / factor volumétrico)
```

- La **misma cuenta** se usa al cotizar (`POST /api/encomiendas/cotizar`) y al crear (`POST /api/servicios`): lo que
  ve el cliente es lo que se cobra. La cotización se guarda en `servicio_encomienda.cotizacion`.
- Capacidad por vehículo (peso facturable, lado más largo, tamaño máximo) → si no cabe, la opción sale
  `disponible: false` con el motivo en palabras de la persona ("Un paquete mediano no cabe en la moto").
- Todos los valores salen de `configuracion_sistema` (`encomienda_*`). **Los precios iniciales son de prueba** y lo
  dice su descripción: hay que definirlos antes de producción.

| Parámetro | Valor inicial | Nota |
|---|---|---|
| `encomienda_factor_volumetrico` | 5000 | estándar de mensajería (cm³/kg) |
| `encomienda_kg_incluidos` | 5 | PRUEBA |
| `encomienda_valor_kg_adicional` | 600 | PRUEBA |
| `encomienda_valor_bulto_adicional` | 1000 | PRUEBA |
| `encomienda_recargo_fragil` | 1500 | PRUEBA |
| `encomienda_proteccion_pct` | 0 | 0 = no se cobra (requiere política legal aprobada) |
| `encomienda_max_valor_declarado` | 2000000 | PRUEBA |
| `encomienda_max_bultos` | 10 | |
| `encomienda_moto_max_kg` / `_taxi_max_kg` | 15 / 30 | PRUEBA, validar con la operación |
| `encomienda_moto_max_lado_cm` / `_taxi_max_lado_cm` | 50 / 100 | PRUEBA |
| `encomienda_moto_tamano_max` / `_taxi_tamano_max` | 2 / 3 | 1 sobre, 2 pequeño, 3 mediano, 4 grande |
| `encomienda_dias_rastreo_publico` | 30 | días que el enlace público sigue abierto al terminar |

## 3. API (V25)

| Método y ruta | Quién | Qué hace |
|---|---|---|
| `POST /api/encomiendas/cotizar` | con sesión | `{origen_lat, origen_lng, destino_lat, destino_lng, paquetes[]}` → `opciones[]` por vehículo con `disponible`, `motivo`, pesos y `desglose[]` |
| `POST /api/servicios` (tipo `encomienda`) | cliente | Acepta `paquetes[]` e `instrucciones_entrega`. Sin `paquetes` sigue aceptando el formato de un solo paquete (`tipo_paquete`, `peso_kg`…) que usa la web |
| `GET /api/servicios/{id}/encomienda` | quien puede ver el servicio | + `numero_guia`, `bultos`, `paquetes[]`, `cotizacion`, `novedades[]`, `instrucciones_entrega`; `url_rastreo` solo para el cliente y el personal |
| `POST /api/servicios/{id}/encomienda/novedades` | conductor asignado o personal | `{tipo, nota?, lat?, lng?}`; etapa según el estado; sin GPS usa la última ubicación del conductor; avisa al cliente |
| `GET /api/publico/encomiendas/{token}` | sin sesión (límite 60/min por IP) | Guía, estado, línea de tiempo (estados + novedades sin notas), primer nombre del conductor, vehículo, zona de origen/destino y ubicación aproximada (~100 m) solo mientras está activo. Nunca celulares, PIN, direcciones exactas ni apellidos. 410 cuando vence |

Tipos de novedad: `remitente_ausente` (solo en recogida), `destinatario_ausente` (solo en entrega),
`direccion_incorrecta`, `sin_acceso`, `rechazado`, `paquete_danado`, `retraso`, `otro` (exige nota).
Una novedad **no cambia el estado** del servicio: queda registrada y soporte decide (cancelar, reprogramar o devolver).

## 4. App móvil

- **¿Qué vas a enviar?** (`cliente/encomienda/paquete`): tamaño, contenido, peso aproximado (si no se escribe se usa
  el típico del tamaño), frágil; medidas y valor declarado plegados como opcionales; "Agregar otro paquete" con la lista
  de los agregados (se pueden quitar).
- **Confirma tu envío**: precio del backend, moto/taxi con precio o "No disponible" y el motivo, "Ver cómo se calcula el
  precio" (desglose y aviso de cobro por volumen).
- **Seguimiento**: número de guía (`MVF-XXXXX-XXXXX`), novedades del conductor y la guía incluida al compartir el código.
- **Conductor** (recogida y en viaje): botón "Reportar novedad" (`componentes/novedad-encomienda`).

## 5. Seguridad y privacidad

- Precio, capacidad, contenido prohibido (también en cada bulto y en las instrucciones) y permisos se validan en el
  backend; la app solo ayuda.
- Rastreo público con token de 256 bits (`SecureRandom`), `Cache-Control: no-store`, `X-Robots-Tag: noindex`, límite de
  peticiones y vencimiento configurable. La guía sola no da acceso a nada.
- Novedades inmutables (trigger `fn_novedades_inmutables`) con quién, rol, hora y GPS.
- El cliente no puede reportar novedades; el conductor no recibe el enlace de rastreo.

## 6. Pruebas

- `EncomiendaCotizadorTest` (11): precio por ruta, peso volumétrico, bultos, frágiles, protección, capacidad por
  vehículo, límites, redondeo y compatibilidad con el formato de la web.
- Suite completa del backend: 118 pruebas, 0 fallas (32 omitidas que necesitan la base `movify_pruebas`).
- App: `encomienda.service.spec.ts` actualizado (cotización POST, varios bultos, medidas); 132 pruebas en verde.
- Punta a punta contra el backend local (`probar_encomiendas.mjs`, fuera del repo): 40 comprobaciones en verde
  (cotizar, crear, formato web, detalle, rastreo público y privacidad, novedades, permisos y GPS de respaldo).
- Inmutabilidad: un `UPDATE` sobre `encomienda_novedades` falla con "Las novedades de una encomienda no se modifican".

## 7. Pendiente (por fases)

**Requiere decisión de Wilson ahora**
- **Web**: el flujo de encomienda de la web cotiza con `/api/tarifas/cotizar` (solo ruta). Desde V25 el backend cobra
  también peso, bultos y frágil, así que la web puede mostrar un precio menor al cobrado cuando el paquete pesa más de
  5 kg o es frágil. Opciones: actualizar la web para usar `/api/encomiendas/cotizar`, o poner esos recargos en 0.
- **Página pública `/rastreo/:token`** en la web (el backend ya la sirve) y sección de encomiendas en el panel admin
  (novedades, guía, parámetros).

**Fase 2**: entrega fallida y reprogramación como estados, devoluciones / logística inversa, recogida programada,
OTP y foto de recogida obligatorios, firma digital, libreta de direcciones, empresas (cuentas, sucursales, envíos
masivos CSV), COD, incidencias con SLA, reportes de encomiendas, notificaciones al destinatario por cada estado.

**Fase 3**: zonas de cobertura y matriz origen-destino (intermunicipal, fluvial), centros logísticos y manifiestos,
etiquetas PDF con QR, API empresarial, rutas multiparada, analítica avanzada.

## 8. Cómo correrlo

```powershell
cd movify-backend
$env:SPRING_PROFILES_ACTIVE="local"
mvn spring-boot:run          # Flyway aplica V25 sola
mvn test                     # pruebas
cd ..\MoviFY-movil
npx ng serve --port 8100     # app
npx ng test --watch=false    # pruebas de la app
```

No requiere variables de entorno nuevas. Los SMS al destinatario usan la configuración de Twilio que ya existe.
