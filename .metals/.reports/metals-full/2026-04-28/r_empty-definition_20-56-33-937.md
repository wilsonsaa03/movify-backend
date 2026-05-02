error id: file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Modelo/Conductor.java:_empty_/OneToOne#
file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Modelo/Conductor.java
empty definition using pc, found symbol in pc: _empty_/OneToOne#
empty definition using semanticdb
empty definition using fallback
non-local guesses:

offset: 286
uri: file:///C:/Users/USUARIO/Desktop/Estudios/univalle/2026-1/Desarrollo%20II/Movify/backend/movify-backend/movify-backend/src/main/java/com/movify/backend/Modelo/Conductor.java
text:
```scala
package com.movify.backend.Modelo;

@Entity @Table(name = "conductores")
@Data @NoArgsConstructor @AllArgsConstructor
public class Conductor {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
// FK → usuarios.id (relación 1:1 del diagrama)
@OneTo@@One @JoinColumn(name = "usuario_id")
private Usuario usuario;
private String licencia;
private String soat;
// pendiente | aprobado | rechazado
private String estado = "pendiente";
@Column(name = "fecha_verificacion")
private LocalDateTime fechaVerificacion;
}

```


#### Short summary: 

empty definition using pc, found symbol in pc: _empty_/OneToOne#