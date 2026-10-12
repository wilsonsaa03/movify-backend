package com.movify.backend.Servicio;

import static com.movify.backend.Modelo.Servicio.EstadoServicio.*;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import com.movify.backend.Modelo.Servicio.EstadoServicio;

/**
 * Qué transiciones de estado existen y quién puede hacerlas.
 *  - CONDUCTOR: el asignado al servicio (o cualquiera aprobado, solo para ACEPTAR un PENDIENTE).
 *  - CLIENTE:   el dueño del servicio.
 *  - PERSONAL:  admin o soporte.
 */
public final class MaquinaEstados {

    public enum Actor { CONDUCTOR, CLIENTE, PERSONAL }

    private static final Map<EstadoServicio, Set<EstadoServicio>> CONDUCTOR = new EnumMap<>(EstadoServicio.class);
    private static final Map<EstadoServicio, Set<EstadoServicio>> CLIENTE = new EnumMap<>(EstadoServicio.class);

    /** Estados en los que el viaje ya arrancó: solo el personal puede cancelarlo. */
    private static final Set<EstadoServicio> EN_CURSO = EnumSet.of(EN_CAMINO, EN_VIAJE, PAQUETE_RECOGIDO);
    public static final Set<EstadoServicio> TERMINALES = EnumSet.of(FINALIZADO, CANCELADO);

    static {
        CONDUCTOR.put(PENDIENTE, EnumSet.of(ACEPTADO));
        CONDUCTOR.put(ACEPTADO, EnumSet.of(EN_CAMINO_AL_USUARIO, LLEGO_AL_ORIGEN, PAQUETE_RECOGIDO, EN_CAMINO, EN_VIAJE, CANCELADO));
        CONDUCTOR.put(EN_CAMINO_AL_USUARIO, EnumSet.of(LLEGO_AL_ORIGEN, PAQUETE_RECOGIDO, EN_CAMINO, EN_VIAJE, CANCELADO));
        CONDUCTOR.put(LLEGO_AL_ORIGEN, EnumSet.of(PAQUETE_RECOGIDO, EN_CAMINO, EN_VIAJE, CANCELADO));
        CONDUCTOR.put(PAQUETE_RECOGIDO, EnumSet.of(EN_CAMINO, EN_VIAJE, FINALIZADO));
        CONDUCTOR.put(EN_CAMINO, EnumSet.of(EN_VIAJE, FINALIZADO));
        CONDUCTOR.put(EN_VIAJE, EnumSet.of(FINALIZADO));

        CLIENTE.put(PENDIENTE, EnumSet.of(CANCELADO));
        CLIENTE.put(ACEPTADO, EnumSet.of(CANCELADO));
        CLIENTE.put(EN_CAMINO_AL_USUARIO, EnumSet.of(CANCELADO));
        CLIENTE.put(LLEGO_AL_ORIGEN, EnumSet.of(CANCELADO));
    }

    private MaquinaEstados() {
    }

    public static boolean permitido(Actor actor, EstadoServicio desde, EstadoServicio hacia) {
        if (desde == null || hacia == null || TERMINALES.contains(desde)) return false;
        return switch (actor) {
            case CONDUCTOR -> CONDUCTOR.getOrDefault(desde, Set.of()).contains(hacia);
            case CLIENTE -> CLIENTE.getOrDefault(desde, Set.of()).contains(hacia);
            // El personal solo interviene para cerrar un servicio problemático
            case PERSONAL -> hacia == CANCELADO;
        };
    }

    public static boolean enCurso(EstadoServicio e) {
        return EN_CURSO.contains(e);
    }
}
