package com.movify.backend.Dto;

import lombok.Data;

@Data
public class LoginRequest {
    private String correo;
    private String password;
}