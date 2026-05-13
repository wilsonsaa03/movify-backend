package com.movify.backend.Base_de_datos;

import com.movify.backend.Modelo.Sesion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface SesionRepositorio extends JpaRepository<Sesion, Long> {
    Optional<Sesion> findByToken(String token);
}