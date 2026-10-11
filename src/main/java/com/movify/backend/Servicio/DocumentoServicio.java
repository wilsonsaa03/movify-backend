package com.movify.backend.Servicio;

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

import com.movify.backend.Comun.RecursoNoEncontradoException;
import com.movify.backend.Comun.ReglaNegocioException;

/**
 * Guarda y entrega los documentos de los conductores (cédula, licencia, SOAT, tarjeta).
 *
 * - El tipo se decide por los primeros bytes del archivo, no por el nombre ni por el
 *   Content-Type que manda el navegador: un "cedula.html" con JavaScript se rechaza.
 * - La extensión guardada la pone el servidor (.pdf, .jpg, .png).
 * - Al servir, se valida que la ruta quede dentro del directorio de documentos.
 */
@Service
public class DocumentoServicio {

    private static final long MAX_BYTES = 5L * 1024 * 1024;
    /** Nombres generados por el servidor (incluye los antiguos con extensión original). */
    private static final Pattern NOMBRE_VALIDO = Pattern.compile("^[a-z]+_[0-9a-f\\-]{36}(\\.[A-Za-z0-9]{1,5})?$");
    private static final Map<String, String> TIPOS = Map.of(
            "pdf", "application/pdf",
            "jpg", "image/jpeg",
            "png", "image/png");

    private final Path directorio;

    public DocumentoServicio(@Value("${app.documentos.dir:/var/www/ds2/movify/uploads-conductores}") String dir) {
        this.directorio = Paths.get(dir).toAbsolutePath().normalize();
    }

    /** @return nombre interno con el que quedó guardado. */
    public String guardar(MultipartFile archivo, String prefijo) {
        if (archivo == null || archivo.isEmpty()) {
            throw new ReglaNegocioException(400, "Falta el documento: " + prefijo);
        }
        if (archivo.getSize() > MAX_BYTES) {
            throw new ReglaNegocioException(413, "El documento " + prefijo + " supera 5 MB");
        }
        try {
            String extension = detectarExtension(archivo);
            if (extension == null) {
                throw new ReglaNegocioException(415,
                        "El documento " + prefijo + " debe ser PDF, JPG o PNG");
            }
            Files.createDirectories(directorio);
            String nombre = prefijo + "_" + UUID.randomUUID() + "." + extension;
            try (InputStream in = archivo.getInputStream()) {
                Files.copy(in, directorio.resolve(nombre), StandardCopyOption.REPLACE_EXISTING);
            }
            return nombre;
        } catch (IOException e) {
            // Antes se guardaba solo el nombre original y el registro quedaba "bien" sin archivo
            throw new IllegalStateException("No se pudo guardar el documento " + prefijo, e);
        }
    }

    public record Documento(Resource recurso, String contentType, String nombre) {
    }

    public Documento obtener(String nombre) {
        if (nombre == null || !NOMBRE_VALIDO.matcher(nombre).matches()) {
            throw new RecursoNoEncontradoException("Documento no encontrado");
        }
        Path ruta = directorio.resolve(nombre).normalize();
        if (!ruta.startsWith(directorio) || !Files.isRegularFile(ruta)) {
            throw new RecursoNoEncontradoException("Documento no encontrado");
        }
        // El tipo se decide otra vez por el contenido: los archivos viejos pudieron entrar sin validar
        String tipo;
        try (InputStream in = Files.newInputStream(ruta)) {
            String ext = extensionPorFirma(in.readNBytes(8));
            tipo = ext == null ? "application/octet-stream" : TIPOS.get(ext);
        } catch (IOException e) {
            throw new RecursoNoEncontradoException("Documento no encontrado");
        }
        return new Documento(new PathResource(ruta), tipo, nombre);
    }

    private static String detectarExtension(MultipartFile archivo) throws IOException {
        byte[] cabecera = new byte[8];
        int leidos;
        try (InputStream in = archivo.getInputStream()) {
            leidos = in.readNBytes(cabecera, 0, cabecera.length);
        }
        return extensionPorFirma(Arrays.copyOf(cabecera, leidos));
    }

    private static String extensionPorFirma(byte[] b) {
        if (empiezaCon(b, 0x25, 0x50, 0x44, 0x46)) return "pdf"; // %PDF
        if (empiezaCon(b, 0xFF, 0xD8, 0xFF)) return "jpg";
        if (empiezaCon(b, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return "png";
        return null;
    }

    private static boolean empiezaCon(byte[] datos, int... firma) {
        if (datos.length < firma.length) return false;
        for (int i = 0; i < firma.length; i++) {
            if ((datos[i] & 0xFF) != firma[i]) return false;
        }
        return true;
    }
}
