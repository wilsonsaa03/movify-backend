// Paquete donde se encuentra este repositorio.
// Generalmente se usa para organizar las clases relacionadas con la base de datos.
package com.movify.backend.Base_de_datos;

// Importa la entidad Calificacion.
// Esta entidad representa la tabla "calificacion" en la base de datos.
import com.movify.backend.Modelo.Calificacion;

// Importa JpaRepository de Spring Data JPA.
// JpaRepository proporciona métodos ya creados para trabajar con la base de datos:
// guardar, eliminar, buscar, actualizar, etc.
import org.springframework.data.jpa.repository.JpaRepository;

// Indica que esta interfaz es un componente de acceso a datos (Repositorio).
// Spring la detecta automáticamente y la administra como un Bean.
import org.springframework.stereotype.Repository;

// Importa la estructura List de Java.
// Se usa porque el método devolverá varias calificaciones.
import java.util.List;

// @Repository marca esta interfaz como repositorio de base de datos.
@Repository

// Se crea la interfaz CalificacionRepositorio.
// Hereda de JpaRepository para obtener métodos CRUD automáticamente.
// <Calificacion, Long>
// Calificacion -> entidad que manejará este repositorio.
// Long -> tipo de dato de la clave primaria (id).
public interface CalificacionRepositorio extends JpaRepository<Calificacion, Long> {

    // Método personalizado de Spring Data JPA.
    // Busca todas las calificaciones relacionadas con un servicio específico.
    // findBy -> Spring entiende que se hará una búsqueda.
    // ServicioId -> busca usando el atributo servicio.id.
    // Ejemplo:
    // Si servicioId = 5
    // devolverá todas las calificaciones cuyo servicio tenga id 5.
    //
    // Retorna una lista de objetos Calificacion.
    List<Calificacion> findByServicioId(Long servicioId);
}