package com.movify.backend.Base_de_datos;

import com.movify.backend.Modelo.Viaje;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ViajeRepositorio
        extends JpaRepository<Viaje, Long> {

    List<Viaje> findByConductorId(Long conductorId);

}