package com.movify.backend.servicios;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.movify.backend.autenticacion.sms.SmsEnviador;
import com.movify.backend.comun.Celular;
import com.movify.backend.comun.ContenidoProhibido;
import com.movify.backend.comun.Datos;
import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.comun.ReglaNegocioException;
import com.movify.backend.servicios.ServicioDtos.CambioEstadoRequest;
import com.movify.backend.servicios.ServicioDtos.SolicitudRequest;

/**
 * Lo propio de la encomienda: datos del paquete, remitente y destinatario, y el PIN de 4 dígitos
 * que el destinatario le da al conductor al recibir. El ciclo de estados es el mismo de todos
 * los servicios (ViajeServicio).
 */
@Service
public class EncomiendaServicio {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** Tope de servicio_encomienda.peso_kg (CHECK de la BD). */
    private static final BigDecimal PESO_MAX_KG = new BigDecimal("30");

    private static final Logger log = LoggerFactory.getLogger(EncomiendaServicio.class);

    private final JdbcTemplate db;
    private final SmsEnviador sms;

    public EncomiendaServicio(JdbcTemplate db, SmsEnviador sms) {
        this.db = db;
        this.sms = sms;
    }

    /** @return el PIN de 4 dígitos que el destinatario le dará al conductor al recibir. */
    String crear(Long servicioId, SolicitudRequest s, Long clienteId) {
        Map<String, Object> cli = db.queryForMap(
                "SELECT trim(nombres || ' ' || COALESCE(apellidos, '')) AS nombre, telefono FROM usuarios WHERE id = ?",
                clienteId);
        String remitente = ViajeServicio.firstNonBlank(s.nombreRemitente(), Datos.texto(cli.get("nombre")));
        String telRemitente = Celular.normalizarContacto(
                ViajeServicio.firstNonBlank(s.telefonoRemitente(), Datos.texto(cli.get("telefono"))));
        String telDestinatario = Celular.normalizarContacto(s.telefonoDestinatario());
        if (telRemitente == null) throw new ReglaNegocioException(400, "Escribe un teléfono válido para quien envía");
        if (Datos.vacioANull(s.nombreDestinatario()) == null) throw new ReglaNegocioException(400, "¿A quién le llega el paquete?");
        if (telDestinatario == null) throw new ReglaNegocioException(400, "Escribe un teléfono válido para quien recibe");
        String descripcion = Datos.vacioANull(s.descripcion());
        if (descripcion == null) throw new ReglaNegocioException(400, "Describe qué vas a enviar");
        // Drogas, armas, dinero, robado…: la encomienda no se crea (la transacción deshace también el servicio)
        ContenidoProhibido.exigirPermitido(descripcion, s.notas(), s.destinoReferencia());

        BigDecimal peso;
        try {
            peso = new BigDecimal(ViajeServicio.firstNonBlank(s.pesoKg(), "1").replace(",", ".").replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException e) {
            throw new ReglaNegocioException(400, "El peso no es válido");
        }
        // Mismos límites que la BD (servicio_encomienda.peso_kg), con un mensaje que la persona entienda
        if (peso.signum() <= 0) throw new ReglaNegocioException(400, "El peso no es válido");
        if (peso.compareTo(PESO_MAX_KG) > 0) {
            throw new ReglaNegocioException(400, "Por ahora llevamos paquetes de hasta " + PESO_MAX_KG + " kg");
        }
        String pin = String.format("%04d", RANDOM.nextInt(10_000));
        db.update("""
                INSERT INTO servicio_encomienda (servicio_id, remitente_nombre, remitente_telefono, destinatario_nombre,
                    destinatario_telefono, descripcion_paquete, tamano, peso_kg, fragil, valor_declarado, codigo_entrega,
                    paga_destinatario)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, servicioId, Datos.recortar(remitente, 120), telRemitente, Datos.recortar(s.nombreDestinatario(), 120),
                telDestinatario, Datos.recortar(descripcion, 300), tamano(s.tamano(), s.tipoPaquete()), peso,
                Boolean.TRUE.equals(s.fragil()), s.valorDeclarado(), pin, Boolean.TRUE.equals(s.pagaDestinatario()));

        // Si quien recibe es otra persona, le llega el código por SMS (Figma "Encomienda 1"). Solo cuando la
        // encomienda ya quedó guardada, y si el SMS falla la encomienda sigue: el remitente también tiene el código.
        if (!telDestinatario.equals(telRemitente)) {
            String texto = "MoviFY: " + Datos.recortar(remitente, 40) + " te envia un paquete. Cuando llegue, dale al "
                    + "conductor este codigo de entrega: " + pin;
            alConfirmar(() -> {
                try {
                    sms.enviar(telDestinatario, texto);
                } catch (RuntimeException e) {
                    log.warn("No se pudo enviar el SMS de la encomienda {}: {}", servicioId, e.getMessage());
                }
            });
        }
        return pin;
    }

    /** Corre la acción después del commit (o enseguida si no hay transacción, p. ej. en pruebas). */
    private static void alConfirmar(Runnable accion) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    accion.run();
                }
            });
        } else {
            accion.run();
        }
    }

    /** "documento", "caja-pequeña"... (web) → pequeno | mediano | grande. */
    static String tamano(String tamano, String tipoPaquete) {
        String t = ViajeServicio.firstNonBlank(tamano, tipoPaquete);
        t = t == null ? "pequeno" : t.toLowerCase(Locale.ROOT).replace("ñ", "n");
        if (t.contains("grande")) return "grande";
        if (t.contains("median")) return "mediano";
        return "pequeno";
    }

    /** Antes de finalizar: el conductor debe dar el PIN que le dio quien recibe (el personal no lo necesita). */
    void prepararEntrega(Long servicioId, CambioEstadoRequest req, boolean personal) {
        Map<String, Object> e = db.queryForMap(
                "SELECT codigo_entrega, destinatario_nombre FROM servicio_encomienda WHERE servicio_id = ?", servicioId);
        if (!personal && !String.valueOf(e.get("codigo_entrega")).equals(req.codigoEntrega())) {
            throw new ReglaNegocioException(400, "El código de entrega no coincide. Pídeselo a quien recibe el paquete.");
        }
        db.update("UPDATE servicio_encomienda SET entregado_a = ? WHERE servicio_id = ?",
                ViajeServicio.firstNonBlank(req.entregadoA(), Datos.texto(e.get("destinatario_nombre"))), servicioId);
    }

    String codigoEntrega(Long servicioId) {
        return db.queryForObject("SELECT codigo_entrega FROM servicio_encomienda WHERE servicio_id = ?", String.class, servicioId);
    }

    /**
     * Datos del paquete de un servicio que la persona ya puede ver (lo que devolvió ViajeServicio.obtener).
     * Los teléfonos de remitente y destinatario siguen la misma regla que el del cliente: no los ve un
     * conductor al que solo se le ofreció el servicio. El PIN solo lo ve quien pidió la encomienda.
     */
    public Map<String, Object> detalle(Map<String, Object> servicioVisible) {
        if (!"encomienda".equals(servicioVisible.get("tipo_servicio"))) {
            throw new RecursoNoEncontradoException("Este servicio no es una encomienda");
        }
        Long id = Datos.aLong(servicioVisible.get("id"));
        List<Map<String, Object>> f = db.queryForList("""
                SELECT remitente_nombre, remitente_telefono, destinatario_nombre, destinatario_telefono,
                       descripcion_paquete, tamano, peso_kg, fragil, valor_declarado, entregado_a, paga_destinatario
                FROM servicio_encomienda WHERE servicio_id = ?
                """, id);
        if (f.isEmpty()) throw new RecursoNoEncontradoException("Encomienda no encontrada");
        Map<String, Object> r = new HashMap<>(f.get(0));
        r.put("servicio_id", id);
        r.put("estado", servicioVisible.get("estado"));
        r.put("estado_codigo", servicioVisible.get("estado_codigo"));
        if (!servicioVisible.containsKey("usuario_telefono")) {
            r.remove("remitente_telefono");
            r.remove("destinatario_telefono");
        }
        if (servicioVisible.get("codigo_entrega") != null) r.put("codigo_entrega", servicioVisible.get("codigo_entrega"));
        return r;
    }
}
