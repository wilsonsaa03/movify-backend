package com.movify.backend.Controlador;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/test")
public class TestControlador {

    @GetMapping("/ping")
    public String ping() {
        return "pong - Backend is working successfully!";
    }
}
