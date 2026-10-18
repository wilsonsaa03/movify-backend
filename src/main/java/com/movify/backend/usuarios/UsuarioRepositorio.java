package com.movify.backend.usuarios;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.movify.backend.comun.Roles;

/** Acceso a usuarios, usuario_roles y permisos efectivos con JdbcTemplate (sin ORM). */
@Repository
public class UsuarioRepositorio {

    private static final String COLUMNAS = """
            id, nombres, apellidos, telefono, telefono_verificado, email, email_verificado, google_sub,
            password_hash, foto_url, estado, motivo_bloqueo, calificacion_promedio, total_calificaciones, creado_en
            """;

    private static final RowMapper<Usuario> MAPEO = (rs, i) -> new Usuario(
            rs.getLong("id"), rs.getString("nombres"), rs.getString("apellidos"), rs.getString("telefono"),
            rs.getBoolean("telefono_verificado"), rs.getString("email"), rs.getBoolean("email_verificado"),
            rs.getString("google_sub"), rs.getString("password_hash"), rs.getString("foto_url"),
            rs.getString("estado"), rs.getString("motivo_bloqueo"), rs.getBigDecimal("calificacion_promedio"),
            rs.getInt("total_calificaciones"), rs.getObject("creado_en", OffsetDateTime.class));

    private final JdbcTemplate db;

    public UsuarioRepositorio(JdbcTemplate db) {
        this.db = db;
    }

    public Optional<Usuario> porId(Long id) {
        return uno("SELECT " + COLUMNAS + " FROM usuarios WHERE id = ?", id);
    }

    public Optional<Usuario> porTelefono(String telefonoE164) {
        return uno("SELECT " + COLUMNAS + " FROM usuarios WHERE telefono = ?", telefonoE164);
    }

    public Optional<Usuario> porEmail(String email) {
        return uno("SELECT " + COLUMNAS + " FROM usuarios WHERE lower(email) = lower(?)", email.trim());
    }

    public Optional<Usuario> porGoogleSub(String sub) {
        return uno("SELECT " + COLUMNAS + " FROM usuarios WHERE google_sub = ?", sub);
    }

    public boolean existeTelefono(String telefonoE164) {
        Boolean b = db.queryForObject("SELECT EXISTS (SELECT 1 FROM usuarios WHERE telefono = ?)", Boolean.class,
                telefonoE164);
        return Boolean.TRUE.equals(b);
    }

    public boolean existeEmail(String email) {
        Boolean b = db.queryForObject("SELECT EXISTS (SELECT 1 FROM usuarios WHERE lower(email) = lower(?))",
                Boolean.class, email.trim());
        return Boolean.TRUE.equals(b);
    }

    /** Crea el usuario (estado inicial "activo", lo exige el trigger del catálogo de estados). */
    public Long crear(String nombres, String apellidos, String telefono, boolean telefonoVerificado, String email,
            boolean emailVerificado, String googleSub, String passwordHash, String fotoUrl) {
        return crear(nombres, apellidos, telefono, telefonoVerificado, email, emailVerificado, googleSub, passwordHash,
                fotoUrl, Roles.CLIENTE);
    }

    /** @param registradoComo "cliente" o "conductor" (lo que eligió en la app). */
    public Long crear(String nombres, String apellidos, String telefono, boolean telefonoVerificado, String email,
            boolean emailVerificado, String googleSub, String passwordHash, String fotoUrl, String registradoComo) {
        return db.queryForObject("""
                INSERT INTO usuarios (nombres, apellidos, telefono, telefono_verificado, email, email_verificado,
                                      google_sub, password_hash, foto_url, registrado_como)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, nombres, apellidos, telefono, telefonoVerificado, email, emailVerificado,
                googleSub, passwordHash, fotoUrl, registradoComo);
    }

    /**
     * Aspirante a conductor: se registró como conductor o ya empezó su perfil. Mientras el admin no
     * lo apruebe no tiene el rol "conductor" (ni ningún otro si se registró como conductor).
     */
    public boolean esAspiranteConductor(Long usuarioId) {
        Boolean b = db.queryForObject("""
                SELECT u.registrado_como = 'conductor' OR EXISTS (SELECT 1 FROM conductores c WHERE c.usuario_id = u.id)
                FROM usuarios u WHERE u.id = ?
                """, Boolean.class, usuarioId);
        return Boolean.TRUE.equals(b);
    }

    public void asignarRol(Long usuarioId, String rol) {
        db.update("INSERT INTO usuario_roles (usuario_id, rol) VALUES (?, ?) ON CONFLICT DO NOTHING", usuarioId, rol);
    }

    public Set<String> roles(Long usuarioId) {
        return new LinkedHashSet<>(db.queryForList("""
                SELECT ur.rol FROM usuario_roles ur JOIN roles r ON r.codigo = ur.rol AND r.activo
                WHERE ur.usuario_id = ? ORDER BY ur.rol
                """, String.class, usuarioId));
    }

    /** Permisos efectivos (roles − revocados + concedidos), los que van en el JWT. */
    public Set<String> permisos(Long usuarioId) {
        return new LinkedHashSet<>(db.queryForList("SELECT permiso FROM fn_permisos_usuario(?)", String.class, usuarioId));
    }

    /** 'exitoso' o el motivo del rechazo (cuenta_bloqueada, acceso_bloqueado_temporal...). */
    public String validarAcceso(Long usuarioId) {
        return db.queryForObject("SELECT fn_validar_acceso(?)", String.class, usuarioId);
    }

    public void actualizarPassword(Long usuarioId, String hash) {
        db.update("UPDATE usuarios SET password_hash = ? WHERE id = ?", hash, usuarioId);
    }

    public void vincularGoogle(Long usuarioId, String sub, String fotoUrl) {
        db.update("""
                UPDATE usuarios SET google_sub = ?, email_verificado = true,
                       foto_url = COALESCE(foto_url, NULLIF(?, ''))
                WHERE id = ?
                """, sub, fotoUrl, usuarioId);
    }

    public List<Usuario> todos() {
        return db.query("SELECT " + COLUMNAS + " FROM usuarios WHERE estado <> 'eliminado' ORDER BY id DESC", MAPEO);
    }

    private Optional<Usuario> uno(String sql, Object... args) {
        return db.query(sql, MAPEO, args).stream().findFirst();
    }
}
