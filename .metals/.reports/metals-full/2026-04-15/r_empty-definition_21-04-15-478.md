error id: file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Modelo/Usuario.java:_empty_/GenerationType#
file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Modelo/Usuario.java
empty definition using pc, found symbol in pc: _empty_/GenerationType#
empty definition using semanticdb
empty definition using fallback
non-local guesses:

offset: 835
uri: file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Modelo/Usuario.java
text:
```scala
package com.movify.backend.Modelo; // Define donde esta guardado el archivo

import jakarta.persistence.Column; // Importa las herramientas para conectar con la base de datos (JPA)
import jakarta.persistence.Entity; // Importa Lombok para no escribir codigo repetitivo
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity // Le dice a Spring: "Esta clase es una tabla de base de datos"
@Table(name = "usuarios") // Especifica que la tabla en PostgreSQL se llamará "usuarios"
@Data // Magia de Lombok: Crea automáticamente los Getters, Setters y el ToString
public class Usuario {

    @Id // Define que este campo es la "Llave Primaria" (única para cada fila)
    @GeneratedValue(strategy = G@@enerationType.IDENTITY) // Hace que el ID sea autoincremental (1, 2, 3...)
    private Long id;

    // 'unique=true' asegura que no haya dos personas con el mismo correo
    // 'nullable=false' obliga a que este campo nunca esté vacío
    @Column(unique = true, nullable = false)
    private String correo;

    @Column(nullable = false) // La contrasena es obligatoria
    private String contrasena;

    // Aquí guardaremos si es 'CLIENTE', 'CONDUCTOR' o 'ADMIN'
    // Esto te servirá para saber qué pantallas mostrar en Angular después
    private String rol; 
}
```


#### Short summary: 

empty definition using pc, found symbol in pc: _empty_/GenerationType#