package com.movify.backend.Base_de_datos;

import com.movify.backend.Modelo.TokenRecuperacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface TokenRecuperacionRepositorio extends JpaRepository<TokenRecuperacion, Long> {
    Optional<TokenRecuperacion> findByToken(String token);
}