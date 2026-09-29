package com.pulsestudio.desktop.api;

import com.pulsestudio.desktop.server.Json;
import com.pulsestudio.desktop.server.Request;
import com.pulsestudio.desktop.server.Router;

import java.nio.file.Files;
import java.util.Map;

/**
 * Generación del pack: POST /api/article (texto depurado), POST /api/script (guion + 6 búsquedas),
 * POST /api/photo (una foto de Pexels ya convertida) y GET /api/images/{id} (JPEG 1080 × 1920).
 */
public final class ContentController {
    private final Services s;

    public ContentController(Services s) { this.s = s; }

    public void register(Router r) {
        r.post("/api/article", this::article);
        r.post("/api/script", this::script);
        r.post("/api/photo", this::photo);
        r.get("/api/images/{id}", req -> req.send(200, "image/jpeg", Files.readAllBytes(s.images.get(req.param("id")))));
    }

    private void article(Request req) throws Exception {
        Map<String, Object> b = req.json();
        req.json(200, s.articles.extract(Json.str(b, "url"), Json.str(b, "title"), Json.str(b, "manualText"),
            Json.str(b, "lastExtracted"), Json.str(b, "storyText"), Json.str(b, "summary")).toMap());
    }

    private void script(Request req) throws Exception {
        Map<String, Object> b = req.json();
        Map<String, Object> ev = Json.obj(b, "evidence");
        req.json(200, s.scripts.create(s.key("groq"), s.settings.groqModel(), Json.obj(b, "news"), Json.str(ev, "text"),
            Json.str(ev, "origin"), Json.str(b, "category")));
    }

    private void photo(Request req) throws Exception {
        Map<String, Object> b = req.json();
        req.json(200, s.pexels.photo(s.key("pexels"), Json.str(b, "query"), Json.list(b, "exclude")));
    }
}
