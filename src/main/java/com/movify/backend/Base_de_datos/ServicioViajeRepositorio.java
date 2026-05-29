package com.movify.backend.Base_de_datos;

import com.movify.backend.Modelo.ServicioViaje;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface ServicioViajeRepositorio
    extends JpaRepository<ServicioViaje, String> {

    Optional<ServicioViaje> findById(String id);
}