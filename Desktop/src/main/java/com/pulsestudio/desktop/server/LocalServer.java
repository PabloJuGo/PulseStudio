package com.pulsestudio.desktop.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Servidor HTTP embebido (JDK, sin dependencias) que sirve la interfaz y la API REST.
 *
 * Seguridad de un servidor local:
 *  - Escucha SOLO en 127.0.0.1 (no accesible desde la red).
 *  - Cabecera Host exacta (evita «DNS rebinding» desde webs maliciosas).
 *  - La API exige el token de sesión aleatorio (X-Pulse-Token) que el lanzador pasa a la ventana, y un Origin
 *    propio si el navegador lo envía (evita CSRF desde otras webs abiertas en el equipo).
 *  - CSP estricta: la interfaz solo puede conectarse a este servidor; las claves nunca salen del backend.
 */
public final class LocalServer {
    private static final Logger LOG = Logger.getLogger("PulseStudio");
    public static final String CSP = "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; "
        + "img-src 'self' blob: data:; media-src 'self' blob:; connect-src 'self'; font-src 'none'; object-src 'none'; "
        + "frame-src 'none'; frame-ancestors 'none'; worker-src 'none'; base-uri 'none'; form-action 'none'";

    private final HttpServer server;
    private final Router router;
    private final StaticFiles statics;
    private final String token;
    private final String origin;
    private final String host;
    private final AtomicLong lastPing = new AtomicLong(System.currentTimeMillis());
    private final ExecutorService pool;

    public LocalServer(int port, String token, Router router, StaticFiles statics) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 64);
        this.router = router;
        this.statics = statics;
        this.token = token;
        int actual = server.getAddress().getPort();
        this.host = "127.0.0.1:" + actual;
        this.origin = "http://" + host;
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "pulse-http-" + n.incrementAndGet()); t.setDaemon(true); return t; });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
    }

    public void start() { server.start(); }
    public void stop() { server.stop(0); pool.shutdownNow(); }
    public int port() { return server.getAddress().getPort(); }
    public String url() { return origin + "/"; }
    public long lastPing() { return lastPing.get(); }
    public void touch() { lastPing.set(System.currentTimeMillis()); }

    private void handle(HttpExchange ex) {
        Request req = new Request(ex);
        try {
            addSecurityHeaders(ex);
            if (!host.equals(req.header("Host")) && !("localhost:" + port()).equals(req.header("Host"))) {
                req.json(403, Map.of("error", "Host no permitido.")); return;
            }
            String path = req.path();
            if (path.startsWith("/api/")) {
                String fetchSite = req.header("Sec-Fetch-Site");
                String reqOrigin = req.header("Origin");
                if ("cross-site".equals(fetchSite) || (!reqOrigin.isEmpty() && !origin.equals(reqOrigin))) {
                    req.json(403, Map.of("error", "Origen no permitido.")); return;
                }
                if (!constantTimeEquals(token, req.header("X-Pulse-Token"))) {
                    req.json(401, Map.of("error", "Sesión no válida. Cierra y vuelve a abrir Pulse Studio.")); return;
                }
                Router.Handler h = router.match(req);
                if (h == null) { req.json(404, Map.of("error", "Ruta no encontrada.")); return; }
                h.handle(req);
                if (!req.responded()) req.json(200, Map.of("ok", true));
                return;
            }
            if (!"GET".equals(req.method()) && !"HEAD".equals(req.method())) { req.json(405, Map.of("error", "Método no permitido.")); return; }
            statics.serve(req);
        } catch (ApiException e) {
            respondError(req, e.status(), e.getMessage());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error en " + req.method() + " " + req.path(), e);
            respondError(req, 500, "Error interno de Pulse Studio: " + e.getClass().getSimpleName());
        } finally {
            ex.close();
        }
    }

    private static void respondError(Request req, int status, String message) {
        try { req.json(status, Map.of("error", message == null ? "Error" : message)); } catch (IOException ignored) { }
    }

    private static void addSecurityHeaders(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("Content-Security-Policy", CSP);
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        h.set("X-Frame-Options", "DENY");
        h.set("Cross-Origin-Opener-Policy", "same-origin");
        h.set("Cross-Origin-Resource-Policy", "same-origin");
        h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    }

    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
