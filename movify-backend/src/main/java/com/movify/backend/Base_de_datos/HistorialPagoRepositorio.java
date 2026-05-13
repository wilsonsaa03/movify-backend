package com.movify.backend.Base_de_datos;

import com.movify.backend.Modelo.HistorialPago;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface HistorialPagoRepositorio extends JpaRepository<HistorialPago, Long> {
    List<HistorialPago> findByServicioId(Long servicioId);
}