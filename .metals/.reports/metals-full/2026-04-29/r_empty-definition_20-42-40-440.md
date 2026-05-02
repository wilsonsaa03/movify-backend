error id: file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Modelo/Vehiculo.java:
file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Modelo/Vehiculo.java
empty definition using pc, found symbol in pc: 
empty definition using semanticdb
empty definition using fallback
non-local guesses:

offset: 188
uri: file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Modelo/Vehiculo.java
text:
```scala
package com.movify.backend.model;
 
package com.movify.backend.Modelo;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "vehiculos")
@Data @NoArgsConstructor@@ @AllArgsConstructor
public class Vehiculo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Relación 1:N con conductores
    @ManyToOne
    @JoinColumn(name = "conductor_id", nullable = false)
    private Conductor conductor;

    @Column(unique = true, nullable = false)
    private String placa;

    private String marca;
    private String modelo;

    // activo | inactivo
    private String estado = "activo";
}
```


#### Short summary: 

empty definition using pc, found symbol in pc: 