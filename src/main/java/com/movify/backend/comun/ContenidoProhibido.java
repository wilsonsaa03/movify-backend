package com.movify.backend.comun;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Detecta en lo que escribe el cliente (qué lleva una encomienda, qué pide en un domicilio)
 * cosas que MoviFY no transporta por ser delito o peligrosas: drogas, armas, municiones,
 * explosivos, dinero en efectivo y objetos robados o de contrabando.
 *
 * Compara palabras completas, sin tildes ni mayúsculas y deshaciendo trucos simples
 * ("c0ca1na", "m.a.r.i.h.u.a.n.a"). A propósito NO incluye palabras con usos comunes
 * y legales en Buenaventura (machete, cuchillo, coca como gaseosa, perico como desayuno, granada la
 * fruta, cartuchos de tinta, Hurtado el apellido) y deja pasar "pistola de silicona" y similares:
 * mejor dejar pasar un caso dudoso que rechazar a quien envía un almuerzo.
 *
 * La misma lista está en la web (core/contenido-prohibido.ts) y en la app
 * (servicios/contenido-prohibido.ts) para avisar antes de enviar; quien decide es esta clase.
 */
public final class ContenidoProhibido {

    private ContenidoProhibido() {
    }

    /** Categoría → palabras o frases (ya sin tildes y en minúscula). */
    private static final Map<String, List<String>> LISTA = new LinkedHashMap<>();

    static {
        LISTA.put("drogas", List.of("droga", "drogas", "cocaina", "marihuana", "mariguana", "marijuana", "cannabis",
                "bareta", "creepy", "cripy", "porro", "porros", "bazuco", "basuco", "heroina", "extasis", "tusi", "tussi",
                "tucibi", "2cb", "lsd", "metanfetamina", "anfetamina", "fentanilo", "crack", "hachis", "pasta base",
                "hoja de coca", "estupefaciente", "estupefacientes", "sustancia ilegal", "sustancias ilegales",
                "sustancia prohibida", "sustancias prohibidas"));
        LISTA.put("armas", List.of("arma", "armas", "arma de fuego", "pistola", "pistolas", "revolver", "revolveres",
                "fusil", "fusiles", "escopeta", "escopetas", "rifle", "rifles", "subametralladora", "ametralladora",
                "changon", "trabuco", "silenciador"));
        LISTA.put("municiones", List.of("municion", "municiones", "bala", "balas", "proyectil", "proyectiles"));
        LISTA.put("explosivos", List.of("explosivo", "explosivos", "granada de mano", "granadas de mano", "dinamita",
                "detonador", "detonadores", "polvora", "c4", "anfo", "mina antipersona", "minas antipersona"));
        LISTA.put("dinero en efectivo", List.of("dinero", "dinero en efectivo", "billetes", "fajo de billetes",
                "fajos de billetes", "dolares", "plata en efectivo"));
        // "Hurtado" es apellido común: solo cuenta acompañado ("mercancía hurtada")
        LISTA.put("objetos robados o de contrabando", List.of("robado", "robada", "robados", "robadas",
                "mercancia hurtada", "objeto hurtado", "objetos hurtados", "contrabando"));
    }

    /** Usos legales que contienen una palabra de la lista: se quitan antes de buscar. */
    private static final List<String> PERMITIDOS = List.of("pistola de silicona", "pistola de calor", "pistola de agua",
            "pistola de juguete", "pistolas de juguete", "arma de juguete", "armas de juguete", "pistola de pintura",
            "pistola para pintar", "pistola de aire para inflar", "pistola de riego");

    /**
     * Palabras que el admin agregó desde el panel (tabla palabras_prohibidas). Se suman a la lista base;
     * las "permitidas" son excepciones legales como las de PERMITIDOS. Las carga ContenidoProhibidoCatalogo.
     */
    private static volatile Map<String, List<String>> extras = Map.of();
    private static volatile List<String> permitidosExtra = List.of();

    public static void actualizarExtras(Map<String, List<String>> prohibidas, List<String> permitidas) {
        Map<String, List<String>> p = new LinkedHashMap<>();
        prohibidas.forEach((categoria, palabras) -> p.put(categoria, palabras.stream().map(ContenidoProhibido::normalizar).toList()));
        extras = Map.copyOf(p);
        permitidosExtra = permitidas.stream().map(ContenidoProhibido::normalizar).toList();
    }

    /** La lista base (la del código), para mostrarla en el panel. */
    public static Map<String, List<String>> listaBase() {
        return Map.copyOf(LISTA);
    }

    public static List<String> permitidosBase() {
        return PERMITIDOS;
    }

    /** Forma normal de una palabra (minúsculas, sin tildes ni signos): así se guardan las del panel. */
    public static String forma(String palabra) {
        return palabra == null ? "" : normalizar(palabra);
    }

    /** @return la categoría encontrada ("drogas", "armas"...), o vacío si el texto está bien. */
    public static Optional<String> detectar(String texto) {
        if (texto == null || texto.isBlank()) return Optional.empty();
        String t = " " + normalizar(texto) + " ";
        for (String permitido : PERMITIDOS) t = t.replace(" " + permitido + " ", " ");
        for (String permitido : permitidosExtra) t = t.replace(" " + permitido + " ", " ");
        String pegado = " " + t.replace(" ", "") + " "; // "m a r i h u a n a" → "marihuana"
        Map<String, List<String>> todas = new LinkedHashMap<>(LISTA);
        extras.forEach((categoria, palabras) -> todas.merge(categoria, palabras,
                (a, b) -> java.util.stream.Stream.concat(a.stream(), b.stream()).toList()));
        for (Map.Entry<String, List<String>> e : todas.entrySet()) {
            for (String palabra : e.getValue()) {
                if (t.contains(" " + palabra + " ")) return Optional.of(e.getKey());
                // Letras separadas solo cuenta para palabras largas (evita falsos positivos con siglas)
                if (palabra.length() >= 6 && !palabra.contains(" ") && pegado.contains(palabra)
                        && !t.contains(palabra)) {
                    return Optional.of(e.getKey());
                }
            }
        }
        return Optional.empty();
    }

    /** Lanza 400 con un mensaje claro si el texto menciona algo prohibido. */
    public static void exigirPermitido(String... textos) {
        for (String texto : textos) {
            detectar(texto).ifPresent(categoria -> {
                throw new ReglaNegocioException(400, "No podemos llevar esto: menciona " + categoria
                        + ". En MoviFY no se envían drogas, armas, municiones, explosivos, dinero en efectivo"
                        + " ni objetos robados. Si es un error, describe el paquete con otras palabras.");
            });
        }
    }

    /** Minúsculas, sin tildes, números "disfrazados" (c0ca1na → cocaina) y solo letras/números separados por espacio. */
    static String normalizar(String texto) {
        String s = Normalizer.normalize(texto.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // Un número entre letras es un truco ("c0ca1na"); solo (como en "2cb" o "c4") se deja igual
            boolean entreLetras = i > 0 && i < s.length() - 1 && Character.isLetter(s.charAt(i - 1))
                    && Character.isLetter(s.charAt(i + 1));
            b.append(switch (c) {
                case '0' -> entreLetras ? 'o' : c;
                case '1' -> entreLetras ? 'i' : c;
                case '3' -> entreLetras ? 'e' : c;
                case '4' -> entreLetras ? 'a' : c;
                case '5' -> entreLetras ? 's' : c;
                case '@' -> 'a';
                case '$' -> 's';
                default -> Character.isLetterOrDigit(c) ? c : ' ';
            });
        }
        return b.toString().replaceAll("\\s+", " ").trim();
    }
}
