error id: file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Base_de_datos/SesionRepositorio.java:java/util/Optional#
file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Base_de_datos/SesionRepositorio.java
empty definition using pc, found symbol in pc: java/util/Optional#
empty definition using semanticdb
empty definition using fallback
non-local guesses:

offset: 219
uri: file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Base_de_datos/SesionRepositorio.java
text:
```scala
package com.movify.backend.Base_de_datos;

import com.movify.backend.Modelo.Sesion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.O@@ptional;

@Repository
public interface SesionRepositorio extends JpaRepository<Sesion, Long> {
    Optional<Sesion> findByToken(String token);
}
```


#### Short summary: 

empty definition using pc, found symbol in pc: java/util/Optional#