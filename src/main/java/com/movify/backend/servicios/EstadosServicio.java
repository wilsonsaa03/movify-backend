package com.movify.backend.servicios;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.movify.backend.comun.ReglaNegocioException;

/**
 * Puente entre los estados del DER v2.3 (buscando, asignado, en_origen, en_curso, finalizado,
 * cancelado, expirado) y los nombres que todavía usa la web Angular (PENDIENTE, ACEPTADO...).
 *
 * El ciclo del conductor es lineal: asignado → en_origen → en_curso → finalizado.
 * Si la web pide saltar (p. ej. de ACEPTADO a EN_VIAJE), se recorren los pasos intermedios
 * para que la base registre cada uno en historial_estados.
 */
public final class EstadosServicio {

    public static final String BUSCANDO = "buscando";
    public static final String ASIGNADO = "asignado";
    public static final String EN_ORIGEN = "en_origen";
    public static final String EN_CURSO = "en_curso";
    public static final String FINALIZADO = "finalizado";
    public static final String CANCELADO = "cancelado";
    public static final String EXPIRADO = "expirado";

    /** Acciones especiales que no son un estado del servicio. */
    public static final String RECHAZAR = "rechazar";      // el conductor descarta la oferta
    public static final String SIN_CAMBIO = "sin_cambio";  // EN_CAMINO_AL_USUARIO: sigue "asignado"

    private static final List<String> CAMINO = List.of(ASIGNADO, EN_ORIGEN, EN_CURSO, FINALIZADO);

    private static final Map<String, String> DESDE_LEGADO = Map.ofEntries(
            Map.entry("PENDIENTE", BUSCANDO),
            Map.entry("ACEPTADO", ASIGNADO),
            Map.entry("RECHAZADO", RECHAZAR),
            Map.entry("EN_CAMINO_AL_USUARIO", SIN_CAMBIO),
            Map.entry("LLEGO_AL_ORIGEN", EN_ORIGEN),
            Map.entry("PAQUETE_RECOGIDO", EN_CURSO),
            Map.entry("EN_CAMINO", EN_CURSO),
            Map.entry("EN_VIAJE", EN_CURSO),
            Map.entry("FINALIZADO", FINALIZADO),
            Map.entry("CANCELADO", CANCELADO));

    private EstadosServicio() {
    }

    /** Acepta el nombre viejo (EN_VIAJE) o el código nuevo (en_curso). */
    public static String normalizar(String texto) {
        if (texto == null || texto.isBlank()) throw new ReglaNegocioException(400, "Falta el estado");
        String t = texto.trim();
        String viejo = DESDE_LEGADO.get(t.toUpperCase(Locale.ROOT));
        if (viejo != null) return viejo;
        String nuevo = t.toLowerCase(Locale.ROOT);
        if (List.of(BUSCANDO, ASIGNADO, EN_ORIGEN, EN_CURSO, FINALIZADO, CANCELADO, RECHAZAR).contains(nuevo)) {
            return nuevo;
        }
        throw new ReglaNegocioException(400, "Estado de servicio no válido: " + texto);
    }

    /** Pasos que da el conductor para ir de "actual" a "destino" (sin incluir el actual). */
    public static List<String> pasos(String actual, String destino) {
        int a = CAMINO.indexOf(actual);
        int d = CAMINO.indexOf(destino);
        if (a < 0 || d < 0 || d <= a) return List.of(destino);
        return CAMINO.subList(a + 1, d + 1);
    }

    public static String legado(String tipoServicio, String estado) {
        return switch (estado) {
            case BUSCANDO -> "PENDIENTE";
            case ASIGNADO -> "ACEPTADO";
            case EN_ORIGEN -> "LLEGO_AL_ORIGEN";
            case EN_CURSO -> "transporte".equals(tipoServicio) ? "EN_VIAJE" : "PAQUETE_RECOGIDO";
            case FINALIZADO -> "FINALIZADO";
            case CANCELADO, EXPIRADO -> "CANCELADO";
            default -> estado.toUpperCase(Locale.ROOT);
        };
    }

    /** "TRANSPORTE", "domicilio"... → código del catálogo tipos_servicio. */
    public static String tipoServicio(String texto) {
        if (texto == null || texto.isBlank()) return "transporte";
        String t = texto.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "transporte", "viaje", "moto", "taxi" -> "transporte";
            case "domicilio", "domicilios", "diligencia" -> "domicilio";
            case "encomienda", "encomiendas", "paquete" -> "encomienda";
            default -> throw new ReglaNegocioException(400, "Tipo de servicio no válido: " + texto);
        };
    }
}
