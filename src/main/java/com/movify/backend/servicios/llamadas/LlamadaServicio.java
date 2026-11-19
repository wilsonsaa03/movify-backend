package com.movify.backend.servicios.llamadas;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.movify.backend.comun.AccesoDenegadoException;
import com.movify.backend.comun.Datos;
import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.comun.ReglaNegocioException;
import com.movify.backend.seguridad.SesionActual;

/**
 * "Llamar" dentro del viaje sin mostrar el número (como Uber y DiDi). MoviFY llama primero a quien tocó el botón
 * y, cuando contesta, lo comunica con el otro; los dos ven el número de MoviFY, nunca el celular del otro.
 *
 * Reglas: solo el cliente y el conductor asignado, solo con el servicio activo, con un máximo de llamadas por
 * servicio y un tiempo mínimo entre llamadas (configuracion_sistema). No se guardan números, solo quién llamó.
 *
 * Proveedor:
 *   - Twilio Voice si están TWILIO_ACCOUNT_SID + credenciales + un número con voz (TWILIO_NUMERO_VOZ o TWILIO_FROM).
 *   - Si no, con SMS_CONSOLA=true (solo desarrollo) la llamada se simula: queda en el log y la app lo dice.
 *   - Si no hay nada, 503 y la app sugiere el chat.
 */
@Service
public class LlamadaServicio {

    private static final Logger log = LoggerFactory.getLogger(LlamadaServicio.class);
    private static final List<String> ACTIVOS = List.of("asignado", "en_origen", "en_curso");

    private final JdbcTemplate db;
    private final SesionActual sesion;
    private final TwilioLlamadas twilio;
    private final boolean simulada;

    public LlamadaServicio(JdbcTemplate db, SesionActual sesion,
            @Value("${app.sms.twilio.account-sid:}") String accountSid,
            @Value("${app.sms.twilio.auth-token:}") String authToken,
            @Value("${app.sms.twilio.api-key:}") String apiKey,
            @Value("${app.sms.twilio.api-secret:}") String apiSecret,
            @Value("${app.sms.twilio.from:}") String from,
            @Value("${app.llamadas.twilio.numero:}") String numeroVoz,
            @Value("${app.sms.consola:false}") boolean consola) {
        this.db = db;
        this.sesion = sesion;
        String numero = numeroVoz.isBlank() ? from : numeroVoz;
        boolean credenciales = !apiKey.isBlank() && !apiSecret.isBlank() || !authToken.isBlank();
        if (!accountSid.isBlank() && credenciales && !numero.isBlank()) {
            this.twilio = apiKey.isBlank() ? new TwilioLlamadas(accountSid, accountSid, authToken, numero)
                    : new TwilioLlamadas(accountSid, apiKey, apiSecret, numero);
            this.simulada = false;
            log.info("Llamadas con número protegido por Twilio Voice");
        } else {
            this.twilio = null;
            this.simulada = consola;
            if (consola) log.info("Llamadas con número protegido SIMULADAS (SMS_CONSOLA=true, solo desarrollo)");
        }
    }

    public boolean disponible() {
        return twilio != null || simulada;
    }

    /** POST /api/servicios/{id}/llamar */
    public Map<String, Object> llamar(Long servicioId) {
        Map<String, Object> s = db.queryForList("""
                SELECT s.estado, s.tipo_servicio, s.cliente_id, s.conductor_id, s.pasajero_telefono, s.pasajero_nombre,
                       cli.telefono AS tel_cliente, split_part(trim(cli.nombres), ' ', 1) AS nombre_cliente,
                       con.telefono AS tel_conductor, split_part(trim(con.nombres), ' ', 1) AS nombre_conductor
                FROM servicios s
                JOIN usuarios cli ON cli.id = s.cliente_id
                LEFT JOIN usuarios con ON con.id = s.conductor_id
                WHERE s.id = ?
                """, servicioId).stream().findFirst().orElseThrow(() -> new RecursoNoEncontradoException("Servicio no encontrado"));
        Long yo = sesion.usuarioId();
        Long cliente = Datos.aLong(s.get("cliente_id"));
        Long conductor = Datos.aLong(s.get("conductor_id"));
        boolean soyCliente = Objects.equals(cliente, yo);
        if (!soyCliente && !Objects.equals(conductor, yo)) {
            throw new AccesoDenegadoException("Solo el cliente y el conductor del servicio pueden llamarse");
        }
        if (conductor == null || !ACTIVOS.contains(String.valueOf(s.get("estado")))) {
            throw new ReglaNegocioException(400, "Solo puedes llamar mientras el servicio está en curso.");
        }
        if (!disponible()) {
            throw new ReglaNegocioException(503, "Las llamadas no están disponibles ahora. Escríbele por el chat.");
        }

        Integer hechas = db.queryForObject("SELECT count(*) FROM llamadas_servicio WHERE servicio_id = ? AND llama_id = ?",
                Integer.class, servicioId, yo);
        int max = db.queryForObject("SELECT fn_config_num('max_llamadas_servicio', 6)::int", Integer.class);
        if (hechas != null && hechas >= max) {
            throw new ReglaNegocioException(429, "Ya llamaste varias veces en este servicio. Sigue por el chat.");
        }
        Boolean muyPronto = db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM llamadas_servicio WHERE servicio_id = ? AND llama_id = ?
                                 AND creado_en > now() - make_interval(secs => fn_config_num('segundos_entre_llamadas', 20)::int))
                """, Boolean.class, servicioId, yo);
        if (Boolean.TRUE.equals(muyPronto)) {
            throw new ReglaNegocioException(429, "Espera unos segundos antes de volver a llamar.");
        }

        String miTelefono = (String) s.get(soyCliente ? "tel_cliente" : "tel_conductor");
        // Si el viaje es para otra persona, el conductor habla con quien viaja (ese número lo dio el cliente)
        String suTelefono = soyCliente ? (String) s.get("tel_conductor")
                : Datos.vacioANull((String) s.get("pasajero_telefono")) != null ? (String) s.get("pasajero_telefono")
                        : (String) s.get("tel_cliente");
        if (miTelefono == null) throw new ReglaNegocioException(400, "Tu cuenta no tiene celular. Usa el chat.");
        if (suTelefono == null) throw new ReglaNegocioException(400, "No hay un celular para comunicarte. Usa el chat.");
        Long recibe = soyCliente ? conductor : cliente;
        String otro = soyCliente ? "tu conductor " + Datos.nvl((String) s.get("nombre_conductor"))
                : Datos.vacioANull((String) s.get("pasajero_nombre")) != null ? (String) s.get("pasajero_nombre")
                        : "tu pasajero " + Datos.nvl((String) s.get("nombre_cliente"));
        String saludo = "Hola, es MoviFY. Te comunicamos con " + otro.trim() + ". Ninguno verá el número del otro.";

        String sid = null;
        String modo;
        if (twilio != null) {
            sid = twilio.conectar(miTelefono, suTelefono, saludo);
            modo = "twilio";
        } else {
            modo = "simulada";
            log.info("[LLAMADA SIMULADA] servicio {}: {} {} → {} (sin Twilio Voice; en producción Twilio llama a los dos)",
                    servicioId, soyCliente ? "cliente" : "conductor", Datos.ocultarTelefono(miTelefono),
                    Datos.ocultarTelefono(suTelefono));
        }
        db.update("INSERT INTO llamadas_servicio (servicio_id, llama_id, recibe_id, modo, sid_proveedor) VALUES (?, ?, ?, ?, ?)",
                servicioId, yo, recibe, modo, sid);
        String mensaje = "twilio".equals(modo)
                ? "Te estamos llamando. Contesta y te comunicamos con " + otro.trim() + "."
                : "Modo de prueba: la llamada quedó registrada, pero no suena (falta configurar Twilio Voice).";
        return Map.of("modo", modo, "message", mensaje);
    }
}
