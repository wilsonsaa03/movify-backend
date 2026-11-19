package com.movify.backend.domicilios;

import java.util.List;
import java.util.Map;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.movify.backend.comun.ArchivoServicio;

import jakarta.validation.Valid;

/**
 * Domicilios con catálogo.
 *
 * Cliente (con sesión):
 *   GET  /api/domicilios/comercios?categoria=&q=&lat=&lng=   comercios aliados (abiertos primero, solo los que entregan ahí)
 *   GET  /api/domicilios/comercios/{id}?lat=&lng=            catálogo por secciones con grupos de opciones
 *   POST /api/domicilios/cotizar                             el carrito calculado por el backend (sin crear nada)
 *   POST /api/domicilios/pedir                               crea el domicilio (valida todo otra vez)
 *   GET  /api/domicilios/fotos/{nombre}                      fotos de comercios y productos (públicas)
 *
 * Panel (admin, permiso catalogos.gestionar; soporte puede ver con catalogos.ver):
 *   /api/admin/domicilios/comercios, /comercios/{id}/productos, /productos/{id}, /cupones, /fotos
 */
@RestController
public class DomiciliosControlador {

    private final CatalogoDomicilios catalogo;
    private final PedidoCatalogo pedidos;
    private final AdminCatalogoDomicilios admin;

    public DomiciliosControlador(CatalogoDomicilios catalogo, PedidoCatalogo pedidos, AdminCatalogoDomicilios admin) {
        this.catalogo = catalogo;
        this.pedidos = pedidos;
        this.admin = admin;
    }

    // ───────────── Cliente ─────────────

    @GetMapping("/api/domicilios/comercios")
    public List<Map<String, Object>> comercios(@RequestParam(required = false) String categoria,
            @RequestParam(required = false) String q, @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng) {
        return catalogo.comercios(categoria, q, lat, lng);
    }

    @GetMapping("/api/domicilios/comercios/{id}")
    public Map<String, Object> comercio(@PathVariable Long id, @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng) {
        return catalogo.comercio(id, lat, lng);
    }

    @PostMapping("/api/domicilios/cotizar")
    public Map<String, Object> cotizar(@Valid @RequestBody PedidoCatalogo.Pedido pedido) {
        return pedidos.cotizar(pedido);
    }

    @PostMapping("/api/domicilios/pedir")
    @PreAuthorize("hasAuthority('servicios.solicitar')")
    public Map<String, Object> pedir(@Valid @RequestBody PedidoCatalogo.Pedido pedido) {
        return pedidos.pedir(pedido);
    }

    /** Pública (va en <img src>): el nombre del archivo es un UUID imposible de adivinar. */
    @GetMapping("/api/domicilios/fotos/{nombre:.+}")
    public ResponseEntity<Resource> foto(@PathVariable String nombre) {
        ArchivoServicio.Archivo a = admin.foto(nombre);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(a.contentType()))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(7)).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .body(a.recurso());
    }

    // ───────────── Panel ─────────────

    @GetMapping("/api/admin/domicilios/comercios")
    @PreAuthorize("hasAuthority('catalogos.ver')")
    public List<Map<String, Object>> adminComercios() {
        return admin.comercios();
    }

    @PostMapping("/api/admin/domicilios/comercios")
    @PreAuthorize("hasAuthority('catalogos.gestionar')")
    public Map<String, Object> crearComercio(@Valid @RequestBody AdminCatalogoDomicilios.ComercioRequest r) {
        return admin.guardarComercio(null, r);
    }

    @PutMapping("/api/admin/domicilios/comercios/{id}")
    @PreAuthorize("hasAuthority('catalogos.gestionar')")
    public Map<String, Object> editarComercio(@PathVariable Long id, @Valid @RequestBody AdminCatalogoDomicilios.ComercioRequest r) {
        return admin.guardarComercio(id, r);
    }

    @DeleteMapping("/api/admin/domicilios/comercios/{id}")
    @PreAuthorize("hasAuthority('catalogos.gestionar')")
    public Map<String, Object> borrarComercio(@PathVariable Long id) {
        return admin.borrarComercio(id);
    }

    @GetMapping("/api/admin/domicilios/comercios/{id}/productos")
    @PreAuthorize("hasAuthority('catalogos.ver')")
    public Map<String, Object> productos(@PathVariable Long id) {
        return admin.productos(id);
    }

    @PostMapping("/api/admin/domicilios/comercios/{id}/productos")
    @PreAuthorize("hasAuthority('catalogos.gestionar')")
    public Map<String, Object> crearProducto(@PathVariable Long id, @Valid @RequestBody AdminCatalogoDomicilios.ProductoRequest r) {
        return admin.guardarProducto(id, null, r);
    }

    @PutMapping("/api/admin/domicilios/productos/{id}")
    @PreAuthorize("hasAuthority('catalogos.gestionar')")
    public Map<String, Object> editarProducto(@PathVariable Long id, @Valid @RequestBody AdminCatalogoDomicilios.ProductoRequest r) {
        return admin.guardarProducto(null, id, r);
    }

    public record DisponibleRequest(boolean disponible) {
    }

    @PutMapping("/api/admin/domicilios/productos/{id}/disponible")
    @PreAuthorize("hasAuthority('catalogos.gestionar')")
    public Map<String, Object> disponible(@PathVariable Long id, @RequestBody DisponibleRequest r) {
        return admin.disponibilidad(id, r.disponible());
    }

    @DeleteMapping("/api/admin/domicilios/productos/{id}")
    @PreAuthorize("hasAuthority('catalogos.gestionar')")
    public Map<String, Object> borrarProducto(@PathVariable Long id) {
        return admin.borrarProducto(id);
    }

    @GetMapping("/api/admin/domicilios/cupones")
    @PreAuthorize("hasAuthority('catalogos.ver')")
    public List<Map<String, Object>> cupones() {
        return admin.cupones();
    }

    @PostMapping("/api/admin/domicilios/cupones")
    @PreAuthorize("hasAuthority('catalogos.gestionar')")
    public Map<String, Object> crearCupon(@Valid @RequestBody AdminCatalogoDomicilios.CuponRequest r) {
        return admin.guardarCupon(null, r);
    }

    @PutMapping("/api/admin/domicilios/cupones/{id}")
    @PreAuthorize("hasAuthority('catalogos.gestionar')")
    public Map<String, Object> editarCupon(@PathVariable Long id, @Valid @RequestBody AdminCatalogoDomicilios.CuponRequest r) {
        return admin.guardarCupon(id, r);
    }

    @DeleteMapping("/api/admin/domicilios/cupones/{id}")
    @PreAuthorize("hasAuthority('catalogos.gestionar')")
    public Map<String, Object> borrarCupon(@PathVariable Long id) {
        return admin.borrarCupon(id);
    }

    @PostMapping("/api/admin/domicilios/fotos")
    @PreAuthorize("hasAuthority('catalogos.gestionar')")
    public Map<String, Object> subirFoto(@RequestParam("foto") MultipartFile foto) {
        return admin.subirFoto(foto);
    }
}
