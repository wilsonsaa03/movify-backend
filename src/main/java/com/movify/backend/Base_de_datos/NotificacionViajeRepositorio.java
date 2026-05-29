package com.movify.backend.Base_de_datos;

import com.movify.backend.Modelo.NotificacionViaje;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface NotificacionViajeRepositorio
    extends JpaRepository<NotificacionViaje, String> {

    // Buscar notificaciones pendientes de un conductor
    List<NotificacionViaje> findByConductorIdAndEstado(
        String conductorId,
        String estado
    );
}