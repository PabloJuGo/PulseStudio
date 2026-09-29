package com.pulsestudio.desktop.server;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Envoltorio de {@link HttpExchange} con utilidades para leer la petición y responder. */
public final class Request {
    public static final long MAX_JSON_BYTES = 4L * 1024 * 1024;
    private final HttpExchange ex;
    private final Map<String, String> query;
    private final Map<String, String> params = new LinkedHashMap<>();
    private boolean responded;

    Request(HttpExchange ex) {
        this.ex = ex;
        this.query = parseQuery(ex.getRequestURI().getRawQuery());
    }

    public HttpExchange exchange() { return ex; }
    public String method() { return ex.getRequestMethod(); }
    public String path() { return ex.getRequestURI().getPath(); }
    public String header(String name) { String v = ex.getRequestHeaders().getFirst(name); return v == null ? "" : v; }
    public String query(String name) { return query.getOrDefault(name, ""); }
    public String param(String name) { return params.getOrDefault(name, ""); }
    void params(Map<String, String> p) { params.putAll(p); }
    boolean responded() { return responded; }

    /** Cuerpo completo con límite de tamaño (para JSON y audio). */
    public byte[] body(long max) throws IOException {
        long declared = contentLength();
        if (declared > max) throw new ApiException(413, "La petición es demasiado grande.");
        try (InputStream in = ex.getRequestBody(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[65536];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > max) throw new ApiException(413, "La petición es demasiado grande.");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    public InputStream bodyStream() { return ex.getRequestBody(); }

    public long contentLength() {
        try { return Long.parseLong(header("Content-Length")); } catch (NumberFormatException e) { return -1; }
    }

    public Map<String, Object> json() throws IOException {
        String text = new String(body(MAX_JSON_BYTES), StandardCharsets.UTF_8);
        if (text.isBlank()) return new LinkedHashMap<>();
        try { return Json.parseObject(text); } catch (IllegalArgumentException e) { throw ApiException.badRequest("JSON no válido: " + e.getMessage()); }
    }

    public void json(int status, Object value) throws IOException {
        send(status, "application/json; charset=utf-8", Json.stringify(value).getBytes(StandardCharsets.UTF_8));
    }

    public void send(int status, String contentType, byte[] bytes) throws IOException {
        if (responded) return;
        responded = true;
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
        else ex.close();
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq), v = eq < 0 ? "" : part.substring(eq + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }
}
