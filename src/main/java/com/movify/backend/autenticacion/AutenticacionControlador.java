package com.movify.backend.autenticacion;

import java.util.HashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.movify.backend.autenticacion.AuthDtos.GoogleLoginRequest;
import com.movify.backend.autenticacion.AuthDtos.LoginCelularRequest;
import com.movify.backend.autenticacion.AuthDtos.LoginCodigoRequest;
import com.movify.backend.autenticacion.AuthDtos.LoginRequest;
import com.movify.backend.autenticacion.AuthDtos.OlvidePasswordRequest;
import com.movify.backend.autenticacion.AuthDtos.PedirCodigoRequest;
import com.movify.backend.autenticacion.AuthDtos.RefreshRequest;
import com.movify.backend.autenticacion.AuthDtos.RegistroAdminRequest;
import com.movify.backend.autenticacion.AuthDtos.RegistroMovilRequest;
import com.movify.backend.autenticacion.AuthDtos.RegistroUsuarioRequest;
import com.movify.backend.autenticacion.AuthDtos.RestablecerClaveRequest;
import com.movify.backend.autenticacion.AuthDtos.RestablecerPasswordRequest;
import com.movify.backend.autenticacion.AuthDtos.VerificarCodigoRequest;
import com.movify.backend.comun.Celular;
import com.movify.backend.comun.ReglaNegocioException;
import com.movify.backend.configuracion.ContextoPeticion;
import com.movify.backend.usuarios.UsuarioRepositorio;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AutenticacionControlador {

    private final AutenticacionServicio auth;
    private final CodigoVerificacionServicio codigos;
    private final UsuarioRepositorio usuarios;

    public AutenticacionControlador(AutenticacionServicio auth, CodigoVerificacionServicio codigos,
            UsuarioRepositorio usuarios) {
        this.auth = auth;
        this.codigos = codigos;
        this.usuarios = usuarios;
    }

    // ───────────── Código SMS ─────────────

    /**
     * Envía un código de 6 números al celular.
     *  - registro: si el número ya tiene cuenta responde 409 (para que la app lo mande a "Iniciar sesión").
     *  - login:    si el número NO tiene cuenta responde igual que si la tuviera (no se revela) y no envía nada.
     */
    @PostMapping("/codigo")
    public Map<String, Object> pedirCodigo(@Valid @RequestBody PedirCodigoRequest req, HttpServletRequest http) {
        String tel = Celular.exigir(req.celular());
        String proposito = CodigoVerificacionServicio.validarProposito(req.proposito());
        Map<String, Object> r = new HashMap<>();
        r.put("message", "Te enviamos un código por SMS al " + Celular.visible(tel));

        boolean existe = usuarios.existeTelefono(tel);
        if (CodigoVerificacionServicio.REGISTRO.equals(proposito) && existe) {
            throw new ReglaNegocioException("Ya existe una cuenta con ese celular. Inicia sesión.");
        }
        if (CodigoVerificacionServicio.LOGIN.equals(proposito) && !existe) {
            r.put("vigenciaSeg", 300);
            return r;
        }
        CodigoVerificacionServicio.Envio envio = codigos.enviar(tel, proposito, ContextoPeticion.de(http).ip());
        r.put("vigenciaSeg", envio.vigenciaSeg());
        if (envio.codigoDesarrollo() != null) {
            r.put("codigoDesarrollo", envio.codigoDesarrollo()); // solo con SMS_CONSOLA=true
        }
        return r;
    }

    @PostMapping("/codigo/verificar")
    public Map<String, Object> verificarCodigo(@Valid @RequestBody VerificarCodigoRequest req) {
        String tel = Celular.exigir(req.celular());
        String token = codigos.verificar(tel, req.proposito(), req.codigo());
        return Map.of("verificacionToken", token, "celular", tel);
    }

    // ───────────── App móvil ─────────────

    @PostMapping("/registro-movil")
    public Map<String, Object> registroMovil(@Valid @RequestBody RegistroMovilRequest req, HttpServletRequest http) {
        return auth.registrarMovil(req, ContextoPeticion.de(http));
    }

    @PostMapping("/login-celular")
    public Map<String, Object> loginCelular(@Valid @RequestBody LoginCelularRequest req, HttpServletRequest http) {
        return auth.loginCelular(req, ContextoPeticion.de(http));
    }

    @PostMapping("/login-codigo")
    public Map<String, Object> loginCodigo(@Valid @RequestBody LoginCodigoRequest req, HttpServletRequest http) {
        return auth.loginCodigo(req.verificacionToken(), ContextoPeticion.de(http));
    }

    @PostMapping("/clave/restablecer")
    public Map<String, Object> restablecerClave(@Valid @RequestBody RestablecerClaveRequest req, HttpServletRequest http) {
        return auth.restablecerClave(req.verificacionToken(), req.clave(), ContextoPeticion.de(http));
    }

    // ───────────── Sesión ─────────────

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@Valid @RequestBody RefreshRequest req, HttpServletRequest http) {
        return auth.refrescar(req.refreshToken(), ContextoPeticion.de(http));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestBody(required = false) Map<String, String> body) {
        if (body != null) auth.cerrarSesion(body.getOrDefault("refreshToken", body.get("refresh_token")));
        return Map.of("message", "Sesión cerrada");
    }

    // ───────────── Web (correo y contraseña) ─────────────

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        return auth.loginCorreo(req, ContextoPeticion.de(http));
    }

    /** El navegador o la app mandan solo el id_token de Google; nombre y correo se leen del token verificado. */
    @PostMapping("/login-google")
    public Map<String, Object> loginGoogle(@Valid @RequestBody GoogleLoginRequest req, HttpServletRequest http) {
        return auth.loginGoogle(req.token(), ContextoPeticion.de(http));
    }

    @PostMapping("/registro")
    public Map<String, Object> registrar(@Valid @RequestBody RegistroUsuarioRequest req) {
        return auth.registrarWeb(req);
    }

    @PostMapping("/registro-admin")
    public Map<String, Object> registrarAdmin(@Valid @RequestBody RegistroAdminRequest req) {
        return auth.registrarAdmin(req);
    }

    @PostMapping("/olvide-password")
    public Map<String, Object> olvidePassword(@Valid @RequestBody OlvidePasswordRequest req) {
        auth.solicitarRecuperacion(req.correo());
        return Map.of("message", "Si el correo está registrado, te enviamos un enlace para restablecer la contraseña.");
    }

    @PostMapping("/restablecer-password")
    public Map<String, Object> restablecer(@Valid @RequestBody RestablecerPasswordRequest req) {
        auth.restablecerPassword(req.token(), req.password());
        return Map.of("message", "Contraseña actualizada. Ya puedes iniciar sesión.");
    }
}
