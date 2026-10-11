package com.movify.backend.Servicio;

import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.movify.backend.Comun.ReglaNegocioException;

/**
 * Saldo y retiros del conductor.
 * saldo = lo ganado en servicios FINALIZADOS − lo que ya pidió retirar (tabla retiros_conductor).
 */
@Service
public class GananciasServicio {

    private final JdbcTemplate db;

    public GananciasServicio(JdbcTemplate db) {
        this.db = db;
    }

    public double saldoDisponible(Long conductorId) {
        Double saldo = db.queryForObject("""
                SELECT COALESCE((SELECT SUM(tarifa) FROM servicios WHERE conductor_id = ? AND estado = 'FINALIZADO'), 0)
                     - COALESCE((SELECT SUM(monto) FROM retiros_conductor WHERE conductor_id = ? AND estado <> 'rechazado'), 0)
                """, Double.class, conductorId, conductorId);
        return saldo == null ? 0 : Math.max(0, saldo);
    }

    @Transactional
    public Map<String, Object> solicitarRetiro(Long conductorId, double monto) {
        // Bloquea la fila del conductor: dos retiros simultáneos no pueden gastar el mismo saldo
        db.queryForList("SELECT id FROM conductores WHERE id = ? FOR UPDATE", conductorId);
        double saldo = saldoDisponible(conductorId);
        if (monto > saldo) {
            throw new ReglaNegocioException("El monto supera tu saldo disponible ($" + Math.round(saldo) + ")");
        }
        db.update("""
                INSERT INTO retiros_conductor (conductor_id, monto, estado, fecha)
                VALUES (?, ?, 'pendiente', NOW())
                """, conductorId, monto);
        return Map.of("message", "Retiro solicitado. Se procesará en 1-2 días hábiles.",
                "saldo_restante", saldo - monto);
    }
}
