error id: file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Modelo/Usuario.java:java/lang/Long#
file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Modelo/Usuario.java
empty definition using pc, found symbol in pc: java/lang/Long#
empty definition using semanticdb
empty definition using fallback
non-local guesses:

offset: 259
uri: file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Modelo/Usuario.java
text:
```scala
package com.movify.backend.Modelo;

import jakarta.persistence.*;

@Entity
@Table(name = "usuarios")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private @@Long id;

    private String nombre;

    @Column(unique = true, nullable = false)
    private String correo;

    private String password;
    private String telefono;

    // cliente | conductor | admin
    private String rol;

    // activo | inactivo
    private String estado = "activo";

    @Column(name = "fecha_registro")
    private LocalDateTime fechaRegistro = LocalDateTime.now();
}
```


#### Short summary: 

empty definition using pc, found symbol in pc: java/lang/Long#