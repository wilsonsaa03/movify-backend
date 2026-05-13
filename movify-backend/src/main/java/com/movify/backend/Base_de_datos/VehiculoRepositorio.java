package com.movify.backend.Base_de_datos;

import com.movify.backend.Modelo.Vehiculo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface VehiculoRepositorio extends JpaRepository<Vehiculo, Long> {
    List<Vehiculo> findByConductorId(Long conductorId);
}