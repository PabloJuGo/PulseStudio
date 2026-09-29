package com.pulsestudio.desktop.service;

import com.pulsestudio.desktop.server.ApiException;
import com.pulsestudio.desktop.store.SettingsStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * Guarda en disco lo que produce la interfaz (guion, imágenes, miniatura, vídeo) dentro de la carpeta de salida.
 * El cuerpo se copia en streaming (el vídeo nunca se carga entero en memoria) y los nombres se validan con lista
 * blanca: nunca se interpreta una ruta enviada por la interfaz.
 */
public final class FileService {
    public static final long MAX_BYTES = 1024L * 1024 * 1024; // 1 GiB
    private static final Map<String, String> EXT = Map.of("txt", "text", "jpg", "image", "jpeg", "image", "png", "image", "mp4", "video", "webm", "video");
    private final SettingsStore settings;

    public FileService(SettingsStore settings) { this.settings = settings; }

    public Path save(String folder, String name, InputStream body, long declaredLength) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,100}\\.(txt|jpg|jpeg|png|mp4|webm)")) throw ApiException.badRequest("Nombre de archivo no admitido.");
        if (folder != null && !folder.isEmpty() && !folder.matches("[A-Za-z0-9_-]{1,80}")) throw ApiException.badRequest("Nombre de carpeta no admitido.");
        if (declaredLength > MAX_BYTES) throw new ApiException(413, "El archivo supera 1 GiB.");
        Path root = settings.outputDir();
        Path dir = folder == null || folder.isEmpty() ? root : root.resolve(folder);
        Files.createDirectories(dir);
        Path target = unique(dir, name);
        Path tmp = Files.createTempFile(dir, ".pulse-", ".part");
        try {
            long total = 0;
            try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = body.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_BYTES) throw new ApiException(413, "El archivo supera 1 GiB.");
                    out.write(buf, 0, n);
                }
            }
            if (total == 0) throw ApiException.badRequest("El archivo está vacío.");
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** "video.mp4" → "video (2).mp4" si ya existe. */
    static Path unique(Path dir, String name) {
        Path p = dir.resolve(name);
        int dot = name.lastIndexOf('.');
        for (int i = 2; Files.exists(p) && i < 1000; i++) p = dir.resolve(name.substring(0, dot) + " (" + i + ")" + name.substring(dot));
        return p;
    }

    public static String kind(String name) {
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
        return EXT.getOrDefault(ext, "file");
    }

    /** Resuelve una ruta devuelta antes por save(): debe estar dentro de la carpeta de salida. */
    public Path inside(String raw) {
        Path root = settings.outputDir().toAbsolutePath().normalize();
        Path p = raw == null || raw.isBlank() ? root : Path.of(raw).toAbsolutePath().normalize();
        if (!p.startsWith(root)) throw new ApiException(403, "Solo se pueden abrir carpetas dentro de la carpeta de salida.");
        return p;
    }
}
