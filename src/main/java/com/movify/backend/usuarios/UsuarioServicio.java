package com.movify.backend.usuarios;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.movify.backend.autenticacion.AutenticacionServicio;
import com.movify.backend.autenticacion.CodigoVerificacionServicio;
import com.movify.backend.comun.ActorBd;
import com.movify.backend.comun.ArchivoServicio;
import com.movify.backend.comun.Celular;
import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.comun.ReglaNegocioException;
import com.movify.backend.comun.Roles;
import com.movify.backend.seguridad.JwtUtil;
import com.movify.backend.seguridad.SesionActual;
import com.movify.backend.usuarios.UsuarioDtos.ActualizarPerfilRequest;
import com.movify.backend.usuarios.UsuarioDtos.CambiarPasswordRequest;
import com.movify.backend.usuarios.UsuarioDtos.ContactoRequest;
import com.movify.backend.usuarios.UsuarioDtos.DireccionRequest;
import com.movify.backend.usuarios.UsuarioDtos.DispositivoRequest;

/** Perfil, foto, celular, direcciones, contactos de emergencia y cuenta del usuario autenticado. */
@Service
public class UsuarioServicio {

    private final UsuarioRepositorio usuarios;
    private final SesionActual sesion;
    private final JdbcTemplate db;
    private final PasswordEncoder encoder;
    private final ArchivoServicio archivos;
    private final CodigoVerificacionServicio codigos;
    private final JwtUtil jwt;
    private final ActorBd actor;

    public UsuarioServicio(UsuarioRepositorio usuarios, SesionActual sesion, JdbcTemplate db, PasswordEncoder encoder,
            ArchivoServicio archivos, CodigoVerificacionServicio codigos, JwtUtil jwt, ActorBd actor) {
        this.usuarios = usuarios;
        this.sesion = sesion;
        this.db = db;
        this.encoder = encoder;
        this.archivos = archivos;
        this.codigos = codigos;
        this.jwt = jwt;
        this.actor = actor;
    }

    // ───────────── Perfil ─────────────

    public Map<String, Object> perfil() {
        Usuario u = actual();
        Map<String, Object> r = datos(u);
        r.putAll(db.queryForMap("""
                SELECT COUNT(*) FILTER (WHERE estado = 'finalizado') AS viajes_finalizados,
                       COALESCE(SUM(valor_final) FILTER (WHERE estado = 'finalizado'), 0) AS total_gastado
                FROM servicios WHERE cliente_id = ?
                """, u.id()));
        return r;
    }

    @Transactional
    public Map<String, Object> actualizar(ActualizarPerfilRequest req) {
        Usuario u = actual();
        String nombres = u.nombres();
        String apellidos = u.apellidos();
        if (req.nombre() != null && !req.nombre().isBlank()) {
            String[] p = partir(req.nombre());
            nombres = p[0];
            apellidos = p[1];
        }
        if (req.nombres() != null && !req.nombres().isBlank()) nombres = req.nombres().trim();
        if (req.apellidos() != null) apellidos = req.apellidos().isBlank() ? null : req.apellidos().trim();

        String email = u.email();
        boolean emailVerificado = u.emailVerificado();
        if (req.correo() != null) {
            String nuevo = req.correo().isBlank() ? null : req.correo().trim().toLowerCase(Locale.ROOT);
            if (nuevo == null ? email != null : !nuevo.equalsIgnoreCase(email)) {
                if (nuevo != null && usuarios.existeEmail(nuevo)) throw new ReglaNegocioException("Ese correo ya está registrado.");
                email = nuevo;
                emailVerificado = false;
            }
        }

        // El celular solo se cambia con código SMS (/telefono/codigo). Si llega igual, se ignora.
        if (req.telefono() != null && !req.telefono().isBlank()) {
            String tel = Celular.normalizar(req.telefono());
            if (tel == null) throw new ReglaNegocioException(400, "Escribe un celular colombiano de 10 dígitos");
            if (!tel.equals(u.telefono())) {
                if (u.telefonoVerificado()) {
                    throw new ReglaNegocioException(400, "Para cambiar tu celular verifica el número nuevo con un código SMS");
                }
                if (usuarios.existeTelefono(tel)) throw new ReglaNegocioException("Ya existe una cuenta con ese celular.");
                db.update("UPDATE usuarios SET telefono = ?, telefono_verificado = false WHERE id = ?", tel, u.id());
            }
        }
        String foto = req.foto() == null ? u.fotoUrl() : (req.foto().isBlank() ? null : req.foto().trim());

        db.update("""
                UPDATE usuarios SET nombres = ?, apellidos = ?, email = ?, email_verificado = ?, foto_url = ?
                WHERE id = ?
                """, nombres, apellidos, email, emailVerificado, foto, u.id());
        return datos(actual());
    }

    /** Web: contraseña (8+ caracteres). App: clave de 4 números. */
    public void cambiarPassword(CambiarPasswordRequest req) {
        Usuario u = actual();
        if (u.passwordHash() == null || !encoder.matches(req.actual(), u.passwordHash())) {
            throw new ReglaNegocioException(400, "La contraseña actual no es correcta");
        }
        boolean esClave = req.nueva().matches("\\d{4}");
        if (esClave && AutenticacionServicio.claveDebil(req.nueva())) {
            throw new ReglaNegocioException(400, "Esa clave es muy fácil de adivinar. Prueba con otra.");
        }
        if (!esClave && req.nueva().length() < 8) {
            throw new ReglaNegocioException(400, "La contraseña debe tener al menos 8 caracteres");
        }
        usuarios.actualizarPassword(u.id(), encoder.encode(req.nueva()));
    }

    // ───────────── Foto ─────────────

    /** @return la URL pública de la foto nueva (la anterior se borra del disco). */
    public String subirFoto(MultipartFile foto) {
        Usuario u = actual();
        ArchivoServicio.Guardado g = archivos.guardar(foto, "foto", false);
        String url = "/api/usuarios/" + u.id() + "/foto/" + g.nombre();
        db.update("UPDATE usuarios SET foto_url = ? WHERE id = ?", url, u.id());
        if (u.fotoUrl() != null && u.fotoUrl().startsWith("/api/usuarios/")) {
            archivos.borrar(u.fotoUrl().substring(u.fotoUrl().lastIndexOf('/') + 1));
        }
        return url;
    }

    /** La foto solo se entrega si es la actual de ese usuario (no se puede pedir cualquier archivo). */
    public ArchivoServicio.Archivo foto(Long usuarioId, String nombre) {
        String url = db.queryForList("SELECT foto_url FROM usuarios WHERE id = ?", String.class, usuarioId).stream()
                .findFirst().orElse(null);
        if (url == null || !url.endsWith("/" + nombre)) throw new RecursoNoEncontradoException("Foto no encontrada");
        return archivos.obtener(nombre);
    }

    // ───────────── Verificar o cambiar el celular (web y app) ─────────────

    public Map<String, Object> codigoTelefono(String celular, String ip) {
        Usuario u = actual();
        String tel = Celular.exigir(celular);
        if (!tel.equals(u.telefono()) && usuarios.existeTelefono(tel)) {
            throw new ReglaNegocioException("Ya existe una cuenta con ese celular.");
        }
        CodigoVerificacionServicio.Envio e = codigos.enviar(tel, CodigoVerificacionServicio.CAMBIO_TELEFONO, ip);
        Map<String, Object> r = new HashMap<>();
        r.put("message", "Te enviamos un código al " + Celular.visible(tel));
        r.put("vigenciaSeg", e.vigenciaSeg());
        if (e.codigoDesarrollo() != null) r.put("codigoDesarrollo", e.codigoDesarrollo());
        return r;
    }

    /** Después de /api/auth/codigo/verificar con proposito "cambio_telefono". @return el celular verificado. */
    public String verificarTelefono(String verificacionToken) {
        Usuario u = actual();
        String tel = jwt.leerVerificacion(verificacionToken, CodigoVerificacionServicio.CAMBIO_TELEFONO);
        db.update("UPDATE usuarios SET telefono = ?, telefono_verificado = true WHERE id = ?", tel, u.id());
        return tel;
    }

    // ───────────── Direcciones favoritas ─────────────

    public List<Map<String, Object>> direcciones() {
        return db.queryForList("""
                SELECT id, etiqueta, direccion, barrio, referencia, lat, lng
                FROM direcciones_favoritas WHERE usuario_id = ? ORDER BY creado_en
                """, sesion.usuarioId());
    }

    public Long guardarDireccion(DireccionRequest d) {
        return db.queryForObject("""
                INSERT INTO direcciones_favoritas (usuario_id, etiqueta, direccion, barrio, referencia, lat, lng)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (usuario_id, etiqueta) DO UPDATE
                   SET direccion = EXCLUDED.direccion, barrio = EXCLUDED.barrio, referencia = EXCLUDED.referencia,
                       lat = EXCLUDED.lat, lng = EXCLUDED.lng
                RETURNING id
                """, Long.class, sesion.usuarioId(), d.etiqueta().trim(), d.direccion().trim(), d.barrio(),
                d.referencia(), d.lat(), d.lng());
    }

    public void borrarDireccion(Long id) {
        int n = db.update("DELETE FROM direcciones_favoritas WHERE id = ? AND usuario_id = ?", id, sesion.usuarioId());
        if (n == 0) throw new RecursoNoEncontradoException("Dirección no encontrada");
    }

    // ───────────── Contactos de emergencia (SOS) ─────────────

    public List<Map<String, Object>> contactos() {
        return db.queryForList("SELECT id, nombre, telefono, parentesco FROM contactos_emergencia WHERE usuario_id = ?",
                sesion.usuarioId());
    }

    public Long agregarContacto(ContactoRequest c) {
        String tel = Celular.normalizarContacto(c.telefono());
        if (tel == null) throw new ReglaNegocioException(400, "Escribe un celular o fijo colombiano");
        return db.queryForObject("""
                INSERT INTO contactos_emergencia (usuario_id, nombre, telefono, parentesco) VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, sesion.usuarioId(), c.nombre().trim(), tel, c.parentesco());
    }

    /**
     * Personas que desvinculé (no me vuelven a emparejar). Solo el nombre de pila y la inicial del apellido:
     * nada de teléfono ni foto.
     */
    public List<Map<String, Object>> bloqueados() {
        return db.queryForList("""
                SELECT b.bloqueado_id AS id,
                       trim(split_part(u.nombres, ' ', 1) || ' ' || COALESCE(left(u.apellidos, 1) || '.', '')) AS nombre,
                       b.motivo, b.creado_en
                FROM usuarios_bloqueados b JOIN usuarios u ON u.id = b.bloqueado_id
                WHERE b.usuario_id = ?
                ORDER BY b.creado_en DESC
                """, sesion.usuarioId());
    }

    public void desbloquear(Long bloqueadoId) {
        int n = db.update("DELETE FROM usuarios_bloqueados WHERE usuario_id = ? AND bloqueado_id = ?", sesion.usuarioId(), bloqueadoId);
        if (n == 0) throw new RecursoNoEncontradoException("No lo tenías desvinculado");
    }

    public void borrarContacto(Long id) {
        int n = db.update("DELETE FROM contactos_emergencia WHERE id = ? AND usuario_id = ?", id, sesion.usuarioId());
        if (n == 0) throw new RecursoNoEncontradoException("Contacto no encontrado");
    }

    // ───────────── Dispositivo (notificaciones push) ─────────────

    public Long registrarDispositivo(DispositivoRequest d) {
        return db.queryForObject("""
                INSERT INTO dispositivos (usuario_id, plataforma, token_push, modelo, version_app)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (token_push) DO UPDATE
                   SET usuario_id = EXCLUDED.usuario_id, plataforma = EXCLUDED.plataforma, modelo = EXCLUDED.modelo,
                       version_app = EXCLUDED.version_app, activo = true, ultima_conexion = now()
                RETURNING id
                """, Long.class, sesion.usuarioId(), d.plataforma(), d.tokenPush(), d.modelo(), d.versionApp());
    }

    public void bajaDispositivo(String tokenPush) {
        db.update("UPDATE dispositivos SET activo = false WHERE token_push = ? AND usuario_id = ?", tokenPush, sesion.usuarioId());
    }

    // ───────────── Eliminar la cuenta (Ley 1581 y Google Play) ─────────────

    @Transactional
    public void eliminarCuenta() {
        Long id = sesion.usuarioId();
        actor.declarar(id, ActorBd.SISTEMA);
        Usuario u = actual();
        db.queryForList("SELECT fn_eliminar_cuenta(?)", id);
        if (u.fotoUrl() != null && u.fotoUrl().startsWith("/api/usuarios/")) {
            archivos.borrar(u.fotoUrl().substring(u.fotoUrl().lastIndexOf('/') + 1));
        }
    }

    // ───────────── Utilidades ─────────────

    private Usuario actual() {
        return usuarios.porId(sesion.usuarioId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario no encontrado"));
    }

    private Map<String, Object> datos(Usuario u) {
        Map<String, Object> r = new HashMap<>();
        r.put("id", u.id());
        r.put("nombre", u.nombreCompleto());
        r.put("nombres", u.nombres());
        r.put("apellidos", u.apellidos());
        r.put("correo", u.email());
        r.put("telefono", u.telefono());
        r.put("telefono_verificado", u.telefonoVerificado());
        r.put("foto", u.fotoUrl());
        r.put("calificacion", u.calificacionPromedio());
        r.put("roles", usuarios.roles(u.id()));
        r.put("rol", Roles.principal(usuarios.roles(u.id())));
        r.put("fecha_registro", u.creadoEn());
        return r;
    }

    private static String[] partir(String completo) {
        String[] p = completo.trim().split("\\s+");
        if (p.length == 1) return new String[] { p[0], null };
        int corte = p.length >= 4 ? 2 : 1;
        return new String[] { String.join(" ", java.util.Arrays.copyOfRange(p, 0, corte)),
                String.join(" ", java.util.Arrays.copyOfRange(p, corte, p.length)) };
    }
}
