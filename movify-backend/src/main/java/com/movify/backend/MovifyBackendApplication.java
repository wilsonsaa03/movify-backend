package com.movify.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = "com.movify.backend")
public class MovifyBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(MovifyBackendApplication.class, args);
    }
}