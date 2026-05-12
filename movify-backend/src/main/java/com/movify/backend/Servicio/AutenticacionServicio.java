package com.movify.backend.Servicio;
 
import com.movify.backend.Base_de_datos.UsuarioRepositorio;
import com.movify.backend.Modelo.Usuario;
import com.movify.backend.Seguridad.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
 
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
 
@Service
public class AutenticacionServicio {
 
    @Autowired private UsuarioRepositorio usuarioRepositorio;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private EmailServicio emailServicio;
 
    // =========================
    // LOGIN NORMAL
    // =========================
 
    public Map<String, Object> login(String correo, String password) {
 
        Usuario usuario = usuarioRepositorio.findByCorreo(correo)
            .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
 
        if (!passwordEncoder.matches(password, usuario.getPassword()))
            throw new RuntimeException("Contraseña incorrecta");
 
        if (!usuario.getEstado().equals("activo"))
            throw new RuntimeException("Usuario inactivo");
 
        // Enviar correo de bienvenida
        emailServicio.enviarCorreoBienvenida(
            usuario.getCorreo(),
            usuario.getNombre()
        );
 
        return buildRespuesta(usuario, "Login exitoso");
    }
 
    // =========================
    // REGISTRO
    // =========================
 
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
 
        return buildRespuesta(nuevo, "Registro exitoso");
    }
 
    // =========================
    // LOGIN GOOGLE
    // =========================
 
    public Map<String, Object> loginGoogle(Map<String, String> datos) {
 
        Usuario usuario = usuarioRepositorio.findByCorreo(datos.get("correo"))
            .orElseGet(() -> {
                Usuario nuevo = new Usuario();
                nuevo.setNombre(datos.get("nombre"));
                nuevo.setCorreo(datos.get("correo"));
                nuevo.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
                nuevo.setRol("cliente");
                nuevo.setEstado("activo");
                return usuarioRepositorio.save(nuevo);
            });
 
        // Enviar correo de bienvenida
        emailServicio.enviarCorreoBienvenida(
            usuario.getCorreo(),
            usuario.getNombre()
        );
 
        return buildRespuesta(usuario, "Login con Google exitoso");
    }
 
    // =========================
    // LOGIN FACEBOOK
    // =========================
 
    public Map<String, Object> loginFacebook(Map<String, String> datos) {
 
        String correo = datos.get("correo");
        if (correo == null || correo.isEmpty())
            correo = datos.get("facebookId") + "@facebook.movify";
 
        final String correoFinal = correo;
 
        Usuario usuario = usuarioRepositorio.findByCorreo(correoFinal)
            .orElseGet(() -> {
                Usuario nuevo = new Usuario();
                nuevo.setNombre(datos.get("nombre"));
                nuevo.setCorreo(correoFinal);
                nuevo.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
                nuevo.setRol("cliente");
                nuevo.setEstado("activo");
                return usuarioRepositorio.save(nuevo);
            });
 
        // Enviar correo de bienvenida
        emailServicio.enviarCorreoBienvenida(
            usuario.getCorreo(),
            usuario.getNombre()
        );
 
        return buildRespuesta(usuario, "Login con Facebook exitoso");
    }
 
    // =========================
    // HELPER
    // =========================
 
    private Map<String, Object> buildRespuesta(Usuario usuario, String mensaje) {
        String token = jwtUtil.generarToken(usuario.getCorreo(), usuario.getRol());
        Map<String, Object> respuesta = new HashMap<>();
        respuesta.put("token",   token);
        respuesta.put("correo",  usuario.getCorreo());
        respuesta.put("rol",     usuario.getRol());
        respuesta.put("nombre",  usuario.getNombre());
        respuesta.put("mensaje", mensaje);
        return respuesta;
    }
}
 