package com.movify.backend.Base_de_datos;

import com.movify.backend.Modelo.Calificacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface CalificacionRepositorio extends JpaRepository<Calificacion, Long> {
    List<Calificacion> findByServicioId(Long servicioId);
}