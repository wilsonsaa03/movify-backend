package com.movify.backend.Base_de_datos;

import com.movify.backend.Modelo.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/**
 * Repositorio para la entidad Usuario.
 * Proporciona métodos para acceder a los datos de los usuarios en la base de
 * datos.
 */
@Repository
public interface UsuarioRepositorio extends JpaRepository<Usuario, Long> {

    // Busca un usuario por su correo electrónico (útil para el Login)
    Optional<Usuario> findByCorreo(String correo);

    // Verifica si ya existe un usuario con ese correo (útil para el Registro)
    boolean existsByCorreo(String correo);

    // Busca un usuario por su token de recuperación (útil para Olvidé mi
    // contraseña)
    Optional<Usuario> findByTokenRecuperacion(String token);

    // Opcional: Busca por nombre de usuario si decides usarlo en el futuro
    // Optional<Usuario> findByNombreUsuario(String nombreUsuario);
}