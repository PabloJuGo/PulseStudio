package com.pulsestudio.desktop.api;

import com.pulsestudio.desktop.server.ApiException;
import com.pulsestudio.desktop.server.Json;
import com.pulsestudio.desktop.server.Request;
import com.pulsestudio.desktop.server.Router;
import com.pulsestudio.desktop.store.SecretStore;
import com.pulsestudio.desktop.store.SettingsStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /api/settings — configuración. Las claves se reciben pero NUNCA se devuelven: la interfaz solo sabe si cada
 * servicio está configurado y los 4 últimos caracteres para reconocerla.
 */
public final class SettingsController {
    private final Services s;

    public SettingsController(Services s) { this.s = s; }

    public void register(Router r) {
        r.get("/api/settings", this::get);
        r.put("/api/settings", this::put);
        r.delete("/api/settings/keys", req -> { s.secrets.clear(); get(req); });
        r.post("/api/settings/test/{service}", this::test);
        r.post("/api/settings/choose-folder", this::chooseFolder);
    }

    private void get(Request req) throws Exception {
        Map<String, String> keys = s.secrets.load();
        Map<String, Object> services = new LinkedHashMap<>();
        for (String name : SecretStore.SERVICES) {
            String k = keys.getOrDefault(name, "");
            services.put(name, Json.map("configured", !k.isEmpty(), "hint", k.length() >= 8 ? "…" + k.substring(k.length() - 4) : ""));
        }
        req.json(200, Json.map("services", services, "groqModel", s.settings.groqModel(), "hfModel", s.settings.hfModel(),
            "outputDir", s.settings.outputDir().toString(), "groqModels", SettingsStore.GROQ_MODELS, "hfModels", SettingsStore.HF_MODELS));
    }

    private void put(Request req) throws Exception {
        Map<String, Object> body = req.json();
        Map<String, Object> incoming = Json.obj(body, "keys");
        List<Object> clear = Json.list(body, "clear");
        Map<String, String> keys = s.secrets.load();
        boolean changed = false;
        for (String name : SecretStore.SERVICES) {
            String v = Json.str(incoming, name).trim();
            if (!v.isEmpty()) { keys.put(name, v); changed = true; }
            if (clear.contains(name)) { keys.put(name, ""); changed = true; }
        }
        try {
            if (changed) s.secrets.save(keys);
            s.settings.update(Json.str(body, "groqModel"), Json.str(body, "hfModel"), Json.str(body, "outputDir"));
        } catch (IllegalArgumentException e) { throw ApiException.badRequest(e.getMessage()); }
        get(req);
    }

    /** Prueba con la clave escrita (si la hay) o con la guardada. */
    private void test(Request req) throws Exception {
        String service = req.param("service");
        String typed = Json.str(req.json(), "key").trim();
        String key = typed.isEmpty() ? s.key(service) : typed;
        switch (service) {
            case "groq" -> s.groq.test(key);
            case "pexels" -> s.pexels.test(key);
            case "hf" -> s.huggingFace.test(key);
            default -> throw ApiException.notFound("Servicio desconocido.");
        }
        req.json(200, Json.map("ok", true));
    }

    private void chooseFolder(Request req) throws Exception {
        var chosen = s.system.chooseFolder(s.settings.outputDir());
        if (chosen != null) s.settings.update(null, null, chosen.toString());
        get(req);
    }
}
