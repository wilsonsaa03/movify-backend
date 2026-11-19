package com.movify.backend.domicilios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.movify.backend.comun.ReglaNegocioException;
import com.movify.backend.seguridad.SesionActual;

/**
 * Panel de catálogo de domicilios contra la base LOCAL (movify_local), dentro de una transacción que se deshace al
 * final: no queda nada guardado (ni en auditoria_admin). Solo corre con MOVIFY_LOCAL_IT=1:
 *   $env:MOVIFY_LOCAL_IT="1"; mvn -o test -Dtest=AdminCatalogoLocalTest
 */
@SpringBootTest(properties = { "app.simulacion.reloj=false", "app.tareas.habilitadas=false", "app.demo.simulacion=false",
        "app.limites.habilitados=false" })
@ActiveProfiles("local")
@Transactional
@EnabledIfEnvironmentVariable(named = "MOVIFY_LOCAL_IT", matches = "1")
class AdminCatalogoLocalTest {

    @Autowired
    AdminCatalogoDomicilios admin;
    @Autowired
    CatalogoDomicilios catalogo;
    @Autowired
    CuponesDomicilio cupones;
    @Autowired
    JdbcTemplate db;
    @MockitoBean
    SesionActual sesion;

    private Long algunUsuario() {
        return db.queryForObject("SELECT min(id) FROM usuarios", Long.class);
    }

    @Test
    void comercioProductoConOpcionesYCupon() {
        when(sesion.usuarioId()).thenReturn(algunUsuario());

        // Comercio
        Map<String, Object> c = admin.guardarComercio(null, new AdminCatalogoDomicilios.ComercioRequest("Prueba Panel", "comida",
                "desc", null, "Calle 1", "Centro", 3.88, -77.03, "3001234567", "00:00", "23:59", new BigDecimal("10000"),
                new BigDecimal("8"), 10, true, 0));
        Long comercioId = ((Number) c.get("id")).longValue();
        assertThat(db.queryForObject("SELECT telefono FROM comercios_domicilio WHERE id = ?", String.class, comercioId))
                .isEqualTo("+573001234567");
        assertThat(admin.comercios()).anyMatch(x -> "Prueba Panel".equals(x.get("nombre")));
        assertThatThrownBy(() -> admin.guardarComercio(comercioId, new AdminCatalogoDomicilios.ComercioRequest("X", "comida", null, null,
                "Calle 1", null, 3.88, -77.03, null, "08:00", "08:00", BigDecimal.ZERO, BigDecimal.ONE, 5, true, 0)))
                .isInstanceOf(ReglaNegocioException.class);

        // Producto con dos grupos (obligatorio y opcional)
        var grupos = List.of(
                new AdminCatalogoDomicilios.GrupoRequest("Tamaño", 1, 1, List.of(
                        new AdminCatalogoDomicilios.OpcionRequest("Normal", BigDecimal.ZERO, true),
                        new AdminCatalogoDomicilios.OpcionRequest("Grande", new BigDecimal("4000"), true))),
                new AdminCatalogoDomicilios.GrupoRequest("Adiciones", 0, 2, List.of(
                        new AdminCatalogoDomicilios.OpcionRequest("Queso", new BigDecimal("2000"), true),
                        new AdminCatalogoDomicilios.OpcionRequest("Huevo", new BigDecimal("1500"), false))));
        Map<String, Object> p = admin.guardarProducto(comercioId, null, new AdminCatalogoDomicilios.ProductoRequest("Platos",
                "Arepa", "con queso", null, new BigDecimal("8000"), true, false, 0, grupos));
        Long productoId = ((Number) p.get("id")).longValue();
        Map<String, Object> lista = admin.productos(comercioId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ps = (List<Map<String, Object>>) lista.get("productos");
        assertThat(ps).hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gs = (List<Map<String, Object>>) ps.get(0).get("grupos");
        assertThat(gs).hasSize(2);
        assertThat(gs.get(0)).containsEntry("obligatorio", true);

        // Editar reemplaza los grupos completos; un máximo mayor que las opciones se rechaza
        admin.guardarProducto(null, productoId, new AdminCatalogoDomicilios.ProductoRequest("Platos", "Arepa rellena", null, null,
                new BigDecimal("9000"), true, false, 0, List.of(grupos.get(0))));
        assertThat(catalogo.gruposDe(comercioId).get(productoId)).hasSize(1);
        assertThatThrownBy(() -> admin.guardarProducto(null, productoId, new AdminCatalogoDomicilios.ProductoRequest("Platos", "Arepa",
                null, null, BigDecimal.TEN, true, false, 0, List.of(new AdminCatalogoDomicilios.GrupoRequest("X", 0, 5,
                        List.of(new AdminCatalogoDomicilios.OpcionRequest("Una", BigDecimal.ZERO, true)))))))
                .isInstanceOf(ReglaNegocioException.class);
        admin.disponibilidad(productoId, false);
        assertThat(db.queryForObject("SELECT disponible FROM productos_domicilio WHERE id = ?", Boolean.class, productoId)).isFalse();

        // El cliente lo ve en el catálogo (agotado incluido, en gris)
        Map<String, Object> tienda = catalogo.comercio(comercioId, 3.88, -77.03);
        assertThat(tienda).containsEntry("abierto", true).containsEntry("entrega_aqui", true);

        // Cupón: código en mayúsculas, porcentaje con tope
        Map<String, Object> cu = admin.guardarCupon(null, new AdminCatalogoDomicilios.CuponRequest(" prueba-10 ", "10 % de prueba",
                "porcentaje", new BigDecimal("10"), new BigDecimal("3000"), new BigDecimal("5000"), comercioId, false, 2, null, null, true));
        assertThat(cu).containsEntry("codigo", "PRUEBA10");
        CuponesDomicilio.Resultado r = cupones.evaluar("prueba10", algunUsuario(), comercioId, new BigDecimal("50000"), new BigDecimal("4000"));
        assertThat(r.valido()).isTrue();
        assertThat(r.descuento()).isEqualByComparingTo("3000"); // 10 % de 50.000 = 5.000, con tope 3.000
        assertThat(cupones.evaluar("PRUEBA10", algunUsuario(), comercioId, new BigDecimal("1000"), BigDecimal.ZERO).valido()).isFalse();
        assertThat(admin.cupones()).anyMatch(x -> "PRUEBA10".equals(x.get("codigo")));

        // Borrar (los pedidos viejos conservan su copia)
        admin.borrarProducto(productoId);
        admin.borrarComercio(comercioId);
        assertThat(db.queryForObject("SELECT count(*) FROM comercios_domicilio WHERE id = ?", Integer.class, comercioId)).isZero();
        // Cada cambio quedó en la auditoría (se deshace al final de la prueba)
        assertThat(db.queryForObject("SELECT count(*) FROM auditoria_admin WHERE entidad IN ('comercio_domicilio', 'producto_domicilio', 'cupon_domicilio') AND entidad_id IN (?, ?)",
                Integer.class, comercioId, productoId)).isGreaterThanOrEqualTo(4);
    }
}
