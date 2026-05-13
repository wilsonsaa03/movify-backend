package com.movify.backend.Base_de_datos;

import com.movify.backend.Modelo.Notificacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface NotificacionRepositorio extends JpaRepository<Notificacion, Long> {
    List<Notificacion> findByUsuarioId(Long usuarioId);
    List<Notificacion> findByUsuarioIdAndLeida(Long usuarioId, Boolean leida);
}