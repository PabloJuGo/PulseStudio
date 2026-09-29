package com.pulsestudio.desktop.api;

import com.pulsestudio.desktop.server.ApiException;
import com.pulsestudio.desktop.server.Json;
import com.pulsestudio.desktop.server.Request;
import com.pulsestudio.desktop.server.Router;
import com.pulsestudio.desktop.service.HuggingFaceService;

import java.util.Map;

/**
 * Miniatura y subtítulos: POST /api/thumbnail/brief (Groq), POST /api/thumbnail/background (Hugging Face) y
 * POST /api/transcribe (audio del vídeo → Whisper de Groq). La imagen y el vídeo se componen en la interfaz.
 */
public final class MediaController {
    private static final long MAX_AUDIO = 25L * 1024 * 1024;
    private final Services s;

    public MediaController(Services s) { this.s = s; }

    public void register(Router r) {
        r.post("/api/thumbnail/brief", this::brief);
        r.post("/api/thumbnail/background", this::background);
        r.post("/api/transcribe", this::transcribe);
    }

    private void brief(Request req) throws Exception {
        Map<String, Object> b = req.json();
        req.json(200, s.thumbnails.brief(s.key("groq"), s.settings.groqModel(), Json.obj(b, "news"), Json.str(b, "context")));
    }

    private void background(Request req) throws Exception {
        Map<String, Object> b = req.json();
        String model = Json.str(b, "model").isBlank() ? s.settings.hfModel() : Json.str(b, "model");
        HuggingFaceService.Image img = s.huggingFace.generate(s.key("hf"), model, Json.str(b, "prompt"));
        req.send(200, img.contentType(), img.bytes());
    }

    private void transcribe(Request req) throws Exception {
        String name = req.query("name");
        if (!name.matches("audio\\.(m4a|webm|mp3)")) throw ApiException.badRequest("Formato de audio no admitido.");
        String type = name.endsWith(".m4a") ? "audio/mp4" : name.endsWith(".mp3") ? "audio/mpeg" : "audio/webm";
        byte[] audio = req.body(MAX_AUDIO);
        if (audio.length < 100) throw ApiException.badRequest("El audio está vacío.");
        String lang = req.query("lang");
        if (!lang.isEmpty() && !lang.matches("[a-z]{2}")) throw ApiException.badRequest("Idioma no válido.");
        req.json(200, Json.map("words", s.groq.transcribe(s.key("groq"), audio, name, type, lang, req.query("context"))));
    }
}
