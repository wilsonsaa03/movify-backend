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
@CrossOrigin(origins = "*")
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
            return ResponseEntity.status(500).body("Error en la consulta: " + e.getMessage());
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
            return ResponseEntity.status(400).body("Error: El ID debe ser un número válido. " + e.getMessage());
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
            return ResponseEntity.status(400).body("Error en datos: " + e.getMessage());
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
            Double tarifa = recalcularTarifaSeguridad(distanciaKm);
            String tipo = solicitud.getOrDefault("tipo", "TRANSPORTE").toString();
            // Insertar en la tabla de servicios
            String sql = "INSERT INTO servicios (usuario_id, origen_lat, origen_lng, destino_lat, destino_lng, distancia_km, tarifa, estado, tipo, fecha_solicitud) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW()) RETURNING id";
            Long servicioId = db.queryForObject(sql, Long.class, usuarioId, origenLat, origenLng, destinoLat,
                    destinoLng, distanciaKm, tarifa, Servicio.EstadoServicio.PENDIENTE.name(), tipo);

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
                        // 1. Aumentamos la espera a 60 segundos para darte tiempo de probar
                        Thread.sleep(60000);

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
                            simularRecorrido(conductorSimuladoId, rutaAlOrigen);

                            System.out.println("🤖 Conductor simulado llegó al usuario.");
                            Thread.sleep(2000); // Pausa de recogida

                            // 3. HACIA EL DESTINO (Ruta real por calles)
                            List<double[]> rutaAlDestino = obtenerRutaOSRM(origenLat, origenLng, destinoLat,
                                    destinoLng);
                            simularRecorrido(conductorSimuladoId, rutaAlDestino);

                            // 4. FINALIZAR
                            Thread.sleep(2000);
                            db.update("UPDATE servicios SET estado = ?, fecha_fin = NOW() WHERE id = ?",
                                    Servicio.EstadoServicio.FINALIZADO.name(), servicioId);

                            System.out.println("🤖 SIMULACIÓN COMPLETADA: Viaje #" + servicioId + " finalizado.");
                        } else {
                            System.out.println("✅ El viaje #" + servicioId
                                    + " fue aceptado por un conductor real. Simulación cancelada.");
                        }

                    } catch (Exception e) {
                        System.err.println("❌ Error en la simulación de aceptación: " + e.getMessage());
                    }
                }).start();
            }
            return ResponseEntity.ok(Map.of("id", servicioId, "message", "Solicitud creada con éxito."));
        } catch (Exception e) {
            e.printStackTrace(); // Esto mostrará el error completo en la consola de Java
            return ResponseEntity.status(400).body("Error al crear la solicitud: " + e.getMessage());
        }
    }

    /**
     * Valida o recalcula la tarifa en el servidor para evitar manipulaciones
     * y aplica recargos por hora del día.
     */
    private Double recalcularTarifaSeguridad(Double distancia) {
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

        return Math.ceil((total * factor) / 100) * 100;
    }

    /**
     * CONDUCTOR: Obtiene solicitudes pendientes para un conductor específico.
     * Idealmente, esto sería reemplazado por WebSockets.
     */
    @GetMapping("/solicitudes-pendientes/{conductorId}")
    public ResponseEntity<?> getSolicitudesPendientes(@PathVariable("conductorId") Long conductorId) {
        try {
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
            return ResponseEntity.status(500).body("Error al obtener solicitudes pendientes: " + e.getMessage());
        }
    }

    /**
     * USUARIO: Consulta el estado actual de un servicio.
     */
    @GetMapping("/servicio/{servicioId}")
    public ResponseEntity<?> getEstadoServicio(@PathVariable("servicioId") Long servicioId) {
        try {
            List<Map<String, Object>> servicio = db.queryForList("""
                        SELECT
                            s.id, s.estado, s.conductor_id, c.nombre as conductor_nombre,
                            c.foto as conductor_foto, c.latitud, c.longitud, s.origen_lat, s.origen_lng
                        FROM servicios s
                        LEFT JOIN conductores c ON s.conductor_id = c.id
                        WHERE s.id = ?
                    """, servicioId);

            if (servicio.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(servicio.get(0));
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error al consultar estado del servicio: " + e.getMessage());
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
            String estado = body.get("estado").toString();
            Long conductorId = body.containsKey("conductor_id") ? Long.parseLong(body.get("conductor_id").toString())
                    : null;

            if (estado.equals(Servicio.EstadoServicio.ACEPTADO.name())) {
                // Intentar aceptar el servicio, solo si está PENDIENTE
                int updatedRows = db.update(
                        "UPDATE servicios SET estado = ?, conductor_id = ? WHERE id = ? AND estado = ?",
                        Servicio.EstadoServicio.ACEPTADO.name(), conductorId, servicioId,
                        Servicio.EstadoServicio.PENDIENTE.name());

                if (updatedRows > 0) {
                    // Si se aceptó, marcar todas las otras notificaciones para este servicio como
                    // RECHAZADAS_POR_OTRO
                    db.update(
                            "UPDATE notificaciones_conductor SET estado_notificacion = ?, fecha_respuesta = NOW() WHERE servicio_id = ? AND conductor_id != ?",
                            NotificacionConductor.EstadoNotificacion.RECHAZADA_POR_OTRO.name(), servicioId,
                            conductorId);
                    // Marcar la notificación de este conductor como ACEPTADA
                    db.update(
                            "UPDATE notificaciones_conductor SET estado_notificacion = ?, fecha_respuesta = NOW() WHERE servicio_id = ? AND conductor_id = ?",
                            NotificacionConductor.EstadoNotificacion.ACEPTADA.name(), servicioId, conductorId);
                    // TODO: Notificar al usuario (WebSocket) que su viaje fue aceptado por este
                    // conductor
                    // TODO: Notificar a los otros conductores (WebSocket) que el viaje ya no está
                    // disponible
                    return ResponseEntity
                            .ok(Map.of("message", "Servicio aceptado con éxito.", "servicioId", servicioId));
                } else {
                    // El servicio ya no está PENDIENTE (fue aceptado por otro o cancelado).
                    // Actualizamos la notificación de este conductor a RECHAZADA_POR_OTRO si
                    // intentó aceptar un servicio ya tomado.
                    db.update(
                            "UPDATE notificaciones_conductor SET estado_notificacion = ?, fecha_respuesta = NOW() WHERE servicio_id = ? AND conductor_id = ?",
                            NotificacionConductor.EstadoNotificacion.RECHAZADA_POR_OTRO.name(), servicioId,
                            conductorId);
                    return ResponseEntity.status(409).body(
                            Map.of("message", "El servicio ya no está disponible o fue aceptado por otro conductor."));
                }
            } else if (estado.equals(Servicio.EstadoServicio.RECHAZADO.name())) {
                // Marcar la notificación de este conductor como RECHAZADA
                db.update(
                        "UPDATE notificaciones_conductor SET estado_notificacion = ?, fecha_respuesta = NOW() WHERE servicio_id = ? AND conductor_id = ?",
                        NotificacionConductor.EstadoNotificacion.RECHAZADA.name(), servicioId, conductorId);
                // TODO: Lógica para buscar otro conductor o marcar el servicio como
                // "SIN_CONDUCTORES" si todos rechazan
                return ResponseEntity.ok(Map.of("message", "Servicio rechazado."));
            } else if (estado.equals(Servicio.EstadoServicio.FINALIZADO.name())) {
                // Lógica para finalizar el servicio (solo el conductor asignado puede hacerlo)
                if (conductorId == null) {
                    return ResponseEntity.status(400)
                            .body("Error: conductor_id es requerido para finalizar un servicio.");
                }
                db.update("UPDATE servicios SET estado = ?, fecha_fin = NOW() WHERE id = ? AND conductor_id = ?",
                        Servicio.EstadoServicio.FINALIZADO.name(), servicioId, conductorId);
                // TODO: Actualizar ganancias del conductor, etc.
                return ResponseEntity.ok(Map.of("message", "Servicio finalizado."));
            } else if (estado.equals(Servicio.EstadoServicio.CANCELADO.name())) {
                // Lógica para cancelar el servicio (puede ser el usuario o el conductor)
                db.update("UPDATE servicios SET estado = ? WHERE id = ?",
                        Servicio.EstadoServicio.CANCELADO.name(), servicioId); // No se requiere conductor_id aquí, ya
                                                                               // que el usuario también puede cancelar
                // TODO: Manejar penalizaciones, etc.
                // Si el servicio fue cancelado, todas las notificaciones pendientes a
                // conductores deben ser marcadas como CANCELADAS_POR_USUARIO
                db.update(
                        "UPDATE notificaciones_conductor SET estado_notificacion = ?, fecha_respuesta = NOW() WHERE servicio_id = ? AND estado_notificacion = ?",
                        NotificacionConductor.EstadoNotificacion.RECHAZADA_POR_OTRO.name(), servicioId,
                        NotificacionConductor.EstadoNotificacion.ENVIADA.name());
                return ResponseEntity.ok(Map.of("message", "Servicio cancelado."));
            }
            return ResponseEntity.status(400).body("Estado de servicio no válido.");
        } catch (Exception e) {
            return ResponseEntity.status(400).body("Error al responder la solicitud: " + e.getMessage());
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
}