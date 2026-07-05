package com.movify.backend.Controlador;

import java.net.HttpURLConnection; // Assuming you have a Servicio model
import java.net.URL; // New: Assuming a NotificacionConductor model
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity; // For atomicity
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movify.backend.Modelo.NotificacionConductor;
import com.movify.backend.Modelo.Servicio;

@RestController
@RequestMapping("/api/transporte")
public class TransporteControlador {
    // Recomendación: Inyectar una capa de servicio (TransporteService) en lugar de
    // JdbcTemplate directamente
    // private final TransporteService transporteService;

    @Autowired
    private JdbcTemplate db;

    /**
     * Busca conductores que tengan en_linea = true
     */
    @GetMapping("/conductores-activos")
    public ResponseEntity<?> conductoresActivos() {
        try {
            List<Map<String, Object>> resultado = db.queryForList("""
                    SELECT
                        c.id as conductor_id,
                        u.nombre,
                        u.foto,
                        c.latitud,
                        c.longitud,
                        c.en_linea
                    FROM conductores c
                    JOIN usuarios u ON c.usuario_id = u.id
                    WHERE c.en_linea = true AND c.estado = 'aprobado'
                    """); // Cierre de la consulta SQL
            return ResponseEntity.ok(resultado);
        } catch (Exception e) {
            java.util.Map<String, Object> err = new java.util.HashMap<>();
            err.put("error", "Error al obtener conductores");
            err.put("details", e.getMessage());
            return ResponseEntity.status(500).body(err);
        }
    }

    /**
     * Activa un conductor por su ID numérico
     */
    @PostMapping("/activar")
    public ResponseEntity<?> activarConductor(@RequestBody Map<String, Object> body) {
        try {
            Long id = Long.parseLong(body.get("conductor_id").toString());
            db.update("UPDATE conductores SET en_linea = true WHERE id = ?", id);
            return ResponseEntity.ok(Map.of("message", "Conductor " + id + " ahora está en línea"));
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of(
                    "error", "ID inválido",
                    "details", e.getMessage()));
        }
    }

    /**
     * Actualiza ubicación recibiendo lat y lng
     */
    @PostMapping("/ubicacion")
    public ResponseEntity<?> actualizarUbicacion(@RequestBody Map<String, Object> body) {
        try {
            Long id = Long.parseLong(body.get("conductor_id").toString());
            Double lat = Double.parseDouble(body.get("lat").toString());
            Double lng = Double.parseDouble(body.get("lng").toString());

            db.update("UPDATE conductores SET latitud = ?, longitud = ? WHERE id = ?",
                    lat, lng, id);
            return ResponseEntity.ok(Map.of("message", "Ubicación de conductor " + id + " actualizada"));
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of(
                    "error", "Error en actualización de ubicación",
                    "details", e.getMessage()));
        }
    }

    /**
     * USUARIO: Crea una nueva solicitud de servicio de transporte.
     * 
     * @param solicitud Los detalles de la solicitud de viaje.
     * @return La solicitud creada con su ID.
     */
    @Transactional // Asegura que la creación del servicio y las notificaciones sean atómicas
    @PostMapping("/solicitar")
    public ResponseEntity<?> crearSolicitud(@RequestBody Map<String, Object> solicitud) {
        try {
            System.out.println("📩 Datos recibidos en /solicitar: " + solicitud);

            // Validar campos obligatorios antes de parsear
            if (solicitud.get("destino_lat") == null || solicitud.get("destino_lng") == null) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Destino no seleccionado",
                    "details", "Debes seleccionar un punto de destino en el mapa"
                ));
            }
            // Validar y extraer datos de la solicitud
            Long usuarioId = Long.parseLong(solicitud.get("usuario_id").toString());
            Double origenLat = Double.parseDouble(solicitud.get("origen_lat").toString());
            Double origenLng = Double.parseDouble(solicitud.get("origen_lng").toString());
            Double destinoLat = Double.parseDouble(solicitud.get("destino_lat").toString());
            Double destinoLng = Double.parseDouble(solicitud.get("destino_lng").toString());
            Double distanciaKm = Double.parseDouble(solicitud.get("distancia_km").toString());
            boolean esRapida = solicitud.get("entrega_rapida") != null && (boolean) solicitud.get("entrega_rapida");
            Double tarifa = recalcularTarifaSeguridad(distanciaKm, esRapida);
            String tipo = solicitud.getOrDefault("tipo", "TRANSPORTE").toString();
            String descripcion = solicitud.getOrDefault("descripcion", "").toString();

            // Extraer campos adicionales con valores por defecto si no vienen
            String nombreRemitente = solicitud.getOrDefault("nombre_remitente", "").toString();
            String telefonoRemitente = solicitud.getOrDefault("telefono_remitente", "").toString();
            String nombreDestinatario = solicitud.getOrDefault("nombre_destinatario", "").toString();
            String telefonoDestinatario = solicitud.getOrDefault("telefono_destinatario", "").toString();
            String tipoPaquete = solicitud.getOrDefault("tipo_paquete", "").toString();
            String pesoKg = solicitud.getOrDefault("peso_kg", "").toString();
            String metodoPago = solicitud.getOrDefault("metodo_pago", "efectivo").toString();

            String sql = """
                    INSERT INTO servicios (
                        usuario_id, origen_lat, origen_lng, destino_lat, destino_lng,
                        distancia_km, tarifa, estado, tipo, descripcion, fecha_solicitud,
                        nombre_remitente, telefono_remitente,
                        nombre_destinatario, telefono_destinatario,
                        tipo_paquete, peso_kg, metodo_pago
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """;

            Long servicioId = db.queryForObject(sql, Long.class,
                    usuarioId, origenLat, origenLng, destinoLat, destinoLng,
                    distanciaKm, tarifa, Servicio.EstadoServicio.PENDIENTE.name(), tipo, descripcion,
                    nombreRemitente, telefonoRemitente,
                    nombreDestinatario, telefonoDestinatario,
                    tipoPaquete, pesoKg, metodoPago);

            // --- Lógica para buscar y notificar conductores cercanos ---
            // 1. Buscar conductores activos cercanos al origen del servicio
            // Fixed: Use origenLng, origenLat for ST_MakePoint
            List<Map<String, Object>> conductoresCercanos = db.queryForList(
                    """
                                SELECT id as conductor_id
                                FROM conductores
                                WHERE en_linea = true
                                AND ST_DWithin(
                                    ST_SetSRID(ST_MakePoint(
                                        CAST(longitud AS double precision),
                                        CAST(latitud AS double precision)
                                    ), 4326)::geography,
                                    ST_SetSRID(ST_MakePoint(
                                        CAST(? AS double precision),
                                        CAST(? AS double precision)
                                    ), 4326)::geography,
                                    5000)
                            """,
                    origenLng, origenLat);
            System.out.println("🔍 Conductores encontrados en radio de 5km: " + conductoresCercanos);
            if (conductoresCercanos.isEmpty()) {
                // Fallback 1: Buscar cualquier conductor que esté en línea (sin importar
                // distancia)
                conductoresCercanos = db
                        .queryForList("SELECT id as conductor_id FROM conductores WHERE en_linea = true");
                System.out.println("⚠️ No hubo conductores cerca. Fallback 1 (Online): " + conductoresCercanos);
            }

            if (conductoresCercanos.isEmpty()) {
                // Fallback 2: Buscar ABSOLUTAMENTE CUALQUIER conductor registrado y activarlo a
                // la fuerza
                conductoresCercanos = db.queryForList("SELECT id as conductor_id FROM conductores LIMIT 1");

                if (!conductoresCercanos.isEmpty()) {
                    Long forcedId = Long.valueOf(conductoresCercanos.get(0).get("conductor_id").toString());
                    // Lo movemos cerca del usuario para que aparezca en el mapa inmediatamente
                    db.update("UPDATE conductores SET en_linea = true, latitud = ?, longitud = ? WHERE id = ?",
                            origenLat + 0.005, origenLng + 0.005, forcedId);
                    System.out.println(
                            "🤖 SIMULACIÓN: Se forzó al conductor " + forcedId + " a estar online para la prueba.");
                }
            }

            // --- FALLBACK 3: Si no existe NI UN SOLO conductor, creamos uno de prueba ---
            if (conductoresCercanos.isEmpty()) {
                System.out.println("🤖 SIMULACIÓN: Creando conductor de emergencia...");
                db.update("""
                            INSERT INTO usuarios (nombre, correo, password, rol, estado)
                            VALUES ('Simulador Moto', 'simula@movify.com', '123', 'conductor', 'activo')
                            ON CONFLICT (correo) DO NOTHING
                        """);
                db.update(
                        """
                                    INSERT INTO conductores (usuario_id, placa, modelo, en_linea, latitud, longitud, estado)
                                    SELECT id, 'SIM-001', 'Yamaha Simulada', true, ?, ?, 'aprobado'
                                    FROM usuarios WHERE correo = 'simula@movify.com' LIMIT 1
                                """,
                        origenLat + 0.001, origenLng + 0.001);
                conductoresCercanos = db
                        .queryForList("SELECT id as conductor_id FROM conductores WHERE en_linea = true LIMIT 1");
            }

            // 2. Crear notificaciones para cada conductor encontrado
            for (Map<String, Object> conductor : conductoresCercanos) {
                Long conductorCercanoId = Long.valueOf(conductor.get("conductor_id").toString());
                System.out.println("🔔 NOTIFICACIÓN CREADA: Enviando viaje #" + servicioId + " al conductor ID: "
                        + conductorCercanoId);
                String insertNotifSql = "INSERT INTO notificaciones_conductor (servicio_id, conductor_id, estado_notificacion, fecha_creacion) VALUES (?, ?, ?, NOW())";
                db.update(insertNotifSql, servicioId, conductorCercanoId,
                        NotificacionConductor.EstadoNotificacion.ENVIADA.name());
            }

            // --- SIMULACIÓN: Viaje completo simulado ---
            if (!conductoresCercanos.isEmpty()) {
                // Obtenemos el ID de forma segura
                Long conductorSimuladoId = Long.valueOf(conductoresCercanos.get(0).get("conductor_id").toString());

                new Thread(() -> {
                    try {
                        // 1. Espera de 10 segundos antes de la simulación automática
                        Thread.sleep(10000);

                        // Verificar si un conductor real ya aceptó el viaje
                        String checkSql = "SELECT estado FROM servicios WHERE id = ?";
                        String estadoActual = db.queryForObject(checkSql, String.class, servicioId);

                        if (Servicio.EstadoServicio.PENDIENTE.name().equals(estadoActual)) {
                            System.out.println(
                                    "🤖 SIMULACIÓN: Ningún conductor real aceptó. Iniciando auto-asignación...");

                            db.update("UPDATE servicios SET estado = ?, conductor_id = ? WHERE id = ?",
                                    Servicio.EstadoServicio.ACEPTADO.name(), conductorSimuladoId, servicioId);
                            db.update(
                                    "UPDATE notificaciones_conductor SET estado_notificacion = ?, fecha_respuesta = NOW() WHERE servicio_id = ? AND conductor_id = ?",
                                    NotificacionConductor.EstadoNotificacion.ACEPTADA.name(), servicioId,
                                    conductorSimuladoId);

                            // Obtener posición actual del conductor para iniciar ruta
                            List<Map<String, Object>> posList = db.queryForList(
                                    "SELECT latitud, longitud FROM conductores WHERE id = ?", conductorSimuladoId);
                            if (posList.isEmpty())
                                return;

                            double cLat = Double.valueOf(posList.get(0).get("latitud").toString());
                            double cLng = Double.valueOf(posList.get(0).get("longitud").toString());

                            // 2. HACIA EL ORIGEN (Ruta real por calles)
                            List<double[]> rutaAlOrigen = obtenerRutaOSRM(cLat, cLng, origenLat, origenLng);
                            simularRecorridoConEstado(conductorSimuladoId, servicioId, rutaAlOrigen,
                                    Servicio.EstadoServicio.ACEPTADO);

                            System.out.println("🤖 Conductor simulado llegó al usuario.");
                            Thread.sleep(2000); // Pausa de recogida

                            // 3. HACIA EL DESTINO (Ruta real por calles)
                            db.update("UPDATE servicios SET estado = ? WHERE id = ?",
                                    Servicio.EstadoServicio.EN_CAMINO.name(), servicioId);
                            List<double[]> rutaAlDestino = obtenerRutaOSRM(origenLat, origenLng, destinoLat,
                                    destinoLng);
                            simularRecorridoConEstado(conductorSimuladoId, servicioId, rutaAlDestino,
                                    Servicio.EstadoServicio.EN_CAMINO);

                            // 4. FINALIZAR
                            Thread.sleep(2000);
                            db.update("UPDATE servicios SET estado = ?, fecha_fin = NOW() WHERE id = ?",
                                    Servicio.EstadoServicio.FINALIZADO.name(), servicioId);

                            // ✅ Actualizar estadísticas del conductor simulado
                            db.update("""
                                        UPDATE conductores
                                        SET viajes_hoy = viajes_hoy + 1, viajes_total = viajes_total + 1,
                                            ganancias_hoy = ganancias_hoy + (SELECT tarifa FROM servicios WHERE id = ?)
                                        WHERE id = ?
                                    """, servicioId, conductorSimuladoId);

                            System.out.println("🤖 SIMULACIÓN COMPLETADA: Viaje #" + servicioId + " finalizado.");
                        } else {
                            System.out.println("✅ El viaje #" + servicioId
                                    + " fue aceptado por un conductor real. Simulación cancelada.");
                        }

                    } catch (Exception e) {
                        System.err.println("❌ Error en simulación: " + e.getMessage());
                    }
                }).start();
            }
            return ResponseEntity.ok(Map.of("id", servicioId, "message", "Solicitud creada con éxito."));
        } catch (Exception e) {
            e.printStackTrace(); // Esto mostrará el error completo en la consola de Java
            java.util.Map<String, Object> err = new java.util.HashMap<>();
            err.put("error", "Error al crear solicitud");
            err.put("details", e.getMessage());
            return ResponseEntity.status(400).body(err);
        }
    }

    /**
     * Valida o recalcula la tarifa en el servidor para evitar manipulaciones
     * y aplica recargos por hora del día.
     */
    private Double recalcularTarifaSeguridad(Double distancia, boolean entregaRapida) {
        double base = 2500;
        double kmPrice = 1200;
        // Estimación de tiempo: 2.5 min por km (tráfico promedio)
        double estimatedTimeMinutes = distancia * 2.5;
        double timePrice = 150;

        double total = base + (distancia * kmPrice) + (estimatedTimeMinutes * timePrice);

        LocalTime ahora = LocalTime.now();
        double factor = 1.0;

        // Horas pico en el servidor
        if ((ahora.getHour() >= 7 && ahora.getHour() <= 9) ||
                (ahora.getHour() >= 17 && ahora.getHour() <= 19)) {
            factor = 1.4;
        } else if (ahora.getHour() >= 22 || ahora.getHour() <= 5) {
            factor = 1.2;
        }

        double calculo = total * factor;
        if (entregaRapida) {
            calculo *= 1.20; // ✅ Surcharge del 20%
        }

        return Math.ceil(calculo / 100) * 100;
    }

    /**
     * CONDUCTOR: Obtiene solicitudes pendientes para un conductor específico.
     * Idealmente, esto sería reemplazado por WebSockets.
     */
    @GetMapping("/solicitudes-pendientes/{conductorId}")
    public ResponseEntity<?> getSolicitudesPendientes(@PathVariable("conductorId") Long conductorId) {
        try {
            // ✅ Validar tasa de cancelación antes de buscar solicitudes
            String sqlTasa = """
                        SELECT COALESCE(
                            (COUNT(CASE WHEN estado = 'CANCELADO' THEN 1 END) * 100.0 / NULLIF(COUNT(*), 0)),
                        0) as tasa
                        FROM servicios
                        WHERE conductor_id = ? AND (estado = 'FINALIZADO' OR estado = 'CANCELADO')
                    """;
            Double tasa = db.queryForObject(sqlTasa, Double.class, conductorId);

            if (tasa != null && tasa > 20.0) {
                return ResponseEntity.status(403).body(Map.of(
                        "error", "Sanción por alta cancelación",
                        "tasaActual", tasa,
                        "message", "Tu tasa de cancelación supera el 20%. No recibirás solicitudes temporalmente."));
            }

            // Buscar notificaciones ENVIADAS para este conductor que aún no han sido
            // respondidas
            // y que el servicio asociado sigue PENDIENTE.
            List<Map<String, Object>> solicitudes = db.queryForList("""
                    SELECT
                        nc.id as notificacion_id,
                        s.id as servicio_id,
                        s.origen_lat, s.origen_lng, s.destino_lat, s.destino_lng,
                        s.distancia_km, s.tarifa,
                        u.nombre as usuario_nombre,
                        u.telefono as usuario_telefono
                    FROM servicios s
                    JOIN notificaciones_conductor nc ON s.id = nc.servicio_id
                    LEFT JOIN usuarios u ON s.usuario_id = u.id
                    WHERE nc.conductor_id = ?
                    AND nc.estado_notificacion = ?
                    AND s.estado = ?
                    ORDER BY nc.fecha_creacion DESC
                    """, conductorId, NotificacionConductor.EstadoNotificacion.ENVIADA.name(),
                    Servicio.EstadoServicio.PENDIENTE.name());
            return ResponseEntity.ok(solicitudes);
        } catch (Exception e) {
            java.util.Map<String, Object> error = new java.util.HashMap<>();
            error.put("error", "Error al obtener solicitudes pendientes");
            error.put("details", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * USUARIO: Consulta el estado actual de un servicio.
     */
    @GetMapping("/servicio/{servicioId}")
    public ResponseEntity<?> getEstadoServicio(@PathVariable("servicioId") Long servicioId) {
        try {
            // ✅ Primero consultar el servicio sin JOINs para evitar errores
            // cuando conductor_id recién fue asignado y los JOINs aún son inestables
            String sqlBase = """
                        SELECT
                            s.id, s.estado, s.conductor_id, s.tipo, s.descripcion,
                            u.nombre as usuario_nombre,
                            s.origen_lat, s.origen_lng, s.destino_lat, s.destino_lng,
                            s.distancia_km, s.tarifa
                        FROM servicios s
                        JOIN usuarios u ON s.usuario_id = u.id
                        WHERE s.id = ?
                    """;
            List<Map<String, Object>> servicios = db.queryForList(sqlBase, servicioId);

            if (servicios.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("error", "Servicio no encontrado"));
            }

            // Trabajamos con un HashMap mutable para poder añadir campos
            java.util.Map<String, Object> servicio = new java.util.HashMap<>(servicios.get(0));

            // ✅ Si hay conductor asignado, hacer el JOIN por separado con try/catch
            Object conductorIdObj = servicio.get("conductor_id");
            if (conductorIdObj != null) {
                try {
                    Long conductorId = Long.parseLong(conductorIdObj.toString());
                    String sqlConductor = """
                                SELECT
                                    c.latitud as conductor_lat,
                                    c.longitud as conductor_lng,
                                    uc.nombre as conductor_nombre,
                                    uc.foto as conductor_foto,
                                    uc.telefono as conductor_telefono
                                FROM conductores c
                                JOIN usuarios uc ON c.usuario_id = uc.id
                                WHERE c.id = ?
                            """;
                    List<Map<String, Object>> conductorData = db.queryForList(sqlConductor, conductorId);
                    if (!conductorData.isEmpty()) {
                        servicio.putAll(conductorData.get(0));
                    }
                } catch (Exception ex) {
                    // ✅ Si falla el JOIN del conductor, no explotar — devolver el servicio sin
                    // datos del conductor
                    System.err.println("⚠️ No se pudo obtener datos del conductor: " + ex.getMessage());
                    servicio.put("conductor_lat", null);
                    servicio.put("conductor_lng", null);
                    servicio.put("conductor_nombre", "En camino...");
                    servicio.put("conductor_foto", null);
                }
            } else {
                // Sin conductor aún
                servicio.put("conductor_lat", null);
                servicio.put("conductor_lng", null);
                servicio.put("conductor_nombre", null);
                servicio.put("conductor_foto", null);
            }

            return ResponseEntity.ok(servicio);

        } catch (Exception e) {
            e.printStackTrace();
            java.util.Map<String, Object> error = new java.util.HashMap<>();
            error.put("error", "Error al consultar estado del servicio");
            error.put("details", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * CONDUCTOR/USUARIO: Actualiza el estado de un servicio
     * (aceptado/rechazado/finalizado).
     */
    @Transactional // Asegura que las actualizaciones sean atómicas
    @PatchMapping("/servicio/{servicioId}/estado")
    public ResponseEntity<?> responderSolicitud(@PathVariable("servicioId") Long servicioId,
            @RequestBody Map<String, Object> body) {
        try {
            String estadoInput = body.get("estado").toString();
            Long conductorId = body.containsKey("conductor_id") ? Long.parseLong(body.get("conductor_id").toString())
                    : null;

            // ✅ Sincronización con Enum: Validación centralizada
            Servicio.EstadoServicio nuevoEstado;
            try {
                nuevoEstado = Servicio.EstadoServicio.valueOf(estadoInput);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.status(400).body(Map.of("error", "Estado de servicio no válido: " + estadoInput));
            }

            switch (nuevoEstado) {
                case ACEPTADO:
                    // Intentar aceptar el servicio, solo si está PENDIENTE
                    int updatedRows = db.update(
                            "UPDATE servicios SET estado = ?, conductor_id = ? WHERE id = ? AND estado = ?",
                            Servicio.EstadoServicio.ACEPTADO.name(), conductorId, servicioId,
                            Servicio.EstadoServicio.PENDIENTE.name());

                    if (updatedRows > 0) {
                        db.update(
                                "UPDATE notificaciones_conductor SET estado_notificacion = ?, fecha_respuesta = NOW() WHERE servicio_id = ? AND conductor_id != ?",
                                NotificacionConductor.EstadoNotificacion.RECHAZADA_POR_OTRO.name(), servicioId,
                                conductorId);
                        db.update(
                                "UPDATE notificaciones_conductor SET estado_notificacion = ?, fecha_respuesta = NOW() WHERE servicio_id = ? AND conductor_id = ?",
                                NotificacionConductor.EstadoNotificacion.ACEPTADA.name(), servicioId, conductorId);

                        // INICIAR SIMULACIÓN DE RECORRIDO POST-ACEPTACIÓN
                        new Thread(() -> {
                            try {
                                Map<String, Object> s = db.queryForMap(
                                        "SELECT origen_lat, origen_lng, destino_lat, destino_lng FROM servicios WHERE id = ?",
                                        servicioId);
                                Map<String, Object> c = db
                                        .queryForMap("SELECT latitud, longitud FROM conductores WHERE id = ?",
                                                conductorId);

                                double oLat = (double) s.get("origen_lat");
                                double oLng = (double) s.get("origen_lng");
                                double dLat = (double) s.get("destino_lat");
                                double dLng = (double) s.get("destino_lng");
                                double cLat = (double) c.get("latitud");
                                double cLng = (double) c.get("longitud");

                                // 1. EN_CAMINO_AL_USUARIO: Ruta hacia el punto de recogida
                                db.update("UPDATE servicios SET estado = ? WHERE id = ?",
                                        Servicio.EstadoServicio.EN_CAMINO_AL_USUARIO.name(), servicioId);
                                System.out.println("🛵 Conductor #" + conductorId + " en camino al usuario.");
                                List<double[]> rutaAlOrigen = obtenerRutaOSRM(cLat, cLng, oLat, oLng);
                                simularRecorridoConEstado(conductorId, servicioId, rutaAlOrigen,
                                        Servicio.EstadoServicio.EN_CAMINO_AL_USUARIO);

                                // 2. LLEGO_AL_ORIGEN: El conductor está afuera
                                db.update("UPDATE servicios SET estado = ? WHERE id = ?",
                                        Servicio.EstadoServicio.LLEGO_AL_ORIGEN.name(), servicioId);
                                System.out.println("📍 Conductor llegó al punto de recogida.");
                                Thread.sleep(5000); // Espera 5 segundos para que el usuario suba

                                // 3. EN_VIAJE: Trayecto al destino final
                                db.update("UPDATE servicios SET estado = ? WHERE id = ?",
                                        Servicio.EstadoServicio.EN_VIAJE.name(), servicioId);
                                System.out.println("🛣️ Viaje #" + servicioId + " en curso al destino.");
                                List<double[]> rutaAlDestino = obtenerRutaOSRM(oLat, oLng, dLat, dLng);
                                simularRecorridoConEstado(conductorId, servicioId, rutaAlDestino,
                                        Servicio.EstadoServicio.EN_VIAJE);

                                // ESTADO: FINALIZADO
                                db.update("UPDATE servicios SET estado = ?, fecha_fin = NOW() WHERE id = ?",
                                        Servicio.EstadoServicio.FINALIZADO.name(), servicioId);

                                // ✅ Actualizar estadísticas del conductor real
                                db.update(
                                        """
                                                    UPDATE conductores
                                                    SET viajes_hoy = viajes_hoy + 1, viajes_total = viajes_total + 1,
                                                        ganancias_hoy = ganancias_hoy + (SELECT tarifa FROM servicios WHERE id = ?)
                                                    WHERE id = ?
                                                """,
                                        servicioId, conductorId);

                                System.out.println("🏁 Viaje #" + servicioId + " finalizado con éxito.");

                            } catch (Exception e) {
                                System.err.println("❌ Error en simulación de viaje: " + e.getMessage());
                            }
                        }).start();

                        return ResponseEntity
                                .ok(Map.of("message", "Servicio aceptado con éxito.", "servicioId", servicioId));
                    } else {
                        db.update(
                                "UPDATE notificaciones_conductor SET estado_notificacion = ?, fecha_respuesta = NOW() WHERE servicio_id = ? AND conductor_id = ?",
                                NotificacionConductor.EstadoNotificacion.RECHAZADA_POR_OTRO.name(), servicioId,
                                conductorId);
                        return ResponseEntity.status(409).body(
                                Map.of("message",
                                        "El servicio ya no está disponible o fue aceptado por otro conductor."));
                    }

                case RECHAZADO:
                    // Marcar la notificación de este conductor como RECHAZADA
                    db.update(
                            "UPDATE notificaciones_conductor SET estado_notificacion = ?, fecha_respuesta = NOW() WHERE servicio_id = ? AND conductor_id = ?",
                            NotificacionConductor.EstadoNotificacion.RECHAZADA.name(), servicioId, conductorId);
                    return ResponseEntity.ok(Map.of("message", "Servicio rechazado."));

                case FINALIZADO:
                    if (conductorId == null) {
                        return ResponseEntity.status(400).body("Error: conductor_id es requerido.");
                    }
                    db.update("UPDATE servicios SET estado = ?, fecha_fin = NOW() WHERE id = ? AND conductor_id = ?",
                            Servicio.EstadoServicio.FINALIZADO.name(), servicioId, conductorId);

                    // ✅ Asegurar actualización de estadísticas en cierre manual
                    db.update("""
                                UPDATE conductores SET viajes_hoy = viajes_hoy + 1, viajes_total = viajes_total + 1,
                                ganancias_hoy = ganancias_hoy + (SELECT tarifa FROM servicios WHERE id = ?) WHERE id = ?
                            """, servicioId, conductorId);

                    return ResponseEntity.ok(Map.of("message", "Servicio finalizado."));

                case CANCELADO:
                    if (conductorId != null) {
                        // ✅ Si el conductor cancela, guardamos su ID para el historial de
                        // penalizaciones
                        db.update("UPDATE servicios SET estado = ?, conductor_id = ? WHERE id = ?",
                                Servicio.EstadoServicio.CANCELADO.name(), conductorId, servicioId);
                    } else {
                        db.update("UPDATE servicios SET estado = ? WHERE id = ?",
                                Servicio.EstadoServicio.CANCELADO.name(), servicioId);
                    }
                    db.update(
                            "UPDATE notificaciones_conductor SET estado_notificacion = ?, fecha_respuesta = NOW() WHERE servicio_id = ? AND estado_notificacion = ?",
                            NotificacionConductor.EstadoNotificacion.RECHAZADA_POR_OTRO.name(), servicioId,
                            NotificacionConductor.EstadoNotificacion.ENVIADA.name());
                    return ResponseEntity.ok(Map.of("message", "Servicio cancelado."));

                case EN_CAMINO:
                case EN_CAMINO_AL_USUARIO:
                case LLEGO_AL_ORIGEN:
                case PAQUETE_RECOGIDO: // ✅ Nuevo estado aceptado
                case EN_VIAJE:
                    // ✅ Manejo agrupado de estados intermedios
                    db.update("UPDATE servicios SET estado = ? WHERE id = ?", nuevoEstado.name(), servicioId);
                    return ResponseEntity.ok(Map.of("message", "Estado actualizado a " + nuevoEstado));

                default:
                    return ResponseEntity.status(400).body("Estado no permitido en esta operación.");
            }
        } catch (Exception e) {
            java.util.Map<String, Object> err = new java.util.HashMap<>();
            err.put("error", "Error al responder solicitud");
            err.put("details", e.getMessage());
            return ResponseEntity.status(400).body(err);
        }
    }

    // ==========================================
    // AYUDANTES PARA SIMULACIÓN REALISTA POR CALLES
    // ==========================================

    private List<double[]> obtenerRutaOSRM(double lat1, double lng1, double lat2, double lng2) {
        List<double[]> coordinates = new ArrayList<>();
        try {
            // URL de OSRM (Public Demo Server) - Formato: longitude,latitude
            String urlStr = String.format(Locale.US,
                    "http://router.project-osrm.org/route/v1/driving/%f,%f;%f,%f?overview=full&geometries=geojson",
                    lng1, lat1, lng2, lat2);

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(conn.getInputStream());
            JsonNode coordsNode = root.path("routes").get(0).path("geometry").path("coordinates");

            for (JsonNode node : coordsNode) {
                // OSRM devuelve [lng, lat], nosotros guardamos [lat, lng]
                coordinates.add(new double[] { node.get(1).asDouble(), node.get(0).asDouble() });
            }
        } catch (Exception e) {
            System.err.println("⚠️ OSRM Falló, usando línea recta como respaldo: " + e.getMessage());
            coordinates.add(new double[] { lat1, lng1 });
            coordinates.add(new double[] { lat2, lng2 });
        }
        return coordinates;
    }

    private void simularRecorrido(Long conductorId, List<double[]> ruta) throws InterruptedException {
        if (ruta == null || ruta.isEmpty())
            return;

        // Dividimos el trayecto en aprox 10 actualizaciones para que sea fluido
        int totalPuntos = ruta.size();
        int salto = Math.max(1, totalPuntos / 10);

        for (int i = 0; i < totalPuntos; i += salto) {
            double[] p = ruta.get(i);
            db.update("UPDATE conductores SET latitud = ?, longitud = ? WHERE id = ?", p[0], p[1], conductorId);
            Thread.sleep(3000); // 3 segundos entre pasos para sincronizar con el mapa
        }

        // Asegurar llegada al punto final exacto
        double[] last = ruta.get(totalPuntos - 1);
        db.update("UPDATE conductores SET latitud = ?, longitud = ? WHERE id = ?", last[0], last[1], conductorId);
    }

    /**
     * Simulación que actualiza tanto la posición como el estado del servicio en
     * cada paso.
     */
    private void simularRecorridoConEstado(Long conductorId, Long servicioId, List<double[]> ruta,
            Servicio.EstadoServicio estado) throws InterruptedException {
        if (ruta == null || ruta.isEmpty())
            return;

        int totalPuntos = ruta.size();
        int salto = Math.max(1, totalPuntos / 10);

        for (int i = 0; i < totalPuntos; i += salto) {
            double[] p = ruta.get(i);
            db.update("UPDATE conductores SET latitud = ?, longitud = ? WHERE id = ?", p[0], p[1], conductorId);
            db.update("UPDATE servicios SET estado = ? WHERE id = ?", estado.name(), servicioId);
            Thread.sleep(3000);
        }

        double[] last = ruta.get(totalPuntos - 1);
        db.update("UPDATE conductores SET latitud = ?, longitud = ? WHERE id = ?", last[0], last[1], conductorId);
        db.update("UPDATE servicios SET estado = ? WHERE id = ?", estado.name(), servicioId);
    }

    /**
     * USUARIO: Califica un servicio finalizado.
     */
    @PostMapping("/calificar")
    public ResponseEntity<?> calificarServicio(@RequestBody Map<String, Object> body) {
        try {
            Long servicioId = Long.parseLong(body.get("servicio_id").toString());
            Long usuarioId = Long.parseLong(body.get("usuario_id").toString());
            Integer puntuacion = Integer.parseInt(body.get("puntos").toString());
            String comentario = body.getOrDefault("comentario", "").toString();

            db.update(
                    "INSERT INTO calificaciones (servicio_id, usuario_id, calificacion, comentario, fecha) VALUES (?, ?, ?, ?, NOW())",
                    servicioId, usuarioId, puntuacion, comentario);

            return ResponseEntity.ok(Map.of("message", "Calificación guardada."));
        } catch (Exception e) {
            return ResponseEntity.status(400)
                    .body(Map.of("error", "Error al guardar calificación", "details", e.getMessage()));
        }
    }

    /**
     * CONDUCTOR: Estadísticas de ganancias agrupadas por período.
     * GET /api/transporte/ganancias-stats/{conductorId}?filtro=mes
     */
    @GetMapping("/ganancias-stats/{conductorId}")
    public ResponseEntity<?> getGananciasStats(
            @PathVariable("conductorId") Long conductorId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "mes") String filtro) {
        try {
            // ── Ganancias HOY ──────────────────────────────────────────
            Map<String, Object> hoy = db.queryForMap("""
                    SELECT
                        COALESCE(SUM(tarifa), 0)  AS ganancias_hoy,
                        COUNT(*)                  AS viajes_hoy
                    FROM servicios
                    WHERE conductor_id = ?
                      AND estado = 'FINALIZADO'
                      AND DATE(fecha_fin) = CURRENT_DATE
                    """, conductorId);

            Map<String, Object> ayer = db.queryForMap("""
                    SELECT COALESCE(SUM(tarifa), 0) AS ganancias_ayer
                    FROM servicios
                    WHERE conductor_id = ?
                      AND estado = 'FINALIZADO'
                      AND DATE(fecha_fin) = CURRENT_DATE - 1
                    """, conductorId);

            // ── Ganancias SEMANA ───────────────────────────────────────
            Map<String, Object> semana = db.queryForMap("""
                    SELECT
                        COALESCE(SUM(tarifa), 0) AS ganancias_semana,
                        COUNT(*)                 AS viajes_semana
                    FROM servicios
                    WHERE conductor_id = ?
                      AND estado = 'FINALIZADO'
                      AND fecha_fin >= DATE_TRUNC('week', CURRENT_DATE)
                    """, conductorId);

            Map<String, Object> semanaAnterior = db.queryForMap("""
                    SELECT COALESCE(SUM(tarifa), 0) AS ganancias_semana_ant
                    FROM servicios
                    WHERE conductor_id = ?
                      AND estado = 'FINALIZADO'
                      AND fecha_fin >= DATE_TRUNC('week', CURRENT_DATE) - INTERVAL '7 days'
                      AND fecha_fin <  DATE_TRUNC('week', CURRENT_DATE)
                    """, conductorId);

            // ── Ganancias MES ──────────────────────────────────────────
            Map<String, Object> mes = db.queryForMap("""
                    SELECT
                        COALESCE(SUM(tarifa), 0) AS ganancias_mes,
                        COUNT(*)                 AS viajes_mes
                    FROM servicios
                    WHERE conductor_id = ?
                      AND estado = 'FINALIZADO'
                      AND fecha_fin >= DATE_TRUNC('month', CURRENT_DATE)
                    """, conductorId);

            Map<String, Object> mesAnterior = db.queryForMap("""
                    SELECT COALESCE(SUM(tarifa), 0) AS ganancias_mes_ant
                    FROM servicios
                    WHERE conductor_id = ?
                      AND estado = 'FINALIZADO'
                      AND fecha_fin >= DATE_TRUNC('month', CURRENT_DATE) - INTERVAL '1 month'
                      AND fecha_fin <  DATE_TRUNC('month', CURRENT_DATE)
                    """, conductorId);

            // ── Total acumulado ────────────────────────────────────────
            Map<String, Object> total = db.queryForMap("""
                    SELECT COALESCE(SUM(tarifa), 0) AS total_acumulado
                    FROM servicios
                    WHERE conductor_id = ? AND estado = 'FINALIZADO'
                    """, conductorId);

            // ── Saldo disponible (últimas 48h no retiradas) ────────────
            Map<String, Object> saldo = db.queryForMap("""
                    SELECT COALESCE(SUM(tarifa), 0) AS saldo_disponible
                    FROM servicios
                    WHERE conductor_id = ?
                      AND estado = 'FINALIZADO'
                      AND fecha_fin >= NOW() - INTERVAL '48 hours'
                    """, conductorId);

            // ── Desglose: propinas e incentivos ───────────────────────
            double gananciaViajes = ((Number) mes.get("ganancias_mes")).doubleValue();
            double propinas = 0.0;
            double incentivos = 0.0;
            try {
                Map<String, Object> propRow = db.queryForMap("""
                        SELECT COALESCE(SUM(monto), 0) AS propinas
                        FROM historial_pagos
                        WHERE conductor_id = ? AND tipo = 'PROPINA'
                          AND fecha >= DATE_TRUNC('month', CURRENT_DATE)
                        """, conductorId);
                propinas = ((Number) propRow.get("propinas")).doubleValue();

                Map<String, Object> incRow = db.queryForMap("""
                        SELECT COALESCE(SUM(monto), 0) AS incentivos
                        FROM historial_pagos
                        WHERE conductor_id = ? AND tipo = 'INCENTIVO'
                          AND fecha >= DATE_TRUNC('month', CURRENT_DATE)
                        """, conductorId);
                incentivos = ((Number) incRow.get("incentivos")).doubleValue();

                gananciaViajes = gananciaViajes - propinas - incentivos;
            } catch (Exception ex) {
                // La tabla historial_pagos puede no existir aún — ignorar
            }

            // ── Métodos de pago ────────────────────────────────────────
            List<Map<String, Object>> metodosRaw = db.queryForList("""
                    SELECT
                        COALESCE(metodo_pago, 'efectivo') AS nombre,
                        COALESCE(SUM(tarifa), 0)          AS monto
                    FROM servicios
                    WHERE conductor_id = ?
                      AND estado = 'FINALIZADO'
                      AND fecha_fin >= DATE_TRUNC('month', CURRENT_DATE)
                    GROUP BY metodo_pago
                    """, conductorId);

            double totalMes = ((Number) mes.get("ganancias_mes")).doubleValue();
            List<java.util.Map<String, Object>> metodos = new java.util.ArrayList<>();
            for (Map<String, Object> m : metodosRaw) {
                double monto = ((Number) m.get("monto")).doubleValue();
                double pct = totalMes > 0 ? Math.round((monto / totalMes) * 1000.0) / 10.0 : 0.0;
                String nombre = m.get("nombre").toString();
                java.util.Map<String, Object> metodo = new java.util.HashMap<>();
                metodo.put("nombre", nombre.substring(0, 1).toUpperCase() + nombre.substring(1));
                metodo.put("monto", monto);
                metodo.put("pct", pct);
                metodos.add(metodo);
            }

            // ── Cálculo de tendencias ──────────────────────────────────
            double gHoy = ((Number) hoy.get("ganancias_hoy")).doubleValue();
            double gAyer = ((Number) ayer.get("ganancias_ayer")).doubleValue();
            double gSem = ((Number) semana.get("ganancias_semana")).doubleValue();
            double gSemA = ((Number) semanaAnterior.get("ganancias_semana_ant")).doubleValue();
            double gMes = ((Number) mes.get("ganancias_mes")).doubleValue();
            double gMesA = ((Number) mesAnterior.get("ganancias_mes_ant")).doubleValue();

            double trendHoy = gAyer > 0 ? Math.round(((gHoy - gAyer) / gAyer) * 100.0) : 0;
            double trendSem = gSemA > 0 ? Math.round(((gSem - gSemA) / gSemA) * 100.0) : 0;
            double trendMes = gMesA > 0 ? Math.round(((gMes - gMesA) / gMesA) * 100.0) : 0;

            // ── Cuenta bancaria (últimos 4 dígitos) ────────────────────
            String ultimosCuatro = "4567"; // Valor por defecto
            try {
                List<Map<String, Object>> cuenta = db.queryForList("""
                        SELECT numero_cuenta FROM historial_pagos
                        WHERE conductor_id = ? AND tipo = 'RETIRO' ORDER BY fecha DESC LIMIT 1
                        """, conductorId);
                if (!cuenta.isEmpty() && cuenta.get(0).get("numero_cuenta") != null) {
                    String num = cuenta.get(0).get("numero_cuenta").toString();
                    ultimosCuatro = num.length() >= 4 ? num.substring(num.length() - 4) : num;
                }
            } catch (Exception ex) {
                /* ignorar */ }

            // ── Respuesta ──────────────────────────────────────────────
            java.util.Map<String, Object> resp = new java.util.HashMap<>();
            resp.put("ganancias_hoy", gHoy);
            resp.put("viajes_hoy", ((Number) hoy.get("viajes_hoy")).intValue());
            resp.put("trend_hoy", trendHoy);
            resp.put("ganancias_semana", gSem);
            resp.put("viajes_semana", ((Number) semana.get("viajes_semana")).intValue());
            resp.put("trend_semana", trendSem);
            resp.put("ganancias_mes", gMes);
            resp.put("viajes_mes", ((Number) mes.get("viajes_mes")).intValue());
            resp.put("trend_mes", trendMes);
            resp.put("saldo_disponible", ((Number) saldo.get("saldo_disponible")).doubleValue());
            resp.put("total_acumulado", ((Number) total.get("total_acumulado")).doubleValue());
            resp.put("ganancia_viajes", Math.max(0, gananciaViajes));
            resp.put("propinas", propinas);
            resp.put("incentivos", incentivos);
            resp.put("metodos_pago", metodos);
            resp.put("ultimos_cuatro", ultimosCuatro);

            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Error al obtener estadísticas", "details", e.getMessage()));
        }
    }

    /**
     * CONDUCTOR: Lista de transacciones paginadas.
     * GET
     * /api/transporte/transacciones-conductor/{conductorId}?filtro=mes&page=0&size=5
     */
    @GetMapping("/transacciones-conductor/{conductorId}")
    public ResponseEntity<?> getTransacciones(
            @PathVariable("conductorId") Long conductorId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "mes") String filtro,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "5") int size) {
        try {
            String condFecha = switch (filtro) {
                case "hoy" -> "AND DATE(s.fecha_fin) = CURRENT_DATE";
                case "semana" -> "AND s.fecha_fin >= DATE_TRUNC('week', CURRENT_DATE)";
                case "mes_cal",
                        "mes" ->
                    "AND s.fecha_fin >= DATE_TRUNC('month', CURRENT_DATE)";
                case "anio" -> "AND s.fecha_fin >= DATE_TRUNC('year', CURRENT_DATE)";
                default -> "";
            };

            int offset = page * size;

            // Viajes completados (tipo Pago)
            List<Map<String, Object>> viajes = db.queryForList("""
                    SELECT
                        s.id,
                        'Pago'                                          AS tipo,
                        'Viaje completado'                             AS titulo,
                    CASE
                        WHEN s.origen IS NOT NULL AND s.origen != ''
                        THEN CONCAT(s.origen, ' → ', s.destino)
                        ELSE CONCAT(
                            TRUNC(s.origen_lat::numeric, 4), ', ', TRUNC(s.origen_lng::numeric, 4),
                            ' → ',
                            TRUNC(s.destino_lat::numeric, 4), ', ', TRUNC(s.destino_lng::numeric, 4)
                        )
                    END                                            AS subtitulo,
                        TO_CHAR(s.fecha_fin, 'DD Mon YYYY')           AS fecha,
                        TO_CHAR(s.fecha_fin, 'HH12:MI AM')            AS hora,
                        s.tarifa                                       AS monto
                    FROM servicios s
                    WHERE s.conductor_id = ?
                      AND s.estado = 'FINALIZADO'
                    """ + condFecha + """
                    ORDER BY s.fecha_fin DESC
                    LIMIT ? OFFSET ?
                    """, conductorId, size, offset);

            // Intentar agregar propinas/incentivos/retiros de historial_pagos
            List<Map<String, Object>> extras = new java.util.ArrayList<>();
            try {
                extras = db.queryForList("""
                        SELECT
                            hp.id,
                            hp.tipo,
                            CASE hp.tipo
                                WHEN 'PROPINA'   THEN 'Propina recibida'
                                WHEN 'INCENTIVO' THEN 'Incentivo por objetivo'
                                WHEN 'RETIRO'    THEN 'Retiro realizado'
                                ELSE hp.tipo
                            END                                        AS titulo,
                            hp.descripcion                             AS subtitulo,
                            TO_CHAR(hp.fecha, 'DD Mon YYYY')           AS fecha,
                            TO_CHAR(hp.fecha, 'HH12:MI AM')            AS hora,
                            CASE WHEN hp.tipo = 'RETIRO' THEN -hp.monto ELSE hp.monto END AS monto
                        FROM historial_pagos hp
                        WHERE hp.conductor_id = ?
                        """ + condFecha.replace("s.fecha_fin", "hp.fecha") + """
                        ORDER BY hp.fecha DESC
                        LIMIT ? OFFSET ?
                        """, conductorId, size, offset);
            } catch (Exception ex) {
                /* historial_pagos puede no existir */ }

            // Combinar viajes + extras, ordenar por fecha desc
            List<Map<String, Object>> todos = new java.util.ArrayList<>();
            todos.addAll(viajes);
            todos.addAll(extras);

            return ResponseEntity.ok(todos);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Error al obtener transacciones", "details", e.getMessage()));
        }
    }

    /**
     * CONDUCTOR: Puntos de la gráfica de ganancias diarias.
     * GET /api/transporte/grafica-conductor/{conductorId}?filtro=mes
     */
    @GetMapping("/grafica-conductor/{conductorId}")
    public ResponseEntity<?> getGraficaConductor(
            @PathVariable("conductorId") Long conductorId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "mes") String filtro) {
        try {
            String sql = switch (filtro) {
                case "semana" -> """
                        SELECT
                            TO_CHAR(DATE_TRUNC('day', fecha_fin), 'DD Mon') AS dia,
                            COALESCE(SUM(tarifa), 0)                        AS valor
                        FROM servicios
                        WHERE conductor_id = ?
                          AND estado = 'FINALIZADO'
                          AND fecha_fin >= DATE_TRUNC('week', CURRENT_DATE)
                        GROUP BY DATE_TRUNC('day', fecha_fin)
                        ORDER BY DATE_TRUNC('day', fecha_fin)
                        """;
                case "anio" -> """
                        SELECT
                            TO_CHAR(DATE_TRUNC('month', fecha_fin), 'Mon YYYY') AS dia,
                            COALESCE(SUM(tarifa), 0)                             AS valor
                        FROM servicios
                        WHERE conductor_id = ?
                          AND estado = 'FINALIZADO'
                          AND fecha_fin >= DATE_TRUNC('year', CURRENT_DATE)
                        GROUP BY DATE_TRUNC('month', fecha_fin)
                        ORDER BY DATE_TRUNC('month', fecha_fin)
                        """;
                default -> // mes
                    """
                            SELECT
                                TO_CHAR(DATE_TRUNC('day', fecha_fin), 'DD Mon') AS dia,
                                COALESCE(SUM(tarifa), 0)                        AS valor
                            FROM servicios
                            WHERE conductor_id = ?
                              AND estado = 'FINALIZADO'
                              AND fecha_fin >= DATE_TRUNC('month', CURRENT_DATE)
                            GROUP BY DATE_TRUNC('day', fecha_fin)
                            ORDER BY DATE_TRUNC('day', fecha_fin)
                            """;
            };

            List<Map<String, Object>> puntos = db.queryForList(sql, conductorId);
            return ResponseEntity.ok(puntos);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Error al obtener gráfica", "details", e.getMessage()));
        }
    }

    /**
     * CONDUCTOR: Solicitar retiro de saldo.
     * POST /api/transporte/solicitar-retiro
     * Body: { conductor_id: number, monto: number }
     */
    @PostMapping("/solicitar-retiro")
    public ResponseEntity<?> solicitarRetiro(@RequestBody Map<String, Object> body) {
        try {
            Long conductorId = Long.parseLong(body.get("conductor_id").toString());
            Double monto = Double.parseDouble(body.get("monto").toString());

            if (monto <= 0) {
                return ResponseEntity.status(400).body(Map.of("error", "Monto inválido"));
            }

            // Registrar en historial_pagos (si existe la tabla)
            try {
                db.update("""
                        INSERT INTO historial_pagos (conductor_id, tipo, monto, descripcion, fecha)
                        VALUES (?, 'RETIRO', ?, 'Transferencia a cuenta registrada', NOW())
                        """, conductorId, monto);
            } catch (Exception ex) {
                // Si la tabla no existe, simplemente loguear
                System.out.println("⚠️ historial_pagos no disponible: " + ex.getMessage());
            }

            return ResponseEntity.ok(Map.of("message", "Retiro solicitado. Se procesará en 1-2 días hábiles."));
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Error al procesar retiro", "details", e.getMessage()));
        }
    }

    /**
     * USUARIO: Obtiene el historial completo de servicios de un usuario.
     */
    @GetMapping("/servicios/usuario/{usuarioId}")
    public ResponseEntity<?> getHistorialUsuario(@PathVariable("usuarioId") Long usuarioId) {
        try {
            List<Map<String, Object>> historial = db.queryForList("""
                    SELECT * FROM servicios
                    WHERE usuario_id = ?
                    ORDER BY fecha_solicitud DESC
                    """, usuarioId);
            return ResponseEntity.ok(historial);
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Error al obtener historial", "details", e.getMessage()));
        }
    }

    /**
     * USUARIO: Obtiene el total gastado y la cantidad de viajes finalizados del mes
     * actual.
     */
    @GetMapping("/servicios/usuario/{usuarioId}/mes-actual")
    public ResponseEntity<?> getStatsMesActual(@PathVariable("usuarioId") Long usuarioId) {
        try {
            String sql = """
                    SELECT
                        COUNT(*) as total_servicios,
                        COALESCE(SUM(tarifa), 0) as total_gasto
                    FROM servicios
                    WHERE usuario_id = ?
                      AND estado = 'FINALIZADO'
                      AND EXTRACT(MONTH FROM fecha_solicitud) = EXTRACT(MONTH FROM CURRENT_DATE)
                      AND EXTRACT(YEAR FROM fecha_solicitud) = EXTRACT(YEAR FROM CURRENT_DATE)
                    """;
            Map<String, Object> stats = db.queryForMap(sql, usuarioId);
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Error al obtener estadísticas del mes", "details", e.getMessage()));
        }
    }

    /**
     * ADMIN: Obtiene la lista de todos los conductores para revisión.
     */
    @GetMapping("/admin/conductores")
    public ResponseEntity<?> getTodosLosConductores() {
        try {
            List<Map<String, Object>> conductores = db.queryForList("""
                    SELECT
                        c.id as conductor_id,
                        u.nombre,
                        u.correo,
                        u.telefono,
                        c.placa,
                        c.modelo,
                        c.estado
                    FROM conductores c
                    JOIN usuarios u ON c.usuario_id = u.id
                    ORDER BY c.id DESC
                    """);
            return ResponseEntity.ok(conductores);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Error al listar conductores",
                    "details", String.valueOf(e.getMessage()),
                    "cause", e.getCause() != null ? String.valueOf(e.getCause().getMessage()) : "sin causa"));
        }
    }

    /**
     * ADMIN: Aprueba o rechaza a un conductor.
     */
    @PatchMapping("/admin/conductor/{id}/estado")
    public ResponseEntity<?> actualizarEstadoConductor(
            @PathVariable("id") Long id,
            @RequestBody Map<String, Object> body) {
        try {
            String nuevoEstado = body.get("estado").toString().toLowerCase();
            String motivo = body.getOrDefault("motivo_rechazo", "").toString();

            // Intentamos guardar también el motivo de rechazo (columna puede no existir aún)
            try {
                db.update("UPDATE conductores SET estado = ?, motivo_rechazo = ? WHERE id = ?",
                        nuevoEstado, motivo, id);
            } catch (Exception exColumna) {
                // Fallback: si motivo_rechazo no existe todavía en la BD,
                // actualizamos solo el estado para no romper la aprobación/rechazo
                System.err.println("⚠️ No se pudo guardar motivo_rechazo (columna puede no existir): "
                        + exColumna.getMessage());
                db.update("UPDATE conductores SET estado = ? WHERE id = ?", nuevoEstado, id);
            }

            return ResponseEntity.ok(Map.of("message", "Estado del conductor actualizado a " + nuevoEstado));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(400).body(Map.of(
                    "error", "Error al actualizar estado",
                    "details", e.getMessage()));
        }
    }

    /**
     * ADMIN: Obtiene todos los servicios de la plataforma.
     */
    @GetMapping("/admin/servicios")
    public ResponseEntity<?> getTodosLosServicios() {
        try {
            List<Map<String, Object>> servicios = db.queryForList("""
                    SELECT
                        s.id,
                        s.tipo,
                        s.estado,
                        s.tarifa,
                        s.fecha_solicitud,
                        s.destino_lat,
                        s.destino_lng,
                        u.nombre as usuario_nombre,
                        uc.nombre as conductor_nombre
                    FROM servicios s
                    LEFT JOIN usuarios u ON s.usuario_id = u.id
                    LEFT JOIN conductores c ON s.conductor_id = c.id
                    LEFT JOIN usuarios uc ON c.usuario_id = uc.id
                    ORDER BY s.fecha_solicitud DESC
                    """);
            return ResponseEntity.ok(servicios);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Error al listar servicios",
                    "details", e.getMessage()));
        }
    }

    /**
     * CONDUCTOR: Stats en tiempo real para la pantalla Mis Viajes.
     * GET /api/transporte/stats-conductor/{conductorId}
     */
    @GetMapping("/stats-conductor/{conductorId}")
    public ResponseEntity<?> getStatsConductor(@PathVariable("conductorId") Long conductorId) {
        try {
            // Viajes de hoy y ganancias de hoy
            Map<String, Object> hoy = db.queryForMap("""
                    SELECT
                        COUNT(*) FILTER (WHERE estado = 'FINALIZADO') AS viajes_hoy,
                        COALESCE(SUM(tarifa) FILTER (WHERE estado = 'FINALIZADO'), 0) AS ganancias_hoy
                    FROM servicios
                    WHERE conductor_id = ?
                      AND fecha_solicitud::date = CURRENT_DATE
                    """, conductorId);

            // Viajes de ayer (para tendencia)
            Map<String, Object> ayer = db.queryForMap("""
                    SELECT
                        COUNT(*) FILTER (WHERE estado = 'FINALIZADO') AS viajes_ayer,
                        COALESCE(SUM(tarifa) FILTER (WHERE estado = 'FINALIZADO'), 0) AS ganancias_ayer
                    FROM servicios
                    WHERE conductor_id = ?
                      AND fecha_solicitud::date = CURRENT_DATE - 1
                    """, conductorId);

            // Viajes activos en este momento
            Long activos = db.queryForObject("""
                    SELECT COUNT(*) FROM servicios
                    WHERE conductor_id = ?
                      AND estado IN ('EN_VIAJE','ACEPTADO','EN_CAMINO',
                                     'EN_CAMINO_AL_USUARIO','LLEGO_AL_ORIGEN','PAQUETE_RECOGIDO')
                    """, Long.class, conductorId);

            // Total viajes finalizados histórico
            Long totalViajes = db.queryForObject("""
                    SELECT COUNT(*) FROM servicios
                    WHERE conductor_id = ? AND estado = 'FINALIZADO'
                    """, Long.class, conductorId);

            // Calcular tendencias
            long vHoy  = ((Number) hoy.get("viajes_hoy")).longValue();
            long vAyer = ((Number) ayer.get("viajes_ayer")).longValue();
            double gHoy  = ((Number) hoy.get("ganancias_hoy")).doubleValue();
            double gAyer = ((Number) ayer.get("ganancias_ayer")).doubleValue();

            long tendenciaHoy      = vHoy - vAyer;
            double tendenciaGanancia = gAyer > 0
                    ? Math.round(((gHoy - gAyer) / gAyer) * 100.0)
                    : 0;

            java.util.Map<String, Object> resp = new java.util.HashMap<>();
            resp.put("viajes_hoy",          vHoy);
            resp.put("ganancias_hoy",        gHoy);
            resp.put("activos",              activos != null ? activos : 0);
            resp.put("total_viajes",         totalViajes != null ? totalViajes : 0);
            resp.put("tendencia_hoy",        tendenciaHoy);
            resp.put("tendencia_ganancia",   tendenciaGanancia);

            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Error al obtener stats", "details", e.getMessage()));
        }
    }

    /**
     * CONDUCTOR: Historial paginado con filtros para la pantalla Mis Viajes.
     * GET /api/transporte/historial-conductor/{conductorId}
     *     ?filtro=todos&busqueda=&pagina=1&limite=10
     */
    @GetMapping("/historial-conductor/{conductorId}")
    public ResponseEntity<?> getHistorialConductor(
            @PathVariable("conductorId") Long conductorId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "todos") String filtro,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "")      String busqueda,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1")     int    pagina,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "10")    int    limite) {
        try {
            // Condición de fecha según filtro
            String condFecha = switch (filtro) {
                case "hoy"    -> "AND s.fecha_solicitud::date = CURRENT_DATE";
                case "semana" -> "AND s.fecha_solicitud >= NOW() - INTERVAL '7 days'";
                case "mes"    -> "AND s.fecha_solicitud >= NOW() - INTERVAL '30 days'";
                default       -> ""; // todos
            };

            // Condición de búsqueda por dirección o nombre de usuario
            String condBusqueda = "";
            if (busqueda != null && !busqueda.isBlank()) {
                condBusqueda = """
                        AND (
                            LOWER(s.origen)  LIKE LOWER('%%%s%%')
                         OR LOWER(s.destino) LIKE LOWER('%%%s%%')
                         OR LOWER(u.nombre)  LIKE LOWER('%%%s%%')
                        )
                        """.formatted(busqueda, busqueda, busqueda);
            }

            // Total de registros (para paginación)
            String sqlCount = """
                    SELECT COUNT(*)
                    FROM servicios s
                    LEFT JOIN usuarios u ON s.usuario_id = u.id
                    WHERE s.conductor_id = ?
                    """ + condFecha + condBusqueda;

            Long total = db.queryForObject(sqlCount, Long.class, conductorId);
            if (total == null) total = 0L;

            int totalPaginas = (int) Math.ceil((double) total / limite);
            if (totalPaginas == 0) totalPaginas = 1;
            int offset = (pagina - 1) * limite;

            // Consulta principal
            String sqlViajes = """
                    SELECT
                        s.id                                              AS servicio_id,
                        s.estado,
                        TO_CHAR(s.fecha_solicitud, 'YYYY-MM-DD"T"HH24:MI:SS') AS fecha_solicitud,
                        s.origen_lat,
                        s.origen_lng,
                        s.destino_lat,
                        s.destino_lng,
                        COALESCE(s.origen,  'Origen del servicio')       AS origen_direccion,
                        'Buenaventura'                                    AS origen_ciudad,
                        COALESCE(s.destino, 'Destino del cliente')       AS destino_direccion,
                        'Buenaventura'                                    AS destino_ciudad,
                        COALESCE(s.distancia_km, 0)                      AS distancia_km,
                        0                                                 AS duracion_min,
                        COALESCE(s.tarifa,            0)                 AS tarifa,
                        COALESCE(s.metodo_pago,       'Efectivo')        AS metodo_pago,
                        COALESCE(u.nombre,            'Cliente')         AS usuario_nombre,
                        COALESCE(s.tipo,              'TRANSPORTE')      AS tipo,
                        s.descripcion                                     AS razon_cancelacion
                    FROM servicios s
                    LEFT JOIN usuarios u ON s.usuario_id = u.id
                    WHERE s.conductor_id = ?
                    """ + condFecha + condBusqueda + """
                    ORDER BY s.fecha_solicitud DESC
                    LIMIT ? OFFSET ?
                    """;

            List<Map<String, Object>> viajes = db.queryForList(
                    sqlViajes, conductorId, limite, offset);

            java.util.Map<String, Object> resp = new java.util.HashMap<>();
            resp.put("viajes",        viajes);
            resp.put("total",         total);
            resp.put("total_paginas", totalPaginas);
            resp.put("pagina_actual", pagina);

            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Error al obtener historial", "details", e.getMessage()));
        }
    }

    /**
     * CONDUCTOR: Exportar ganancias como CSV.
     * GET /api/transporte/exportar-ganancias/{conductorId}
     */
    @GetMapping("/exportar-ganancias/{conductorId}")
    public ResponseEntity<byte[]> exportarGanancias(@PathVariable("conductorId") Long conductorId) {
        try {
            List<Map<String, Object>> viajes = db.queryForList("""
                    SELECT
                        s.id,
                        TO_CHAR(s.fecha_solicitud, 'DD/MM/YYYY HH24:MI') AS fecha,
                        s.estado,
                        COALESCE(s.origen,  '') AS origen,
                        COALESCE(s.destino, '') AS destino,
                        COALESCE(s.distancia_km, 0) AS distancia_km,
                        0                           AS duracion_min,
                        COALESCE(s.tarifa, 0)              AS tarifa,
                        COALESCE(s.metodo_pago, 'Efectivo') AS metodo_pago
                    FROM servicios s
                    WHERE conductor_id = ? AND s.estado = 'FINALIZADO'
                    ORDER BY s.fecha_solicitud DESC
                    """, conductorId);

            StringBuilder csv = new StringBuilder();
            csv.append("ID,Fecha,Estado,Origen,Destino,Distancia(km),Duracion(min),Tarifa,Metodo pago\n");
            for (Map<String, Object> v : viajes) {
                csv.append(String.format("%s,%s,%s,\"%s\",\"%s\",%s,%s,%s,%s\n",
                        v.get("id"),
                        v.get("fecha"),
                        v.get("estado"),
                        v.get("origen"),
                        v.get("destino"),
                        v.get("distancia_km"),
                        v.get("duracion_min"),
                        v.get("tarifa"),
                        v.get("metodo_pago")));
            }

            byte[] bytes = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header("Content-Type", "text/csv; charset=UTF-8")
                    .header("Content-Disposition",
                            "attachment; filename=\"ganancias-" + conductorId + ".csv\"")
                    .body(bytes);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(null);
        }
    }
}