error id: file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Base_de_datos/CalificacionRepositorio.java:_empty_/Repository#
file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Base_de_datos/CalificacionRepositorio.java
empty definition using pc, found symbol in pc: _empty_/Repository#
empty definition using semanticdb
empty definition using fallback
non-local guesses:

offset: 234
uri: file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Base_de_datos/CalificacionRepositorio.java
text:
```scala
package com.movify.backend.Base_de_datos;

import com.movify.backend.Modelo.Calificacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@@@Repository
public interface CalificacionRepositorio extends JpaRepository<Calificacion, Long> {
    List<Calificacion> findByServicioId(Long servicioId);
}
```


#### Short summary: 

empty definition using pc, found symbol in pc: _empty_/Repository#