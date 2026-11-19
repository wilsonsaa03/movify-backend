package com.movify.backend.servicios.pagos;

import java.util.List;
import java.util.Map;

/**
 * Quien cobra de verdad: Wompi (sandbox o producción) o el simulador local.
 * Los estados siguen los de Wompi: PENDING, APPROVED, DECLINED, ERROR (y VOIDED solo para tarjetas).
 */
public interface ProveedorPagos {

    /** En Resultado.datos: la página del banco (PSE, Bancolombia) a la que la app debe llevar al cliente. */
    String URL_PAGO = "url_pago";

    /** simulado | wompi */
    String nombre();

    /** Enlace a los términos que el cliente acepta al pagar (null si no aplica). */
    String terminosUrl();

    /** Bancos de PSE: [{ codigo, nombre }]. */
    List<Map<String, String>> bancosPse();

    /**
     * @param metodo  nequi | daviplata | tarjeta | pse | bancolombia
     * @param cuenta  Nequi: celular de 10 dígitos. Daviplata y PSE: número de documento. Tarjeta: token de la tarjeta.
     * @param extra   lo que pide cada medio: cuotas (tarjeta); tipo_persona, tipo_documento, banco, nombre, telefono (PSE)
     */
    record Cobro(String referencia, long montoCentavos, String metodo, String cuenta, String correo, String descripcion,
            Map<String, String> extra) {

        public String extra(String clave) {
            return extra == null ? null : extra.get(clave);
        }
    }

    /**
     * @param datos  lo que el proveedor necesita guardar para seguir (pagos.datos_proveedor). Solo url_pago sale a la app.
     */
    record Resultado(String transaccionId, String estado, boolean requiereOtp, String detalle, Map<String, Object> datos) {

        public boolean finalizado() {
            return !"PENDING".equals(estado);
        }
    }

    Resultado iniciar(Cobro cobro);

    /** Estado actual de una transacción pendiente. */
    Resultado consultar(String transaccionId, Map<String, Object> datos, long creadoMs);

    /** Daviplata: el código de 6 números que le llegó por SMS al cliente. */
    Resultado validarOtp(String transaccionId, Map<String, Object> datos, String codigo);
}
