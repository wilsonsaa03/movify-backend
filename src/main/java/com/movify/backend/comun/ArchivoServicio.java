package com.movify.backend.comun;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Guarda y entrega archivos subidos (documentos de conductores, fotos de perfil y evidencias).
 *
 * - El tipo se decide por los primeros bytes del archivo, no por el nombre ni por el
 *   Content-Type que manda el navegador: un "cedula.html" con JavaScript se rechaza.
 * - La extensión la pone el servidor (.pdf, .jpg, .png, .webp).
 * - Al servir, se valida que la ruta quede dentro del directorio de archivos.
 */
@Service
public class ArchivoServicio {

    /** Lo mismo que permite documentos_conductor.tamano_bytes (10 MB). */
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Pattern NOMBRE_VALIDO = Pattern.compile("^[a-z_]+_[0-9a-f\\-]{36}\\.(pdf|jpg|png|webp)$");
    private static final Map<String, String> TIPOS = Map.of(
            "pdf", "application/pdf",
            "jpg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp");

    private final Path directorio;

    public ArchivoServicio(@Value("${app.documentos.dir:/var/www/ds2/movify/uploads-conductores}") String dir) {
        this.directorio = Paths.get(dir).toAbsolutePath().normalize();
    }

    public record Guardado(String nombre, String mimeType, long tamanoBytes, String nombreOriginal) {
    }

    public record Archivo(Resource recurso, String contentType, String nombre) {
    }

    /**
     * @param prefijo     solo letras minúsculas y guion bajo (ej. "soat", "foto")
     * @param permitirPdf false para fotos
     */
    public Guardado guardar(MultipartFile archivo, String prefijo, boolean permitirPdf) {
        if (archivo == null || archivo.isEmpty()) {
            throw new ReglaNegocioException(400, "Falta el archivo");
        }
        if (archivo.getSize() > MAX_BYTES) {
            throw new ReglaNegocioException(413, "El archivo supera 10 MB");
        }
        try {
            String extension = detectarExtension(archivo);
            if (extension == null || (!permitirPdf && "pdf".equals(extension))) {
                throw new ReglaNegocioException(415, permitirPdf ? "El archivo debe ser PDF, JPG, PNG o WEBP"
                        : "La foto debe ser JPG, PNG o WEBP");
            }
            Files.createDirectories(directorio);
            String nombre = prefijo + "_" + UUID.randomUUID() + "." + extension;
            try (InputStream in = archivo.getInputStream()) {
                Files.copy(in, directorio.resolve(nombre), StandardCopyOption.REPLACE_EXISTING);
            }
            String original = archivo.getOriginalFilename();
            if (original != null && original.length() > 150) original = original.substring(original.length() - 150);
            return new Guardado(nombre, TIPOS.get(extension), archivo.getSize(), original);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo guardar el archivo " + prefijo, e);
        }
    }

    public Archivo obtener(String nombre) {
        if (nombre == null || !NOMBRE_VALIDO.matcher(nombre).matches()) {
            throw new RecursoNoEncontradoException("Archivo no encontrado");
        }
        Path ruta = directorio.resolve(nombre).normalize();
        if (!ruta.startsWith(directorio) || !Files.isRegularFile(ruta)) {
            throw new RecursoNoEncontradoException("Archivo no encontrado");
        }
        String tipo;
        try (InputStream in = Files.newInputStream(ruta)) {
            String ext = extensionPorFirma(in.readNBytes(12));
            tipo = ext == null ? "application/octet-stream" : TIPOS.get(ext);
        } catch (IOException e) {
            throw new RecursoNoEncontradoException("Archivo no encontrado");
        }
        return new Archivo(new PathResource(ruta), tipo, nombre);
    }

    public void borrar(String nombre) {
        if (nombre == null || !NOMBRE_VALIDO.matcher(nombre).matches()) return;
        try {
            Files.deleteIfExists(directorio.resolve(nombre).normalize());
        } catch (IOException e) {
            // no es grave: queda un archivo huérfano
        }
    }

    private static String detectarExtension(MultipartFile archivo) throws IOException {
        byte[] cabecera = new byte[12];
        int leidos;
        try (InputStream in = archivo.getInputStream()) {
            leidos = in.readNBytes(cabecera, 0, cabecera.length);
        }
        return extensionPorFirma(Arrays.copyOf(cabecera, leidos));
    }

    static String extensionPorFirma(byte[] b) {
        if (empiezaCon(b, 0, 0x25, 0x50, 0x44, 0x46)) return "pdf"; // %PDF
        if (empiezaCon(b, 0, 0xFF, 0xD8, 0xFF)) return "jpg";
        if (empiezaCon(b, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return "png";
        if (empiezaCon(b, 0, 0x52, 0x49, 0x46, 0x46) && empiezaCon(b, 8, 0x57, 0x45, 0x42, 0x50)) return "webp"; // RIFF....WEBP
        return null;
    }

    private static boolean empiezaCon(byte[] datos, int desde, int... firma) {
        if (datos.length < desde + firma.length) return false;
        for (int i = 0; i < firma.length; i++) {
            if ((datos[desde + i] & 0xFF) != firma[i]) return false;
        }
        return true;
    }
}
