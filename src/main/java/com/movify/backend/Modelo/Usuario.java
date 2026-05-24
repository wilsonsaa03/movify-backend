package com.movify.backend.Modelo;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Entity
@Table(name = "usuarios")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder // Permite crear objetos de forma fluida: Usuario.builder().nombre("...").build()
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "El nombre es obligatorio")
    private String nombre;

    @Email(message = "Debe proporcionar un correo válido")
    @Column(unique = true, nullable = false)
    private String correo;

    @NotBlank(message = "La contraseña no puede estar vacía")
    private String password;

    private String telefono;
    
    // Cambiamos a columnDefinition para manejar URLs largas de fotos de perfil
    @Column(columnDefinition = "TEXT")
    private String foto;

    // Usar valores constantes para evitar errores de escritura
    @Column(nullable = false)
    private String rol; // Sugerencia: "CLIENTE", "CONDUCTOR", "ADMIN"

    private String estado;

    @Column(name = "fecha_registro", updatable = false)
    private LocalDateTime fechaRegistro;

    @Column(name = "token_recuperacion")
    private String tokenRecuperacion;

    @Column(name = "token_expiracion")
    private LocalDateTime tokenExpiracion;

    @PrePersist
    public void prePersist() {
        if (this.estado == null) this.estado = "activo";
        if (this.fechaRegistro == null) this.fechaRegistro = LocalDateTime.now();
        // Normalizar correo a minúsculas antes de guardar
        if (this.correo != null) this.correo = this.correo.toLowerCase().trim();
    }
}