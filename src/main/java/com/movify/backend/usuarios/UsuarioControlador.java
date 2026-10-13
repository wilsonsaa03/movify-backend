package com.movify.backend.usuarios;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.movify.backend.autenticacion.AutenticacionServicio;
import com.movify.backend.autenticacion.CodigoVerificacionServicio;
import com.movify.backend.comun.ActorBd;
import com.movify.backend.comun.ArchivoServicio;
import com.movify.backend.comun.Celular;
import com.movify.backend.comun.RecursoNoEncontradoException;
import com.movify.backend.comun.ReglaNegocioException;
import com.movify.backend.comun.Roles;
import com.movify.backend.configuracion.ContextoPeticion;
import com.movify.backend.seguridad.JwtUtil;
import com.movify.backend.seguridad.SesionActual;
import com.movify.backend.usuarios.UsuarioDtos.ActualizarPerfilRequest;
import com.movify.backend.usuarios.UsuarioDtos.CambiarPasswordRequest;
import com.movify.backend.usuarios.UsuarioDtos.CodigoTelefonoRequest;
import com.movify.backend.usuarios.UsuarioDtos.ContactoRequest;
import com.movify.backend.usuarios.UsuarioDtos.DireccionRequest;
import com.movify.backend.usuarios.UsuarioDtos.DispositivoRequest;
import com.movify.backend.usuarios.UsuarioDtos.VerificarTelefonoRequest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/** Perfil del usuario autenticado. El id sale del token, nunca de la URL. */
@RestController
@RequestMapping("/api/usuarios")
public class UsuarioControlador {

    private final UsuarioRepositorio usuarios;
    private final SesionActual sesion;
    private final JdbcTemplate db;
    private final PasswordEncoder encoder;
    private final ArchivoServicio archivos;
    private final CodigoVerificacionServicio codigos;
    private final JwtUtil jwt;
    private final ActorBd actor;

    public UsuarioControlador(UsuarioRepositorio usuarios, SesionActual sesion, JdbcTemplate db, PasswordEncoder encoder,
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

    @GetMapping("/perfil")
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

    @PutMapping("/perfil")
    @Transactional
    public Map<String, Object> actualizar(@Valid @RequestBody ActualizarPerfilRequest req) {
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
    @PutMapping("/password")
    public Map<String, Object> cambiarPassword(@Valid @RequestBody CambiarPasswordRequest req) {
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
        return Map.of("message", "Contraseña actualizada correctamente");
    }

    // ───────────── Foto ─────────────

    @PostMapping("/perfil/foto")
    public Map<String, Object> subirFoto(@RequestParam("foto") MultipartFile foto) {
        Usuario u = actual();
        ArchivoServicio.Guardado g = archivos.guardar(foto, "foto", false);
        String url = "/api/usuarios/" + u.id() + "/foto/" + g.nombre();
        db.update("UPDATE usuarios SET foto_url = ? WHERE id = ?", url, u.id());
        if (u.fotoUrl() != null && u.fotoUrl().startsWith("/api/usuarios/")) {
            archivos.borrar(u.fotoUrl().substring(u.fotoUrl().lastIndexOf('/') + 1));
        }
        return Map.of("foto", url);
    }

    /** Fotos de perfil: cualquier usuario con sesión (el cliente ve la del conductor y al revés). */
    @GetMapping("/{id}/foto/{nombre:.+}")
    public ResponseEntity<Resource> verFoto(@PathVariable Long id, @PathVariable String nombre) {
        String url = db.queryForList("SELECT foto_url FROM usuarios WHERE id = ?", String.class, id).stream()
                .findFirst().orElse(null);
        if (url == null || !url.endsWith("/" + nombre)) throw new RecursoNoEncontradoException("Foto no encontrada");
        ArchivoServicio.Archivo a = archivos.obtener(nombre);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(a.contentType()))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(7)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff").body(a.recurso());
    }

    // ───────────── Verificar o cambiar el celular (web y app) ─────────────

    @PostMapping("/telefono/codigo")
    public Map<String, Object> codigoTelefono(@Valid @RequestBody CodigoTelefonoRequest req, HttpServletRequest http) {
        Usuario u = actual();
        String tel = Celular.exigir(req.celular());
        if (!tel.equals(u.telefono()) && usuarios.existeTelefono(tel)) {
            throw new ReglaNegocioException("Ya existe una cuenta con ese celular.");
        }
        CodigoVerificacionServicio.Envio e = codigos.enviar(tel, CodigoVerificacionServicio.CAMBIO_TELEFONO,
                ContextoPeticion.de(http).ip());
        Map<String, Object> r = new HashMap<>();
        r.put("message", "Te enviamos un código al " + Celular.visible(tel));
        r.put("vigenciaSeg", e.vigenciaSeg());
        if (e.codigoDesarrollo() != null) r.put("codigoDesarrollo", e.codigoDesarrollo());
        return r;
    }

    /** Después de /api/auth/codigo/verificar con proposito "cambio_telefono". */
    @PostMapping("/telefono/verificar")
    public Map<String, Object> verificarTelefono(@Valid @RequestBody VerificarTelefonoRequest req) {
        Usuario u = actual();
        String tel = jwt.leerVerificacion(req.verificacionToken(), CodigoVerificacionServicio.CAMBIO_TELEFONO);
        db.update("UPDATE usuarios SET telefono = ?, telefono_verificado = true WHERE id = ?", tel, u.id());
        return Map.of("message", "Celular verificado", "telefono", tel);
    }

    // ───────────── Direcciones favoritas ─────────────

    @GetMapping("/direcciones")
    public List<Map<String, Object>> direcciones() {
        return db.queryForList("""
                SELECT id, etiqueta, direccion, barrio, referencia, lat, lng
                FROM direcciones_favoritas WHERE usuario_id = ? ORDER BY creado_en
                """, sesion.usuarioId());
    }

    @PostMapping("/direcciones")
    public Map<String, Object> guardarDireccion(@Valid @RequestBody DireccionRequest d) {
        Long id = db.queryForObject("""
                INSERT INTO direcciones_favoritas (usuario_id, etiqueta, direccion, barrio, referencia, lat, lng)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (usuario_id, etiqueta) DO UPDATE
                   SET direccion = EXCLUDED.direccion, barrio = EXCLUDED.barrio, referencia = EXCLUDED.referencia,
                       lat = EXCLUDED.lat, lng = EXCLUDED.lng
                RETURNING id
                """, Long.class, sesion.usuarioId(), d.etiqueta().trim(), d.direccion().trim(), d.barrio(),
                d.referencia(), d.lat(), d.lng());
        return Map.of("id", id, "message", "Dirección guardada");
    }

    @DeleteMapping("/direcciones/{id}")
    public Map<String, Object> borrarDireccion(@PathVariable Long id) {
        int n = db.update("DELETE FROM direcciones_favoritas WHERE id = ? AND usuario_id = ?", id, sesion.usuarioId());
        if (n == 0) throw new RecursoNoEncontradoException("Dirección no encontrada");
        return Map.of("message", "Dirección eliminada");
    }

    // ───────────── Contactos de emergencia (SOS) ─────────────

    @GetMapping("/contactos-emergencia")
    public List<Map<String, Object>> contactos() {
        return db.queryForList("SELECT id, nombre, telefono, parentesco FROM contactos_emergencia WHERE usuario_id = ?",
                sesion.usuarioId());
    }

    @PostMapping("/contactos-emergencia")
    public Map<String, Object> agregarContacto(@Valid @RequestBody ContactoRequest c) {
        String tel = Celular.normalizarContacto(c.telefono());
        if (tel == null) throw new ReglaNegocioException(400, "Escribe un celular o fijo colombiano");
        Long id = db.queryForObject("""
                INSERT INTO contactos_emergencia (usuario_id, nombre, telefono, parentesco) VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, sesion.usuarioId(), c.nombre().trim(), tel, c.parentesco());
        return Map.of("id", id, "message", "Contacto agregado");
    }

    @DeleteMapping("/contactos-emergencia/{id}")
    public Map<String, Object> borrarContacto(@PathVariable Long id) {
        int n = db.update("DELETE FROM contactos_emergencia WHERE id = ? AND usuario_id = ?", id, sesion.usuarioId());
        if (n == 0) throw new RecursoNoEncontradoException("Contacto no encontrado");
        return Map.of("message", "Contacto eliminado");
    }

    // ───────────── Dispositivo (notificaciones push) ─────────────

    @PostMapping("/dispositivos")
    public Map<String, Object> registrarDispositivo(@Valid @RequestBody DispositivoRequest d) {
        Long id = db.queryForObject("""
                INSERT INTO dispositivos (usuario_id, plataforma, token_push, modelo, version_app)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (token_push) DO UPDATE
                   SET usuario_id = EXCLUDED.usuario_id, plataforma = EXCLUDED.plataforma, modelo = EXCLUDED.modelo,
                       version_app = EXCLUDED.version_app, activo = true, ultima_conexion = now()
                RETURNING id
                """, Long.class, sesion.usuarioId(), d.plataforma(), d.tokenPush(), d.modelo(), d.versionApp());
        return Map.of("id", id);
    }

    // ───────────── Eliminar la cuenta (Ley 1581 y Google Play) ─────────────

    @DeleteMapping("/cuenta")
    @PreAuthorize("hasAuthority('perfil.eliminar_cuenta')")
    @Transactional
    public Map<String, Object> eliminarCuenta() {
        Long id = sesion.usuarioId();
        actor.declarar(id, ActorBd.SISTEMA);
        Usuario u = actual();
        db.queryForList("SELECT fn_eliminar_cuenta(?)", id);
        if (u.fotoUrl() != null && u.fotoUrl().startsWith("/api/usuarios/")) {
            archivos.borrar(u.fotoUrl().substring(u.fotoUrl().lastIndexOf('/') + 1));
        }
        return Map.of("message", "Tu cuenta fue eliminada");
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
