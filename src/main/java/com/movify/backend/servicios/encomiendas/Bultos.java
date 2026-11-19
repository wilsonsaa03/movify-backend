package com.movify.backend.servicios.encomiendas;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.movify.backend.comun.Datos;
import com.movify.backend.comun.ReglaNegocioException;
import com.movify.backend.servicios.encomiendas.EncomiendaCotizador.Bulto;
import com.movify.backend.servicios.encomiendas.EncomiendaDtos.BultoRequest;

/** Convierte lo que manda el cliente en bultos válidos (la app nueva manda una lista; la web, un solo paquete). */
public final class Bultos {

    private Bultos() {
    }

    /**
     * @param lista bultos de la app (puede ser null)
     * @param tipoPaquete, tamano, pesoKg, fragil, valorDeclarado, descripcion: el formato de un solo paquete
     */
    public static List<Bulto> normalizar(List<BultoRequest> lista, String tipoPaquete, String tamano, String pesoKg,
            Boolean fragil, BigDecimal valorDeclarado, String descripcion) {
        if (lista != null && !lista.isEmpty()) {
            List<Bulto> r = new ArrayList<>();
            for (int i = 0; i < lista.size(); i++) r.add(uno(lista.get(i), i + 1));
            return r;
        }
        BigDecimal peso;
        try {
            String p = pesoKg == null || pesoKg.isBlank() ? "1" : pesoKg;
            peso = new BigDecimal(p.replace(",", ".").replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException e) {
            throw new ReglaNegocioException(400, "El peso no es válido");
        }
        return List.of(uno(new BultoRequest(tipo(tipoPaquete != null ? tipoPaquete : tamano), peso, null, null, null,
                valorDeclarado, fragil, Datos.recortar(Datos.vacioANull(descripcion), 120)), 1));
    }

    private static Bulto uno(BultoRequest b, int n) {
        String quien = "El paquete " + n;
        if (b.pesoKg() == null || b.pesoKg().signum() <= 0) throw new ReglaNegocioException(400, quien + " necesita un peso válido");
        if (b.pesoKg().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new ReglaNegocioException(400, quien + " pesa más de 100 kg");
        }
        int medidas = (b.largoCm() != null ? 1 : 0) + (b.anchoCm() != null ? 1 : 0) + (b.altoCm() != null ? 1 : 0);
        if (medidas != 0 && medidas != 3) {
            throw new ReglaNegocioException(400, quien + ": escribe largo, ancho y alto, o deja las tres medidas vacías");
        }
        if (medidas == 3) {
            for (BigDecimal m : List.of(b.largoCm(), b.anchoCm(), b.altoCm())) {
                if (m.signum() <= 0 || m.compareTo(BigDecimal.valueOf(300)) > 0) {
                    throw new ReglaNegocioException(400, quien + ": cada medida debe estar entre 1 y 300 cm");
                }
            }
        }
        if (b.valorDeclarado() != null && b.valorDeclarado().signum() < 0) {
            throw new ReglaNegocioException(400, quien + ": el valor declarado no puede ser negativo");
        }
        BigDecimal declarado = b.valorDeclarado() == null || b.valorDeclarado().signum() == 0 ? null : b.valorDeclarado();
        return new Bulto(tipo(b.tipo()), b.pesoKg(), b.largoCm(), b.anchoCm(), b.altoCm(), declarado,
                Boolean.TRUE.equals(b.fragil()), Datos.recortar(Datos.vacioANull(b.descripcion()), 120));
    }

    /** "sobre" | "documento" | "caja-pequeña" | "mediano"… → sobre | pequeno | mediano | grande. */
    static String tipo(String t) {
        if (t == null || t.isBlank()) return "pequeno";
        String x = t.toLowerCase(Locale.ROOT).replace("ñ", "n");
        if (x.contains("sobre") || x.contains("documento")) return "sobre";
        if (x.contains("grande")) return "grande";
        if (x.contains("median")) return "mediano";
        return "pequeno";
    }

    /** Tamaño que guarda servicio_encomienda (pequeno | mediano | grande): el del bulto más grande. */
    public static String tamanoMayor(List<Bulto> bultos) {
        if (bultos.stream().anyMatch(b -> "grande".equals(b.tipo()))) return "grande";
        if (bultos.stream().anyMatch(b -> "mediano".equals(b.tipo()))) return "mediano";
        return "pequeno";
    }
}
