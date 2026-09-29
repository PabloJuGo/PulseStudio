package com.pulsestudio.desktop.api;

import com.pulsestudio.desktop.server.Request;
import com.pulsestudio.desktop.server.Router;

/** GET /api/news?q=&category=&source=&sort= — búsqueda multifuente con filtros y deduplicación. */
public final class NewsController {
    private final Services s;

    public NewsController(Services s) { this.s = s; }

    public void register(Router r) {
        r.get("/api/news", req -> req.json(200, s.news.search(req.query("q"), req.query("category"), req.query("source"), req.query("sort"))));
    }
}
