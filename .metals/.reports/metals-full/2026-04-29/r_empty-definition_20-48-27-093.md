error id: file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Encomienda.java:_empty_/GenerationType#IDENTITY#
file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Encomienda.java
empty definition using pc, found symbol in pc: _empty_/GenerationType#IDENTITY#
empty definition using semanticdb
empty definition using fallback
non-local guesses:

offset: 263
uri: file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Encomienda.java
text:
```scala
package com.movify.backend.Modelo;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "encomiendas")
@Data @NoArgsConstructor @AllArgsConstructor
public class Encomienda {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENT@@ITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "servicio_id", nullable = false)
    private Servicio servicio;

    private String tipo;
    private Double peso;
    private String destinatario;

    @Column(name = "telefono_destinatario")
    private String telefonoDestinatario;
}
```


#### Short summary: 

empty definition using pc, found symbol in pc: _empty_/GenerationType#IDENTITY#