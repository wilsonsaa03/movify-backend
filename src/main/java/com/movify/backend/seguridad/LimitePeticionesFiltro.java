package com.movify.backend.seguridad;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.movify.backend.configuracion.ContextoPeticion;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Límite de peticiones (ventana fija, en memoria). Complementa el bloqueo por cuenta del DER
 * (5 claves erradas → 15 min): aquí se frena a quien prueba MUCHAS cuentas desde la misma IP,
 * a quien manda SMS a números al azar (cada SMS de Twilio cuesta) y a quien satura la API.
 *
 * Reglas (por IP; la general, por sesión si trae token):
 *   login         POST /api/auth/login*                         20 / 5 min
 *   sms           POST /api/auth/codigo                          5 / 15 min
 *   verificar     POST /api/auth/codigo/verificar, login-verificar 20 / 15 min
 *   registro      POST /api/auth/registro*                      10 / hora
 *   recuperar     POST olvide-password, restablecer-*, clave/*   5 / 15 min
 *   publico       GET  /api/publico/**, /api/tarifas/cotizar    60 / min
 *   general       todo /api/**                                 300 / min (por sesión o IP)
 *   general_ip    todo /api/** con token, por IP              1500 / min
 * Al pasarse: 429 con Retry-After. Se apaga con app.limites.habilitados=false (pruebas).
 */
@Component
public class LimitePeticionesFiltro extends OncePerRequestFilter {

    record Regla(String nombre, int maximo, long ventanaSeg) {
    }

    static final Regla LOGIN = new Regla("login", 20, 300);
    static final Regla SMS = new Regla("sms", 5, 900);
    static final Regla VERIFICAR = new Regla("verificar", 20, 900);
    static final Regla REGISTRO = new Regla("registro", 10, 3600);
    static final Regla RECUPERAR = new Regla("recuperar", 5, 900);
    static final Regla PUBLICO = new Regla("publico", 60, 60);
    static final Regla GENERAL = new Regla("general", 300, 60);
    static final Regla GENERAL_IP = new Regla("general_ip", 1500, 60);

    private static final List<String> LOGIN_RUTAS = List.of("/api/auth/login", "/api/auth/login-celular",
            "/api/auth/login-codigo", "/api/auth/login-google");
    private static final List<String> REGISTRO_RUTAS = List.of("/api/auth/registro", "/api/auth/registro-movil",
            "/api/auth/registro-admin");
    private static final List<String> RECUPERAR_RUTAS = List.of("/api/auth/olvide-password",
            "/api/auth/restablecer-password", "/api/auth/clave/restablecer");

    private static final class Ventana {
        volatile long inicio;
        final AtomicLong cuenta = new AtomicLong();

        Ventana(long inicio) {
            this.inicio = inicio;
        }
    }

    private final Map<String, Ventana> ventanas = new ConcurrentHashMap<>();
    private final AtomicLong peticiones = new AtomicLong();
    private final VigilanciaSeguridad vigilancia;
    private final boolean habilitado;

    public LimitePeticionesFiltro(VigilanciaSeguridad vigilancia,
            @Value("${app.limites.habilitados:true}") boolean habilitado) {
        this.vigilancia = vigilancia;
        this.habilitado = habilitado;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !habilitado || "OPTIONS".equals(request.getMethod()) || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String ip = ContextoPeticion.ipCliente(req);
        String ruta = req.getRequestURI();
        long ahora = System.currentTimeMillis() / 1000;

        Regla especifica = reglaDe(req.getMethod(), ruta);
        if (especifica != null && !permitir(especifica, especifica.nombre() + "|" + ip, ahora, ip, ruta, res)) return;

        // General: por sesión cuando hay token (muchos celulares comparten IP con el operador móvil)
        String auth = req.getHeader("Authorization");
        String quien = auth != null && auth.startsWith("Bearer ") && auth.length() > 20 ? "t:" + huella(auth) : "ip:" + ip;
        if (!permitir(GENERAL, "general|" + quien, ahora, ip, ruta, res)) return;
        // Y un techo por IP aunque traiga token: inventar un token distinto en cada petición no lo salta
        if (quien.startsWith("t:") && !permitir(GENERAL_IP, "general_ip|" + ip, ahora, ip, ruta, res)) return;

        if (peticiones.incrementAndGet() % 2000 == 0) limpiar(ahora);
        chain.doFilter(req, res);
    }

    static Regla reglaDe(String metodo, String ruta) {
        if ("POST".equals(metodo)) {
            if (LOGIN_RUTAS.contains(ruta)) return LOGIN;
            if ("/api/auth/codigo".equals(ruta)) return SMS;
            if ("/api/auth/codigo/verificar".equals(ruta) || "/api/auth/login-verificar".equals(ruta)) return VERIFICAR;
            if (REGISTRO_RUTAS.contains(ruta)) return REGISTRO;
            if (RECUPERAR_RUTAS.contains(ruta)) return RECUPERAR;
        }
        if ("GET".equals(metodo) && (ruta.startsWith("/api/publico/") || "/api/tarifas/cotizar".equals(ruta))) return PUBLICO;
        return null;
    }

    private boolean permitir(Regla r, String llave, long ahora, String ip, String ruta, HttpServletResponse res)
            throws IOException {
        Ventana v = ventanas.computeIfAbsent(llave, k -> new Ventana(ahora));
        synchronized (v) {
            if (ahora - v.inicio >= r.ventanaSeg()) {
                v.inicio = ahora;
                v.cuenta.set(0);
            }
        }
        long n = v.cuenta.incrementAndGet();
        if (n <= r.maximo()) return true;

        long espera = Math.max(1, r.ventanaSeg() - (ahora - v.inicio));
        // Solo el primer rechazo de cada ventana va a la bitácora (si no, el ataque llenaría el log)
        if (n == r.maximo() + 1) {
            vigilancia.registrar("limite_superado", ip, ruta, "regla=" + r.nombre() + " max=" + r.maximo() + "/" + r.ventanaSeg() + "s");
        }
        res.setStatus(429);
        res.setHeader("Retry-After", String.valueOf(espera));
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        String cuando = espera >= 120 ? (espera / 60) + " minutos" : espera + " segundos";
        res.getWriter().write("{\"error\":\"Hiciste demasiadas solicitudes. Intenta de nuevo en " + cuando + ".\"}");
        return false;
    }

    private void limpiar(long ahora) {
        ventanas.entrySet().removeIf(e -> ahora - e.getValue().inicio > 3600);
    }

    private static String huella(String texto) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 12);
        } catch (Exception e) {
            return String.valueOf(texto.hashCode());
        }
    }
}
