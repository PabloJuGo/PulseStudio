package com.pulsestudio.desktop.service;

import com.pulsestudio.desktop.net.Endpoints;
import com.pulsestudio.desktop.net.WebClient;
import com.pulsestudio.desktop.server.ApiException;
import com.pulsestudio.desktop.server.Json;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fotografías verticales de Pexels convertidas a JPEG 1080 × 1920 y guardadas en la caché local. */
public final class PexelsService {
    private final WebClient web;
    private final Endpoints ep;
    private final ImageCache cache;

    public PexelsService(WebClient web, Endpoints ep, ImageCache cache) { this.web = web; this.ep = ep; this.cache = cache; }

    public void test(String key) {
        requireKey(key);
        WebClient.Response r = web.get(ep.pexels + "/v1/search?query=technology&orientation=portrait&per_page=1", Map.of("Authorization", key), 20, 2_000_000);
        if (!r.ok()) throw ApiException.upstream("Pexels: HTTP " + r.status() + ": " + r.errorMessage());
    }

    /** Busca una foto nueva (excluyendo las ya usadas) y la deja lista en la caché. */
    public Map<String, Object> photo(String key, String query, List<Object> exclude) throws Exception {
        requireKey(key);
        String term = Text.cap(query, 95);
        if (term.isEmpty()) throw ApiException.badRequest("Falta la búsqueda de la imagen.");
        Set<String> used = new HashSet<>();
        for (Object o : exclude) used.add(String.valueOf(o).replaceAll("\\.0$", ""));
        WebClient.Response r = web.get(ep.pexels + "/v1/search?query=" + Text.pct(term) + "&orientation=portrait&per_page=15", Map.of("Authorization", key), 35, 4_000_000);
        if (!r.ok()) throw ApiException.upstream("Pexels: HTTP " + r.status() + ": " + r.errorMessage());
        List<Map<String, Object>> photos = new ArrayList<>();
        for (Object o : Json.list(r.json(), "photos")) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) raw;
            Map<String, Object> src = Json.obj(p, "src");
            String id = Json.str(p, "id").replaceAll("\\.0$", "");
            if (used.contains(id) || (Json.str(src, "large2x").isEmpty() && Json.str(src, "large").isEmpty())) continue;
            photos.add(p);
        }
        if (photos.isEmpty()) throw ApiException.upstream("Pexels no ha devuelto fotografías nuevas para «" + term + "». Prueba con otra noticia o palabra clave.");
        RuntimeException last = null;
        for (Map<String, Object> p : photos.subList(0, Math.min(3, photos.size()))) {
            Map<String, Object> src = Json.obj(p, "src");
            String url = !Json.str(src, "large2x").isEmpty() ? Json.str(src, "large2x") : Json.str(src, "large");
            try {
                String safe = Text.safeHttp(url);
                if (safe == null || !URI.create(safe).getAuthority().equals(URI.create(ep.pexelsImages).getAuthority()))
                    throw ApiException.upstream("El proveedor ha devuelto una imagen de un dominio no admitido.");
                WebClient.Response img = web.get(safe, Map.of("Accept", "image/jpeg,image/png;q=0.9"), 35, 20_000_000);
                if (!img.ok() || img.body().length < 1000) throw ApiException.upstream("No se puede descargar la foto de Pexels (HTTP " + img.status() + ").");
                String imageId = cache.put(ImageCache.toVerticalJpeg(img.body()));
                return Json.map("id", imageId, "pexelsId", Json.str(p, "id").replaceAll("\\.0$", ""),
                    "photographer", Json.str(p, "photographer").isEmpty() ? "Autor no indicado" : Json.str(p, "photographer"),
                    "photographerUrl", Text.safeHttp(Json.str(p, "photographer_url")), "photoUrl", Text.safeHttp(Json.str(p, "url")),
                    "query", term, "url", "/api/images/" + imageId);
            } catch (RuntimeException e) { last = e; }
        }
        throw last != null ? last : ApiException.upstream("No se ha podido obtener una fotografía JPEG.");
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) throw new ApiException(428, "Introduce tu clave de Pexels en Configuración (⚙, arriba a la derecha).");
    }
}
