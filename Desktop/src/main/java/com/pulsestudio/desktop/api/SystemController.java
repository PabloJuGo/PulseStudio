package com.pulsestudio.desktop.api;

import com.pulsestudio.desktop.server.Json;
import com.pulsestudio.desktop.server.LocalServer;
import com.pulsestudio.desktop.server.Router;

/** GET /api/ping — latido de la ventana (el backend se cierra si la interfaz desaparece) y versión. */
public final class SystemController {
    public static final String VERSION = "1.0.0";
    private final LocalServer[] server;

    public SystemController(LocalServer[] server) { this.server = server; }

    public void register(Router r) {
        r.get("/api/ping", req -> { if (server[0] != null) server[0].touch(); req.json(200, Json.map("ok", true, "version", VERSION)); });
    }
}
