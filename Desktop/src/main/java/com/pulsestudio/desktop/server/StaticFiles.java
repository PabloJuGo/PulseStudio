package com.pulsestudio.desktop.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Sirve la interfaz (index.html, styles.css, app.js y vendor/) desde el JAR o, en desarrollo, desde una carpeta
 * indicada con -Dpulse.web=RUTA. Solo rutas normalizadas dentro de /web; nunca listados de directorio.
 */
public final class StaticFiles {
    private static final Map<String, String> TYPES = Map.ofEntries(
        Map.entry("html", "text/html; charset=utf-8"), Map.entry("css", "text/css; charset=utf-8"),
        Map.entry("js", "text/javascript; charset=utf-8"), Map.entry("mjs", "text/javascript; charset=utf-8"),
        Map.entry("wasm", "application/wasm"), Map.entry("tflite", "application/octet-stream"),
        Map.entry("json", "application/json"), Map.entry("svg", "image/svg+xml"), Map.entry("png", "image/png"),
        Map.entry("ico", "image/x-icon"));
    private final Path devRoot;

    public StaticFiles(Path devRoot) { this.devRoot = devRoot; }

    void serve(Request req) throws IOException {
        String path = req.path();
        if (path.equals("/")) path = "/index.html";
        if (path.contains("..") || path.contains("\\") || path.contains("//") || !path.matches("/[A-Za-z0-9._/-]+")) {
            req.json(404, Map.of("error", "No encontrado.")); return;
        }
        String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase();
        String type = TYPES.get(ext);
        if (type == null) { req.json(404, Map.of("error", "No encontrado.")); return; }
        try (InputStream in = open(path)) {
            if (in == null) { req.json(404, Map.of("error", "No encontrado.")); return; }
            var ex = req.exchange();
            ex.getResponseHeaders().set("Content-Type", type);
            ex.getResponseHeaders().set("Cache-Control", path.startsWith("/vendor/") ? "max-age=86400" : "no-cache");
            if ("HEAD".equals(req.method())) { ex.sendResponseHeaders(200, -1); return; }
            ex.sendResponseHeaders(200, 0);
            try (OutputStream out = ex.getResponseBody()) { in.transferTo(out); }
        }
    }

    private InputStream open(String path) throws IOException {
        if (devRoot != null) {
            Path p = devRoot.resolve(path.substring(1)).normalize();
            if (!p.startsWith(devRoot) || !Files.isRegularFile(p)) return null;
            return Files.newInputStream(p);
        }
        return StaticFiles.class.getResourceAsStream("/web" + path);
    }
}
