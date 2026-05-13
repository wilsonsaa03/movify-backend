package com.movify.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Eliminamos el 'exclude' para que Spring busque la configuración de PostgreSQL
@SpringBootApplication
public class MovifyBackendApplication {

    public static void main(String[] args) {
        // Esta línea es la que realmente "enciende" el motor de Spring Boot
        SpringApplication.run(MovifyBackendApplication.class, args);
    }

}