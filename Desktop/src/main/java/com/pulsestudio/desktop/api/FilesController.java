package com.pulsestudio.desktop.api;

import com.pulsestudio.desktop.server.Json;
import com.pulsestudio.desktop.server.Request;
import com.pulsestudio.desktop.server.Router;

import java.nio.file.Path;

/**
 * POST /api/files?name=&folder= — guarda en la carpeta de salida (streaming, sin límite de memoria).
 * POST /api/system/reveal {path} — muestra el archivo o carpeta en el Explorador.
 * POST /api/system/open-url {url} — abre un enlace externo en el navegador predeterminado.
 */
public final class FilesController {
    private final Services s;

    public FilesController(Services s) { this.s = s; }

    public void register(Router r) {
        r.post("/api/files", this::save);
        r.post("/api/system/reveal", req -> { s.system.reveal(s.files.inside(Json.str(req.json(), "path"))); req.json(200, Json.map("ok", true)); });
        r.post("/api/system/open-url", req -> { s.system.openUrl(Json.str(req.json(), "url")); req.json(200, Json.map("ok", true)); });
    }

    private void save(Request req) throws Exception {
        Path saved = s.files.save(req.query("folder"), req.query("name"), req.bodyStream(), req.contentLength());
        req.json(200, Json.map("saved", true, "path", saved.toString(), "folder", saved.getParent().toString(), "name", saved.getFileName().toString()));
    }
}
