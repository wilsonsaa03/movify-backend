package com.movify.backend.domicilios;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.movify.backend.comun.Datos;

/**
 * Cupones de domicilio (simulados: el descuento lo asume MoviFY, no hay cobro real). Tipos:
 *   porcentaje → % de los productos, con tope ("20 % hasta $10.000")
 *   valor      → monto fijo sobre los productos ("$5.000 menos")
 *   envio      → descuento sobre el domicilio, hasta "valor" ("domicilio gratis" con un valor alto)
 * Reglas: activo, sin vencer, compra mínima, comercio (si el cupón es de uno), primer pedido, usos por cliente y totales.
 * Los usos solo cuentan en pedidos que no se cancelaron.
 */
@Service
public class CuponesDomicilio {

    private final JdbcTemplate db;

    public CuponesDomicilio(JdbcTemplate db) {
        this.db = db;
    }

    /** Resultado de aplicar un cupón: si no es válido, el mensaje dice por qué y el descuento es 0. */
    public record Resultado(boolean valido, String mensaje, BigDecimal descuento, Long cuponId, String codigo, String tipo) {

        static Resultado no(String codigo, String mensaje) {
            return new Resultado(false, mensaje, BigDecimal.ZERO, null, codigo, null);
        }
    }

    public static String normalizar(String codigo) {
        return codigo == null ? "" : codigo.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    public Resultado evaluar(String codigoTexto, Long usuarioId, Long comercioId, BigDecimal subtotal, BigDecimal domicilio) {
        String codigo = normalizar(codigoTexto);
        if (codigo.isEmpty()) return Resultado.no(codigo, null);
        // El vencimiento se compara en la base (el driver entrega la fecha como Timestamp, no como OffsetDateTime)
        Map<String, Object> c = db.queryForList(
                "SELECT *, (vence_en IS NOT NULL AND vence_en < now()) AS vencido FROM cupones_domicilio WHERE codigo = ?", codigo)
                .stream().findFirst().orElse(null);
        if (c == null || !Boolean.TRUE.equals(c.get("activo"))) return Resultado.no(codigo, "Ese cupón no existe.");
        if (Boolean.TRUE.equals(c.get("vencido"))) return Resultado.no(codigo, "Ese cupón ya venció.");
        Long soloComercio = Datos.aLong(c.get("comercio_id"));
        if (soloComercio != null && !Objects.equals(soloComercio, comercioId)) {
            return Resultado.no(codigo, "Ese cupón no aplica en este comercio.");
        }
        BigDecimal minimo = (BigDecimal) c.get("minimo_compra");
        if (subtotal.compareTo(minimo) < 0) {
            return Resultado.no(codigo, "Ese cupón aplica en compras desde " + pesos(minimo) + ".");
        }
        Long cuponId = Datos.aLong(c.get("id"));
        if (Boolean.TRUE.equals(c.get("solo_primer_pedido"))) {
            Integer antes = db.queryForObject("""
                    SELECT count(*) FROM servicios WHERE cliente_id = ? AND tipo_servicio = 'domicilio' AND estado = 'finalizado'
                    """, Integer.class, usuarioId);
            if (antes != null && antes > 0) return Resultado.no(codigo, "Ese cupón es solo para tu primer domicilio.");
        }
        Integer mios = db.queryForObject("""
                SELECT count(*) FROM cupones_usados u JOIN servicios s ON s.id = u.servicio_id
                WHERE u.cupon_id = ? AND u.usuario_id = ? AND s.estado NOT IN ('cancelado', 'expirado')
                """, Integer.class, cuponId, usuarioId);
        if (mios != null && mios >= ((Number) c.get("usos_por_cliente")).intValue()) {
            return Resultado.no(codigo, "Ya usaste este cupón.");
        }
        if (c.get("usos_totales") instanceof Number total) {
            Integer todos = db.queryForObject("""
                    SELECT count(*) FROM cupones_usados u JOIN servicios s ON s.id = u.servicio_id
                    WHERE u.cupon_id = ? AND s.estado NOT IN ('cancelado', 'expirado')
                    """, Integer.class, cuponId);
            if (todos != null && todos >= total.intValue()) return Resultado.no(codigo, "Ese cupón ya se agotó.");
        }

        String tipo = String.valueOf(c.get("tipo"));
        BigDecimal valor = (BigDecimal) c.get("valor");
        BigDecimal descuento = switch (tipo) {
            case "porcentaje" -> subtotal.multiply(valor).divide(new BigDecimal("100"), 0, RoundingMode.DOWN);
            case "envio" -> valor.min(domicilio);
            default -> valor.min(subtotal);
        };
        if (c.get("tope_descuento") instanceof BigDecimal tope) descuento = descuento.min(tope);
        descuento = descuento.max(BigDecimal.ZERO).setScale(0, RoundingMode.DOWN);
        return new Resultado(true, String.valueOf(c.get("descripcion")), descuento, cuponId, codigo, tipo);
    }

    void registrarUso(Resultado r, Long usuarioId, Long servicioId) {
        if (!r.valido() || r.cuponId() == null) return;
        db.update("INSERT INTO cupones_usados (cupon_id, usuario_id, servicio_id, descuento) VALUES (?, ?, ?, ?)",
                r.cuponId(), usuarioId, servicioId, r.descuento());
    }

    static String pesos(BigDecimal v) {
        return "$" + String.format(Locale.ROOT, "%,d", v.longValue()).replace(',', '.');
    }
}
