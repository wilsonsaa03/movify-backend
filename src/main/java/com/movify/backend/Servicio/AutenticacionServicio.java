package com.movify.backend.Servicio;

import com.movify.backend.Base_de_datos.UsuarioRepositorio;
import com.movify.backend.Modelo.Usuario;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AutenticacionServicio {

    @Autowired
    private UsuarioRepositorio usuarioRepositorio;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${app.admin.secret-code:MOVI-ADMIN-2024-SECRET}")
    private String codigoAdminSecreto;

    /**
     * Verifica si una contraseña coincide con el hash guardado.
     */
    public boolean validarPassword(String passwordPlana, String passwordHash) {
        return passwordEncoder.matches(passwordPlana, passwordHash);
    }

    /**
     * Valida el código secreto y registra al administrador.
     */
    public Usuario registrarAdmin(Map<String, String> datos) throws Exception {
        String codigoIngresado = datos.get("codigoAdmin");

        if (!codigoAdminSecreto.equals(codigoIngresado)) {
            throw new Exception("El código de administrador no es válido. Acceso denegado.");
        }

        if (usuarioRepositorio.existsByCorreo(datos.get("correo"))) {
            throw new Exception("El correo electrónico ya se encuentra registrado.");
        }

        Usuario nuevoAdmin = new Usuario();
        nuevoAdmin.setNombre(datos.get("nombre"));
        nuevoAdmin.setCorreo(datos.get("correo"));
        nuevoAdmin.setPassword(passwordEncoder.encode(datos.get("password")));
        nuevoAdmin.setRol("admin");
        nuevoAdmin.setEstado("activo");

        return usuarioRepositorio.save(nuevoAdmin);
    }
}