package com.pulsestudio.desktop.service;

import com.pulsestudio.desktop.net.Endpoints;
import com.pulsestudio.desktop.net.WebClient;
import com.pulsestudio.desktop.server.ApiException;
import com.pulsestudio.desktop.server.Json;
import com.pulsestudio.desktop.store.SettingsStore;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fondo de la miniatura con Hugging Face Inference Providers. Resuelve qué proveedor sirve el modelo en directo
 * (hf-inference, nscale o together, todos con respuesta síncrona) y usa el primero disponible.
 */
public final class HuggingFaceService {
    public record Image(byte[] bytes, String contentType) {}
    private static final List<String> PROVIDERS = List.of("hf-inference", "nscale", "together");
    private static final Map<String, Integer> STEPS = Map.of("black-forest-labs/FLUX.1-schnell", 4,
        "stabilityai/stable-diffusion-xl-base-1.0", 30, "stabilityai/stable-diffusion-3-medium-diffusers", 28);
    private record Provider(String name, String providerId) {}

    private final WebClient web;
    private final Endpoints ep;
    private final Map<String, List<Provider>> cache = new ConcurrentHashMap<>();

    public HuggingFaceService(WebClient web, Endpoints ep) { this.web = web; this.ep = ep; }

    public void test(String token) {
        requireToken(token);
        WebClient.Response r = web.get(ep.hfHub + "/api/whoami-v2", Map.of("Authorization", "Bearer " + token), 20, 1_000_000);
        if (!r.ok()) throw error(r.status(), r.errorMessage(), null);
    }

    private List<Provider> providers(String token, String model) {
        List<Provider> known = cache.get(model);
        if (known != null) return known;
        List<Provider> list = new ArrayList<>();
        WebClient.Response r;
        try { r = web.get(ep.hfHub + "/api/models/" + model + "?expand%5B%5D=inferenceProviderMapping", Map.of("Authorization", "Bearer " + token), 20, 2_000_000); }
        catch (ApiException e) { r = null; }
        if (r != null && (r.status() == 401 || r.status() == 403)) throw error(401, r.errorMessage(), null);
        if (r != null && r.ok()) {
            Object raw = r.json().get("inferenceProviderMapping");
            List<Map<String, Object>> entries = new ArrayList<>();
            if (raw instanceof Map<?, ?> m) m.forEach((k, v) -> { if (v instanceof Map<?, ?> vm) { @SuppressWarnings("unchecked") Map<String, Object> e = new java.util.LinkedHashMap<>((Map<String, Object>) vm); e.put("provider", k); entries.add(e); } });
            else if (raw instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> vm) { @SuppressWarnings("unchecked") Map<String, Object> e = (Map<String, Object>) vm; entries.add(e); }
            entries.stream().filter(e -> "text-to-image".equals(Json.str(e, "task")) && "live".equals(Json.str(e, "status")) && PROVIDERS.contains(Json.str(e, "provider")))
                .sorted((a, b) -> PROVIDERS.indexOf(Json.str(a, "provider")) - PROVIDERS.indexOf(Json.str(b, "provider")))
                .forEach(e -> list.add(new Provider(Json.str(e, "provider"), Json.str(e, "providerId").isEmpty() ? model : Json.str(e, "providerId"))));
        }
        if (list.isEmpty()) list.add(new Provider("hf-inference", model));
        cache.put(model, list);
        return list;
    }

    public Image generate(String token, String model, String prompt) {
        requireToken(token);
        if (!SettingsStore.HF_MODELS.contains(model)) throw ApiException.badRequest("Modelo de Hugging Face no admitido.");
        String p = Text.cap(prompt, 900);
        if (p.length() < 12) throw ApiException.badRequest("Escribe un prompt visual (en inglés) o pulsa «Crear miniatura».");
        int width = 768, height = 1344, steps = STEPS.getOrDefault(model, 28);
        ApiException last = null;
        for (Provider pr : providers(token, model)) {
            String url;
            Map<String, Object> body;
            switch (pr.name) {
                case "hf-inference" -> { url = ep.hfRouter + "/hf-inference/models/" + model; body = Json.map("inputs", p, "parameters", Json.map("width", width, "height", height, "num_inference_steps", steps)); }
                case "nscale" -> { url = ep.hfRouter + "/nscale/v1/images/generations"; body = Json.map("prompt", p, "model", pr.providerId, "response_format", "b64_json", "width", width, "height", height, "num_inference_steps", steps); }
                default -> { url = ep.hfRouter + "/together/v1/images/generations"; body = Json.map("prompt", p, "model", pr.providerId, "response_format", "base64", "width", width, "height", height, "steps", steps); }
            }
            try {
                WebClient.Response r = web.postJson(url, Map.of("Authorization", "Bearer " + token), body, 120, 40_000_000);
                if (!r.ok()) throw error(r.status(), r.errorMessage(), pr.name);
                if (r.contentType().contains("json")) {
                    List<Object> data = Json.list(r.json(), "data");
                    if (!data.isEmpty() && data.get(0) instanceof Map<?, ?> d && d.get("b64_json") != null) {
                        byte[] bytes = Base64.getMimeDecoder().decode(String.valueOf(d.get("b64_json")).replaceFirst("^data:[^,]+,", ""));
                        return new Image(bytes, sniff(bytes));
                    }
                    throw ApiException.upstream("Respuesta de imagen no reconocida (" + pr.name + ").");
                }
                if (r.body().length < 1000) throw ApiException.upstream("Imagen de Hugging Face vacía (" + pr.name + ").");
                return new Image(r.body(), sniff(r.body()));
            } catch (ApiException e) {
                last = e;
                if (e.status() == 401 || e.status() == 402) throw e;
            }
        }
        throw last != null ? last : ApiException.upstream("Ningún proveedor de Hugging Face ha generado la imagen.");
    }

    static String sniff(byte[] b) {
        if (b.length > 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P') return "image/png";
        if (b.length > 12 && b[8] == 'W' && b[9] == 'E') return "image/webp";
        return "image/jpeg";
    }

    private static ApiException error(int status, String detail, String provider) {
        String suffix = detail == null || detail.isBlank() ? "" : " (" + Text.cap(detail, 180) + ")";
        if (status == 401 || status == 403) return new ApiException(401, "Hugging Face rechaza el token. Crea uno con el permiso «Make calls to Inference Providers»." + suffix);
        if (status == 402) return new ApiException(402, "Se han agotado los créditos gratuitos de Hugging Face de este mes. Elige una imagen del pack como fondo o añade saldo a tu cuenta." + suffix);
        if (status == 429) return new ApiException(429, "Hugging Face ha limitado temporalmente las solicitudes (429). Espera un minuto y vuelve a intentarlo." + suffix);
        return ApiException.upstream("El proveedor " + (provider == null ? "de Hugging Face" : provider) + " no ha podido generar la imagen (HTTP " + status + ")." + suffix);
    }

    private static void requireToken(String token) {
        if (token == null || token.isBlank()) throw new ApiException(428, "Añade tu token de Hugging Face en Configuración o elige una imagen del pack como fondo.");
    }
}
