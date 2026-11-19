package com.movify.backend.servicios.encomiendas;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.movify.backend.comun.ReglaNegocioException;
import com.movify.backend.servicios.TarifaServicio;

/**
 * Precio de una encomienda con su desglose.
 *
 *   total = tarifa de la ruta (fn_cotizar: base + km + minutos + recargos)
 *         + kilos facturables por encima de los incluidos
 *         + bultos adicionales al primero
 *         + manejo de bultos frágiles
 *         + protección del valor declarado (solo si el admin la habilita)
 *
 * El peso facturable de cada bulto es el mayor entre el real y el volumétrico (largo × ancho × alto / factor).
 * Todos los valores salen de configuracion_sistema (los edita el admin); este archivo no tiene precios.
 * La misma cuenta se usa al cotizar (pantalla) y al crear la encomienda (cobro), así no pueden diferir.
 */
@Service
public class EncomiendaCotizador {

    /** Orden de los tamaños para comparar con encomienda_{moto|taxi}_tamano_max. */
    private static final List<String> TAMANOS = List.of("sobre", "pequeno", "mediano", "grande");
    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    private final JdbcTemplate db;
    private final TarifaServicio tarifas;

    public EncomiendaCotizador(JdbcTemplate db, TarifaServicio tarifas) {
        this.db = db;
        this.tarifas = tarifas;
    }

    /** Un bulto tal como llega de la app (ya validado por Bultos.normalizar). */
    public record Bulto(String tipo, BigDecimal pesoKg, BigDecimal largoCm, BigDecimal anchoCm, BigDecimal altoCm,
            BigDecimal valorDeclarado, boolean fragil, String descripcion) {

        boolean tieneMedidas() {
            return largoCm != null && anchoCm != null && altoCm != null;
        }

        BigDecimal ladoMayor() {
            return tieneMedidas() ? largoCm.max(anchoCm).max(altoCm) : null;
        }
    }

    /** Parámetros vigentes (configuracion_sistema). */
    public record Parametros(BigDecimal factorVolumetrico, BigDecimal kgIncluidos, BigDecimal valorKgAdicional,
            BigDecimal valorBultoAdicional, BigDecimal recargoFragil, BigDecimal proteccionPct,
            BigDecimal maxValorDeclarado, int maxBultos, Map<String, Capacidad> capacidades) {
    }

    public record Capacidad(BigDecimal maxKg, BigDecimal maxLadoCm, int tamanoMax) {
    }

    public record Linea(String concepto, String detalle, BigDecimal valor) {
    }

    /** Resultado para un vehículo. Si no puede llevarla, disponible = false y motivo dice por qué. */
    public record Resultado(String tipoVehiculo, boolean disponible, String motivo, int bultos, BigDecimal pesoRealKg,
            BigDecimal pesoVolumetricoKg, BigDecimal pesoFacturableKg, List<Linea> desglose, BigDecimal total) {

        public Map<String, Object> comoMapa() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tipo_vehiculo", tipoVehiculo);
            m.put("disponible", disponible);
            m.put("motivo", motivo);
            m.put("bultos", bultos);
            m.put("peso_real_kg", pesoRealKg);
            m.put("peso_volumetrico_kg", pesoVolumetricoKg);
            m.put("peso_facturable_kg", pesoFacturableKg);
            m.put("desglose", desglose.stream().map(l -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("concepto", l.concepto());
                x.put("detalle", l.detalle());
                x.put("valor", l.valor());
                return x;
            }).toList());
            m.put("valor_estimado", total);
            return m;
        }
    }

    // ═════════════════════════════ Con base de datos ═════════════════════════════

    public Parametros parametros() {
        Map<String, Capacidad> cap = new LinkedHashMap<>();
        cap.put("moto", new Capacidad(num("encomienda_moto_max_kg", 15), num("encomienda_moto_max_lado_cm", 50),
                num("encomienda_moto_tamano_max", 2).intValue()));
        cap.put("taxi", new Capacidad(num("encomienda_taxi_max_kg", 30), num("encomienda_taxi_max_lado_cm", 100),
                num("encomienda_taxi_tamano_max", 3).intValue()));
        return new Parametros(num("encomienda_factor_volumetrico", 5000), num("encomienda_kg_incluidos", 5),
                num("encomienda_valor_kg_adicional", 0), num("encomienda_valor_bulto_adicional", 0),
                num("encomienda_recargo_fragil", 0), num("encomienda_proteccion_pct", 0),
                num("encomienda_max_valor_declarado", 2_000_000), num("encomienda_max_bultos", 10).intValue(), cap);
    }

    /** Vehículos que hacen encomiendas y tienen tarifa activa (moto y taxi hoy; sale de la BD). */
    public List<String> vehiculos() {
        return db.queryForList("""
                SELECT p.tipo_vehiculo FROM servicio_vehiculo_permitido p
                JOIN tarifas t ON t.tipo_servicio = p.tipo_servicio AND t.tipo_vehiculo = p.tipo_vehiculo AND t.activo
                WHERE p.tipo_servicio = 'encomienda' ORDER BY p.tipo_vehiculo
                """, String.class);
    }

    /** Cotiza para un vehículo con la tarifa activa de encomienda. */
    public Resultado cotizar(List<Bulto> bultos, String vehiculo, TarifaServicio.Ruta ruta) {
        Parametros p = parametros();
        validarConjunto(bultos, p);
        BigDecimal tarifaRuta = tarifas.cotizar("encomienda", vehiculo, ruta);
        return calcular(bultos, vehiculo, tarifaRuta, ruta, p);
    }

    /** Todas las opciones (moto, taxi…) para que el cliente elija. */
    public List<Resultado> opciones(List<Bulto> bultos, TarifaServicio.Ruta ruta) {
        Parametros p = parametros();
        validarConjunto(bultos, p);
        List<Resultado> r = new ArrayList<>();
        for (String v : vehiculos()) {
            r.add(calcular(bultos, v, tarifas.cotizar("encomienda", v, ruta), ruta, p));
        }
        return r;
    }

    private BigDecimal num(String clave, double defecto) {
        return db.queryForObject("SELECT fn_config_num(?, ?)", BigDecimal.class, clave, BigDecimal.valueOf(defecto));
    }

    // ═════════════════════════════ Cálculo puro (se prueba sin BD) ═════════════════════════════

    /** Reglas que no dependen del vehículo: cuántos bultos y cuánto valor declarado. */
    public static void validarConjunto(List<Bulto> bultos, Parametros p) {
        if (bultos == null || bultos.isEmpty()) throw new ReglaNegocioException(400, "Agrega al menos un paquete");
        if (bultos.size() > p.maxBultos()) {
            throw new ReglaNegocioException(400, "Puedes enviar hasta " + p.maxBultos() + " paquetes en una misma encomienda");
        }
        BigDecimal declarado = bultos.stream().map(b -> b.valorDeclarado() == null ? BigDecimal.ZERO : b.valorDeclarado())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (declarado.compareTo(p.maxValorDeclarado()) > 0) {
            throw new ReglaNegocioException(400, "El valor declarado no puede pasar de " + pesos(p.maxValorDeclarado()));
        }
    }

    public static Resultado calcular(List<Bulto> bultos, String vehiculo, BigDecimal tarifaRuta, TarifaServicio.Ruta ruta,
            Parametros p) {
        BigDecimal real = BigDecimal.ZERO;
        BigDecimal volumetrico = BigDecimal.ZERO;
        BigDecimal facturable = BigDecimal.ZERO;
        BigDecimal declarado = BigDecimal.ZERO;
        int fragiles = 0;
        for (Bulto b : bultos) {
            BigDecimal vol = pesoVolumetrico(b, p.factorVolumetrico());
            real = real.add(b.pesoKg());
            volumetrico = volumetrico.add(vol);
            facturable = facturable.add(b.pesoKg().max(vol));
            if (b.valorDeclarado() != null) declarado = declarado.add(b.valorDeclarado());
            if (b.fragil()) fragiles++;
        }
        facturable = facturable.setScale(2, RoundingMode.HALF_UP);

        String motivo = motivoNoCabe(bultos, vehiculo, facturable, p);
        List<Linea> desglose = new ArrayList<>();
        desglose.add(new Linea("Tarifa de la ruta",
                String.format(Locale.ROOT, "%s km · %d min", ruta.distanciaKm().stripTrailingZeros().toPlainString(),
                        ruta.duracionMin()),
                tarifaRuta));

        // Kilos por encima de los incluidos: se cobra cada kilo empezado
        BigDecimal kgExtra = facturable.subtract(p.kgIncluidos()).max(BigDecimal.ZERO).setScale(0, RoundingMode.CEILING);
        if (kgExtra.signum() > 0 && p.valorKgAdicional().signum() > 0) {
            desglose.add(new Linea("Peso adicional", kgExtra.toPlainString() + " kg sobre " + p.kgIncluidos()
                    .stripTrailingZeros().toPlainString() + " kg incluidos", redondear(kgExtra.multiply(p.valorKgAdicional()))));
        }
        int adicionales = bultos.size() - 1;
        if (adicionales > 0 && p.valorBultoAdicional().signum() > 0) {
            desglose.add(new Linea("Paquetes adicionales", adicionales + (adicionales == 1 ? " paquete" : " paquetes"),
                    redondear(p.valorBultoAdicional().multiply(BigDecimal.valueOf(adicionales)))));
        }
        if (fragiles > 0 && p.recargoFragil().signum() > 0) {
            desglose.add(new Linea("Manejo de frágiles", fragiles + (fragiles == 1 ? " paquete frágil" : " paquetes frágiles"),
                    redondear(p.recargoFragil().multiply(BigDecimal.valueOf(fragiles)))));
        }
        if (declarado.signum() > 0 && p.proteccionPct().signum() > 0) {
            desglose.add(new Linea("Protección del valor declarado",
                    p.proteccionPct().stripTrailingZeros().toPlainString() + " % de " + pesos(declarado),
                    redondear(declarado.multiply(p.proteccionPct()).divide(CIEN, 2, RoundingMode.HALF_UP))));
        }
        BigDecimal total = desglose.stream().map(Linea::valor).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Resultado(vehiculo, motivo == null, motivo, bultos.size(), real.setScale(2, RoundingMode.HALF_UP),
                volumetrico.setScale(2, RoundingMode.HALF_UP), facturable, List.copyOf(desglose), total);
    }

    /** null si el vehículo puede llevarla; si no, el motivo en palabras de la persona. */
    static String motivoNoCabe(List<Bulto> bultos, String vehiculo, BigDecimal facturable, Parametros p) {
        Capacidad c = p.capacidades().get(vehiculo);
        if (c == null) return "Este vehículo no lleva encomiendas";
        String nombre = "moto".equals(vehiculo) ? "la moto" : "taxi".equals(vehiculo) ? "el taxi" : "este vehículo";
        for (Bulto b : bultos) {
            int rango = TAMANOS.indexOf(b.tipo()) + 1;
            if (rango > c.tamanoMax()) return "Un paquete " + nombreTamano(b.tipo()) + " no cabe en " + nombre;
            BigDecimal lado = b.ladoMayor();
            if (lado != null && lado.compareTo(c.maxLadoCm()) > 0) {
                return "Un paquete mide " + lado.stripTrailingZeros().toPlainString() + " cm y en " + nombre + " caben hasta "
                        + c.maxLadoCm().stripTrailingZeros().toPlainString() + " cm";
            }
        }
        if (facturable.compareTo(c.maxKg()) > 0) {
            return "Son " + facturable.stripTrailingZeros().toPlainString() + " kg facturables y " + nombre + " lleva hasta "
                    + c.maxKg().stripTrailingZeros().toPlainString() + " kg";
        }
        return null;
    }

    static BigDecimal pesoVolumetrico(Bulto b, BigDecimal factor) {
        if (!b.tieneMedidas() || factor == null || factor.signum() <= 0) return BigDecimal.ZERO;
        return b.largoCm().multiply(b.anchoCm()).multiply(b.altoCm()).divide(factor, 2, RoundingMode.HALF_UP);
    }

    /** Al múltiplo de $100 hacia arriba (como cobra el conductor en efectivo). */
    static BigDecimal redondear(BigDecimal v) {
        return v.divide(CIEN, 0, RoundingMode.CEILING).multiply(CIEN);
    }

    static String nombreTamano(String tipo) {
        return switch (tipo) {
            case "sobre" -> "sobre";
            case "mediano" -> "mediano";
            case "grande" -> "grande";
            default -> "pequeño";
        };
    }

    static String pesos(BigDecimal v) {
        return "$" + String.format(Locale.ROOT, "%,d", v.setScale(0, RoundingMode.HALF_UP).longValue()).replace(',', '.');
    }
}
