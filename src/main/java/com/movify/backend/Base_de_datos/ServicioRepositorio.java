package com.movify.backend.Base_de_datos;

import com.movify.backend.Modelo.Servicio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ServicioRepositorio extends JpaRepository<Servicio, Long> {
    List<Servicio> findByUsuarioId(Long usuarioId);
    List<Servicio> findByConductorId(Long conductorId);
    List<Servicio> findByEstado(String estado);
}