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
import org.springframework.web.bind.annotation.CrossOrigin;
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
@CrossOrigin(origins = "${app.cors.allowed-origins}")
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
                    WHERE c.en_linea = true
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

            // Insertar en la tabla de servicios
            String sql = "INSERT INTO servicios (usuario_id, origen_lat, origen_lng, destino_lat, destino_lng, distancia_km, tarifa, estado, tipo, descripcion, fecha_solicitud) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW()) RETURNING id";
            Long servicioId = db.queryForObject(sql, Long.class, usuarioId, origenLat, origenLng, destinoLat,
                    destinoLng, distanciaKm, tarifa, Servicio.EstadoServicio.PENDIENTE.name(), tipo, descripcion);

            // --- Lógica para buscar y notificar conductores cercanos ---
            // 1. Buscar conductores activos cercanos al origen del servicio
            // Fixed: Use origenLng, origenLat for ST_MakePoint
            List<Map<String, Object>> conductoresCercanos = db.queryForList(
                    """
                                SELECT id as conductor_id
                                FROM conductores
                                WHERE en_linea = true
                                AND ST_DWithin(
                                    ST_SetSRID(ST_MakePoint(longitud::double precision, latitud::double precision), 4326)::geography,
                                    ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
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

            db.update("INSERT INTO calificaciones (servicio_id, usuario_id, calificacion, comentario, fecha) VALUES (?, ?, ?, ?, NOW())",
                    servicioId, usuarioId, puntuacion, comentario);

            return ResponseEntity.ok(Map.of("message", "Calificación guardada."));
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", "Error al guardar calificación", "details", e.getMessage()));
        }
    }
}