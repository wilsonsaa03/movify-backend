error id: file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Controlador/AutenticacionControlador.java:_empty_/`<any>`#getContrasena#equals#
file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Controlador/AutenticacionControlador.java
empty definition using pc, found symbol in pc: _empty_/`<any>`#getContrasena#equals#
empty definition using semanticdb
empty definition using fallback
non-local guesses:

offset: 1143
uri: file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Controlador/AutenticacionControlador.java
text:
```scala
package com.movify.backend.Controlador;

//import com.movify.backend.Modelo.Usuario;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.movify.backend.Base_de_datos.UsuarioRepositorio;

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
            .filter(u -> u.getContrasena().equals@@(password))
            .map(u -> ResponseEntity.ok(u))
            .orElse(ResponseEntity.status(401).build());
    }
}
```


#### Short summary: 

empty definition using pc, found symbol in pc: _empty_/`<any>`#getContrasena#equals#