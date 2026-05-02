package com.movify.backend.Servicio;

import com.movify.backend.Base_de_datos.UsuarioRepositorio;
import com.movify.backend.Modelo.Usuario;
import com.movify.backend.Seguridad.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.Map;

@Service
public class AutenticacionServicio {

    @Autowired
    private UsuarioRepositorio usuarioRepositorio;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    public Map<String, Object> login(String correo, String password) {
        Usuario usuario = usuarioRepositorio.findByCorreo(correo)
            .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        if (!passwordEncoder.matches(password, usuario.getPassword()))
            throw new RuntimeException("Contraseña incorrecta");

        if (!usuario.getEstado().equals("activo"))
            throw new RuntimeException("Usuario inactivo");

        String token = jwtUtil.generarToken(usuario.getCorreo(), usuario.getRol());

        Map<String, Object> respuesta = new HashMap<>();
        respuesta.put("token", token);
        respuesta.put("correo", usuario.getCorreo());
        respuesta.put("rol", usuario.getRol());
        respuesta.put("nombre", usuario.getNombre());
        respuesta.put("mensaje", "Login exitoso");
        return respuesta;
    }

    public Map<String, Object> registro(Map<String, String> datos) {
        if (usuarioRepositorio.existsByCorreo(datos.get("correo")))
            throw new RuntimeException("El correo ya está registrado");

        Usuario nuevo = new Usuario();
        nuevo.setNombre(datos.get("nombre"));
        nuevo.setCorreo(datos.get("correo"));
        nuevo.setPassword(passwordEncoder.encode(datos.get("password")));
        nuevo.setRol(datos.get("rol"));
        nuevo.setTelefono(datos.get("telefono"));
        nuevo.setEstado("activo");

        usuarioRepositorio.save(nuevo);

        String token = jwtUtil.generarToken(nuevo.getCorreo(), nuevo.getRol());

        Map<String, Object> respuesta = new HashMap<>();
        respuesta.put("token", token);
        respuesta.put("correo", nuevo.getCorreo());
        respuesta.put("rol", nuevo.getRol());
        respuesta.put("nombre", nuevo.getNombre());
        respuesta.put("mensaje", "Registro exitoso");
        return respuesta;
    }
}