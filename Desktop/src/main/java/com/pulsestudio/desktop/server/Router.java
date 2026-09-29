package com.pulsestudio.desktop.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Enrutador REST mínimo: "GET /api/images/{id}" → manejador. */
public final class Router {
    @FunctionalInterface
    public interface Handler { void handle(Request req) throws Exception; }

    private record Route(String method, String[] parts, Handler handler) {}

    private final List<Route> routes = new ArrayList<>();

    public Router get(String path, Handler h) { return add("GET", path, h); }
    public Router post(String path, Handler h) { return add("POST", path, h); }
    public Router put(String path, Handler h) { return add("PUT", path, h); }
    public Router delete(String path, Handler h) { return add("DELETE", path, h); }

    private Router add(String method, String path, Handler h) {
        routes.add(new Route(method, split(path), h));
        return this;
    }

    /** Devuelve el manejador y rellena los parámetros de ruta; null si no existe; lanza 405 si solo falla el método. */
    Handler match(Request req) {
        String[] parts = split(req.path());
        boolean pathExists = false;
        for (Route r : routes) {
            Map<String, String> params = matchParts(r.parts, parts);
            if (params == null) continue;
            pathExists = true;
            if (!r.method.equals(req.method())) continue;
            req.params(params);
            return r.handler;
        }
        if (pathExists) throw new ApiException(405, "Método no permitido.");
        return null;
    }

    private static Map<String, String> matchParts(String[] pattern, String[] actual) {
        if (pattern.length != actual.length) return null;
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < pattern.length; i++) {
            String p = pattern[i];
            if (p.startsWith("{") && p.endsWith("}")) params.put(p.substring(1, p.length() - 1), actual[i]);
            else if (!p.equals(actual[i])) return null;
        }
        return params;
    }

    private static String[] split(String path) {
        String p = path.replaceAll("^/+|/+$", "");
        return p.isEmpty() ? new String[0] : p.split("/");
    }
}
