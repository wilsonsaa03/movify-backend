package com.movify.backend.Base_de_datos;

import com.movify.backend.Modelo.Conductor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ConductorRepositorio
        extends JpaRepository<Conductor, Long> {

    Optional<Conductor> findByUsuarioId(
            Long usuarioId);

}