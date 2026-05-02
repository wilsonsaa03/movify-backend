error id: file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Controlador/AutenticacionControlador.java:com/movify/backend/ase_de_datos/UsuarioRepositorio#
file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Controlador/AutenticacionControlador.java
empty definition using pc, found symbol in pc: com/movify/backend/ase_de_datos/UsuarioRepositorio#
empty definition using semanticdb
empty definition using fallback
non-local guesses:

offset: 143
uri: file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Controlador/AutenticacionControlador.java
text:
```scala
package com.movify.backend.Controlador;

import com.movify.backend.Modelo.Usuario;
import com.movify.backend.ase_de_datos.UsuarioRepositorio@@;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "http://localhost:4200")
public class AutenticacionControlador {

    @Autowired
    private UsuarioRepositorio usuarioRepositorio;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credenciales) {
        String correo = credenciales.get("correo");
        String password = credenciales.get("contrasena");

        return usuarioRepositorio.findByCorreo(correo)
            .filter(u -> u.getContrasena().equals(password))
            .map(u -> ResponseEntity.ok(u))
            .orElse(ResponseEntity.status(401).build());
    }
}
```


#### Short summary: 

empty definition using pc, found symbol in pc: com/movify/backend/ase_de_datos/UsuarioRepositorio#