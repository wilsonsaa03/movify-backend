package com.movify.backend.comun;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * SMS y notificaciones push se mandan solo cuando el cambio ya quedó guardado: si la transacción se deshace,
 * nadie recibe un aviso de algo que no pasó.
 */
public final class DespuesDelCommit {

    private DespuesDelCommit() {
    }

    /** Corre la acción después del commit (o enseguida si no hay transacción, p. ej. en pruebas). */
    public static void ejecutar(Runnable accion) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    accion.run();
                }
            });
        } else {
            accion.run();
        }
    }
}
