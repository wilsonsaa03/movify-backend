package com.movify.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * MoviFY · API de movilidad y entregas (Buenaventura).
 *
 * Paquetes (uno por área del DER v2.3):
 *   autenticacion  → registro, códigos SMS, login, sesiones
 *   usuarios       → perfil, foto, direcciones favoritas
 *   conductores    → perfil del conductor, vehículos, documentos, disponibilidad
 *   servicios      → transporte, domicilios, encomiendas, tarifas, calificaciones
 *   administracion → panel de admin y soporte
 *   catalogos      → tipos, estados, motivos y documentos legales
 *   seguridad / configuracion / comun → piezas compartidas
 */
@SpringBootApplication
@EnableScheduling
public class MovifyBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(MovifyBackendApplication.class, args);
    }
}
