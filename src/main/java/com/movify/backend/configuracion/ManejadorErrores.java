package com.movify.backend.configuracion;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import com.movify.backend.comun.AccesoDenegadoException;
import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.comun.ReglaNegocioException;

/**
 * Respuestas de error uniformes: { "error": "...", "campos": {...} }.
 *
 * Muchas reglas del negocio viven en los triggers del DER v2.3 (RAISE EXCEPTION con
 * mensajes en español pensados para el usuario). Aquí se traducen a 409 con ese mensaje.
 * Los demás errores de SQL nunca se muestran tal cual (filtrarían la estructura); van al log.
 */
@RestControllerAdvice
public class ManejadorErrores {

    private static final Logger log = LoggerFactory.getLogger(ManejadorErrores.class);

    /** Mensajes para las llaves únicas más comunes. */
    private static final Map<String, String> UNICOS = Map.ofEntries(
            Map.entry("ux_usuarios_telefono", "Ya existe una cuenta con ese celular. Inicia sesión."),
            Map.entry("ux_usuarios_email", "Ese correo ya está registrado."),
            Map.entry("ux_usuarios_google_sub", "Esa cuenta de Google ya está vinculada a otro usuario."),
            Map.entry("ux_conductores_identificacion", "Ese documento de identidad ya está registrado por otro conductor."),
            Map.entry("ux_vehiculos_placa", "Esa placa ya está registrada."),
            Map.entry("ux_servicios_un_activo_por_conductor", "Ya tienes un servicio en curso."),
            Map.entry("ux_servicios_transporte_activo_por_cliente", "Ya tienes un viaje en curso. Termínalo o cancélalo antes de pedir otro."),
            Map.entry("ux_calificaciones_una_por_servicio", "Ya calificaste este servicio."),
            Map.entry("ux_direcciones_etiqueta", "Ya tienes una dirección guardada con ese nombre."),
            Map.entry("ux_contactos_telefono", "Ese contacto ya está en tu lista."),
            Map.entry("ux_ofertas_servicio_conductor", "Ya respondiste a esta solicitud."));

    /** Mensajes para los CHECK que el usuario sí puede provocar. */
    private static final Map<String, String> CHECKS = Map.ofEntries(
            Map.entry("ck_vehiculos_placa", "La placa no tiene el formato correcto (taxi: ABC123, moto: ABC12D)."),
            Map.entry("ck_vehiculos_taxi_empresa", "Para un taxi debes indicar la empresa afiliadora."),
            Map.entry("ck_usuarios_telefono_formato", "El celular debe ser colombiano (+57 y 10 dígitos)."),
            Map.entry("ck_usuarios_email_formato", "El correo no es válido."),
            Map.entry("ck_servicios_origen_destino", "El origen y el destino no pueden ser el mismo punto."),
            Map.entry("ck_servicios_moto_un_pasajero", "En moto solo puede ir un pasajero."),
            Map.entry("ck_conductores_categorias", "Las categorías de licencia no son válidas."),
            Map.entry("ck_conductores_rechazo_motivo", "Debes escribir el motivo."),
            Map.entry("ck_documentos_rechazo", "Debes escribir por qué se rechaza el documento."),
            Map.entry("ck_usuarios_bloqueo_motivo", "Debes escribir el motivo del bloqueo."),
            Map.entry("ck_servicios_cancelacion", "Debes indicar el motivo de la cancelación."));

    @ExceptionHandler(AccesoDenegadoException.class)
    public ResponseEntity<Map<String, Object>> accesoDenegado(AccesoDenegadoException e) {
        return cuerpo(403, e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> accesoDenegadoSpring(AccessDeniedException e) {
        return cuerpo(403, "No tienes permiso para esta acción");
    }

    @ExceptionHandler(RecursoNoEncontradoException.class)
    public ResponseEntity<Map<String, Object>> noEncontrado(RecursoNoEncontradoException e) {
        return cuerpo(404, e.getMessage());
    }

    @ExceptionHandler(ReglaNegocioException.class)
    public ResponseEntity<Map<String, Object>> reglaNegocio(ReglaNegocioException e) {
        return cuerpo(e.getStatus(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validacion(MethodArgumentNotValidException e) {
        Map<String, String> campos = new LinkedHashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            campos.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", campos.isEmpty() ? "Revisa los datos enviados" : campos.values().iterator().next());
        body.put("campos", campos);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class,
            IllegalArgumentException.class })
    public ResponseEntity<Map<String, Object>> peticionInvalida(Exception e) {
        log.debug("Petición inválida: {}", e.getMessage());
        return cuerpo(400, "La petición no tiene el formato esperado");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> archivoGrande(MaxUploadSizeExceededException e) {
        return cuerpo(413, "El archivo supera el tamaño permitido (10 MB)");
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> baseDeDatos(DataAccessException e) {
        PSQLException pg = buscarPsql(e);
        if (pg != null && pg.getServerErrorMessage() != null) {
            ServerErrorMessage m = pg.getServerErrorMessage();
            String estado = pg.getSQLState();
            String restriccion = m.getConstraint();
            switch (estado) {
                case "23505": // única
                    return cuerpo(409, UNICOS.getOrDefault(restriccion, "Ese registro ya existe."));
                case "23514": // CHECK o RAISE de un trigger
                    if (restriccion == null) return cuerpo(409, m.getMessage());
                    log.info("CHECK violado: {}", restriccion);
                    return cuerpo(400, CHECKS.getOrDefault(restriccion, "Hay datos que no cumplen las reglas de MoviFY."));
                case "23503": // llave foránea
                    return cuerpo(400, "Uno de los datos hace referencia a algo que no existe.");
                case "23502": // NOT NULL
                    return cuerpo(400, "Falta un dato obligatorio.");
                case "P0002": // no_data_found en funciones del DER
                    return cuerpo(404, m.getMessage());
                case "42501": // bitácoras de solo inserción
                    return cuerpo(409, "Ese registro no se puede modificar.");
                default:
                    break;
            }
        }
        log.error("Error de base de datos", e);
        return cuerpo(500, "Ocurrió un error interno. Intenta de nuevo.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> generico(Exception e) {
        log.error("Error no controlado", e);
        return cuerpo(500, "Ocurrió un error interno. Intenta de nuevo.");
    }

    private static PSQLException buscarPsql(Throwable t) {
        while (t != null) {
            if (t instanceof PSQLException p) return p;
            if (t instanceof SQLException s && s.getNextException() instanceof PSQLException p) return p;
            t = t.getCause();
        }
        return null;
    }

    private static ResponseEntity<Map<String, Object>> cuerpo(int status, String mensaje) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", mensaje);
        return ResponseEntity.status(status).body(body);
    }
}
