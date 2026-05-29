package com.movify.backend.Base_de_datos;

import com.movify.backend.Modelo.ConductorActivo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConductorActivoRepositorio
    extends JpaRepository<ConductorActivo, String> {
}