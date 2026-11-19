package com.movify.backend.servicios.simulacion;

import java.util.ArrayList;
import java.util.List;

/**
 * Una polilínea de calles (la que devuelve OSRM) y las cuentas para moverse por ella.
 *
 * El vehículo SIEMPRE está sobre la polilínea: su posición se define por los metros recorridos
 * desde el primer vértice, y se interpola dentro del tramo en el que caen esos metros. Así pasa por
 * todos los vértices en orden (curvas, giros, intersecciones) y nunca corta camino en línea recta.
 *
 * Distancias en metros con la aproximación plana local (equirectangular). Es la MISMA fórmula que usa
 * la app (animar-por-ruta.ts → metros()): con los mismos metros recorridos las dos dan el mismo punto.
 */
public final class RutaSimulada {

    /** Metros por grado de latitud (y de longitud en el ecuador). Igual que en la app. */
    public static final double M_POR_GRADO = 111_320;
    /** Para la orientación se mira un poco antes y un poco después: gira suave en las esquinas. */
    private static final double MIRAR_ATRAS_M = 4;
    private static final double MIRAR_ADELANTE_M = 6;

    public record Posicion(double lat, double lng, double rumbo, double recorrido, int tramo) {
    }

    public record Proyeccion(double recorrido, double distancia) {
    }

    private final double[] lat;
    private final double[] lng;
    /** acumulado[i] = metros desde el vértice 0 hasta el vértice i. */
    private final double[] acumulado;

    /** @param puntos [[lat, lng], ...] en orden. Los vértices repetidos seguidos se quitan. */
    public RutaSimulada(List<double[]> puntos) {
        List<double[]> limpios = new ArrayList<>();
        for (double[] p : puntos) {
            if (p == null || p.length < 2) continue;
            if (limpios.isEmpty() || metros(limpios.get(limpios.size() - 1), p) > 0.01) limpios.add(p);
        }
        if (limpios.isEmpty()) throw new IllegalArgumentException("La ruta no tiene puntos");
        if (limpios.size() == 1) limpios.add(limpios.get(0));
        int n = limpios.size();
        lat = new double[n];
        lng = new double[n];
        acumulado = new double[n];
        for (int i = 0; i < n; i++) {
            lat[i] = limpios.get(i)[0];
            lng[i] = limpios.get(i)[1];
            if (i > 0) acumulado[i] = acumulado[i - 1] + metros(lat[i - 1], lng[i - 1], lat[i], lng[i]);
        }
    }

    public double largo() {
        return acumulado[acumulado.length - 1];
    }

    public int vertices() {
        return lat.length;
    }

    public double[] vertice(int i) {
        return new double[] { lat[i], lng[i] };
    }

    /** Metros desde el inicio hasta el vértice i. */
    public double acumuladoEn(int i) {
        return acumulado[i];
    }

    /** Punto y orientación a `recorrido` metros del inicio (se recorta a [0, largo]). */
    public Posicion en(double recorrido) {
        double s = Math.max(0, Math.min(largo(), recorrido));
        int i = tramo(s);
        double[] p = interpolar(i, s);
        return new Posicion(p[0], p[1], rumboEn(s), s, i);
    }

    /** Rumbo en grados (0 = norte, 90 = este) mirando la ruta un poco antes y después del punto. */
    public double rumboEn(double recorrido) {
        double s = Math.max(0, Math.min(largo(), recorrido));
        double a = Math.max(0, s - MIRAR_ATRAS_M);
        double b = Math.min(largo(), s + MIRAR_ADELANTE_M);
        if (b - a < 0.5) {
            int i = Math.min(tramo(s), lat.length - 2);
            return rumbo(lat[i], lng[i], lat[i + 1], lng[i + 1]);
        }
        double[] pa = interpolar(tramo(a), a);
        double[] pb = interpolar(tramo(b), b);
        return rumbo(pa[0], pa[1], pb[0], pb[1]);
    }

    /** Punto de la ruta más cercano a (la, ln): a cuántos metros del inicio queda y qué tan lejos está. */
    public Proyeccion proyectar(double la, double ln) {
        double mejorS = 0;
        double mejorD = Double.POSITIVE_INFINITY;
        double cos = Math.cos(Math.toRadians(la));
        for (int i = 0; i < lat.length - 1; i++) {
            double ax = (lng[i + 1] - lng[i]) * cos, ay = lat[i + 1] - lat[i];
            double px = (ln - lng[i]) * cos, py = la - lat[i];
            double l2 = ax * ax + ay * ay;
            double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, (px * ax + py * ay) / l2));
            double qLat = lat[i] + (lat[i + 1] - lat[i]) * t;
            double qLng = lng[i] + (lng[i + 1] - lng[i]) * t;
            double d = metros(la, ln, qLat, qLng);
            if (d < mejorD) {
                mejorD = d;
                mejorS = acumulado[i] + (acumulado[i + 1] - acumulado[i]) * t;
            }
        }
        return new Proyeccion(mejorS, mejorD);
    }

    /** Distancia de un punto a la polilínea (para las pruebas: el vehículo no se sale de la calle). */
    public double distanciaA(double la, double ln) {
        return proyectar(la, ln).distancia();
    }

    /** Índice i del tramo (i → i+1) donde cae s. Búsqueda binaria sobre los acumulados. */
    private int tramo(double s) {
        int lo = 0, hi = acumulado.length - 1;
        if (s >= acumulado[hi]) return Math.max(0, hi - 1);
        while (hi - lo > 1) {
            int m = (lo + hi) >>> 1;
            if (acumulado[m] <= s) lo = m;
            else hi = m;
        }
        return lo;
    }

    private double[] interpolar(int i, double s) {
        int j = Math.min(i + 1, lat.length - 1);
        double l = acumulado[j] - acumulado[i];
        double t = l <= 0 ? 0 : (s - acumulado[i]) / l;
        t = Math.max(0, Math.min(1, t));
        return new double[] { lat[i] + (lat[j] - lat[i]) * t, lng[i] + (lng[j] - lng[i]) * t };
    }

    // ───────────── Fórmulas (idénticas a las de la app) ─────────────

    public static double metros(double[] a, double[] b) {
        return metros(a[0], a[1], b[0], b[1]);
    }

    public static double metros(double aLat, double aLng, double bLat, double bLng) {
        double x = (bLng - aLng) * M_POR_GRADO * Math.cos(Math.toRadians((aLat + bLat) / 2));
        double y = (bLat - aLat) * M_POR_GRADO;
        return Math.hypot(x, y);
    }

    /** 0 = norte, 90 = este, 180 = sur, 270 = oeste. */
    public static double rumbo(double aLat, double aLng, double bLat, double bLng) {
        double x = (bLng - aLng) * Math.cos(Math.toRadians((aLat + bLat) / 2));
        double y = bLat - aLat;
        double g = Math.toDegrees(Math.atan2(x, y));
        return (g + 360) % 360;
    }
}
