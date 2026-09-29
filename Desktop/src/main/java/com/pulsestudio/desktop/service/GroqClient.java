package com.pulsestudio.desktop.service;

import com.pulsestudio.desktop.net.Endpoints;
import com.pulsestudio.desktop.net.WebClient;
import com.pulsestudio.desktop.server.ApiException;
import com.pulsestudio.desktop.server.Json;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Cliente de Groq: chat con salida JSON estricta y transcripción Whisper. La clave nunca sale del backend. */
public final class GroqClient {
    private final WebClient web;
    private final Endpoints ep;

    public GroqClient(WebClient web, Endpoints ep) { this.web = web; this.ep = ep; }

    /** Devuelve el objeto JSON generado según el esquema (response_format json_schema strict). */
    public Map<String, Object> chatJson(String key, String model, String system, String prompt, String schemaName,
                                        Map<String, Object> schema, double temperature, int maxTokens) {
        requireKey(key);
        Map<String, Object> body = Json.map("model", model,
            "messages", List.of(Json.map("role", "system", "content", system), Json.map("role", "user", "content", prompt)),
            "temperature", temperature, "max_completion_tokens", maxTokens, "stream", false,
            "response_format", Json.map("type", "json_schema", "json_schema", Json.map("name", schemaName, "strict", true, "schema", schema)));
        WebClient.Response r = web.postJson(ep.groq + "/openai/v1/chat/completions", Map.of("Authorization", "Bearer " + key), body, 90, 4_000_000);
        if (!r.ok()) throw mapError(r.status(), r.errorMessage());
        Map<String, Object> choice = firstChoice(r.json());
        String content = Json.str(Json.obj(choice, "message"), "content").trim();
        if (content.isEmpty()) throw ApiException.upstream("Groq no ha devuelto contenido. Comprueba el modelo y los límites de salida.");
        if ("length".equals(Json.str(choice, "finish_reason"))) throw ApiException.upstream("Groq cortó la respuesta antes de terminar el JSON. Vuelve a intentarlo.");
        content = content.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        try { return Json.parseObject(content); }
        catch (IllegalArgumentException e) { throw ApiException.upstream("Groq no ha devuelto JSON válido. Inténtalo de nuevo."); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstChoice(Map<String, Object> payload) {
        List<Object> choices = Json.list(payload, "choices");
        if (choices.isEmpty() || !(choices.get(0) instanceof Map)) throw ApiException.upstream("Groq no ha devuelto ninguna respuesta.");
        return (Map<String, Object>) choices.get(0);
    }

    static ApiException mapError(int status, String message) {
        String m = "HTTP " + status + ": " + message;
        if (status == 429 || m.toLowerCase().contains("rate limit")) return new ApiException(429, "Groq ha alcanzado temporalmente el límite de solicitudes (429). Espera y vuelve a intentarlo. " + m);
        if (status == 401 || status == 403) return new ApiException(401, "Groq no autoriza la solicitud. Comprueba la API key, los permisos y el modelo. " + m);
        if (status == 404 || m.contains("model_not_found")) return ApiException.upstream("Modelo de Groq no disponible. Selecciona otro modelo en Configuración. " + m);
        if (status == 413) return new ApiException(413, "El archivo es demasiado grande para Groq (máx. 25 MB en el plan gratuito). " + m);
        return ApiException.upstream("Groq: " + m);
    }

    public void test(String key) {
        requireKey(key);
        WebClient.Response r = web.get(ep.groq + "/openai/v1/models", Map.of("Authorization", "Bearer " + key), 20, 2_000_000);
        if (!r.ok()) throw mapError(r.status(), r.errorMessage());
    }

    /** Whisper con tiempos por palabra. Devuelve [{word,start,end}] (si faltan, se reparten por frase). */
    public List<Object> transcribe(String key, byte[] audio, String fileName, String contentType, String language, String prompt) {
        requireKey(key);
        String boundary = "----PulseStudio" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream(audio.length + 4096);
        field(out, boundary, "model", "whisper-large-v3-turbo");
        field(out, boundary, "response_format", "verbose_json");
        field(out, boundary, "timestamp_granularities[]", "word");
        field(out, boundary, "timestamp_granularities[]", "segment");
        field(out, boundary, "temperature", "0");
        if (language != null && !language.isBlank()) field(out, boundary, "language", language);
        if (prompt != null && !prompt.isBlank()) field(out, boundary, "prompt", Text.cap(prompt, 500));
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\nContent-Type: " + contentType + "\r\n\r\n");
        out.writeBytes(audio);
        write(out, "\r\n--" + boundary + "--\r\n");
        WebClient.Response r = web.post(ep.groq + "/openai/v1/audio/transcriptions", Map.of("Authorization", "Bearer " + key),
            "multipart/form-data; boundary=" + boundary, out.toByteArray(), 180, 20_000_000);
        if (!r.ok()) throw mapError(r.status(), r.errorMessage());
        Map<String, Object> data = r.json();
        List<Object> words = new ArrayList<>();
        for (Object o : Json.list(data, "words")) {
            if (!(o instanceof Map<?, ?> w)) continue;
            String text = String.valueOf(w.get("word") == null ? "" : w.get("word")).trim();
            double start = Json.num(w.get("start"), Double.NaN), end = Json.num(w.get("end"), Double.NaN);
            if (!text.isEmpty() && !Double.isNaN(start)) words.add(Json.map("word", text, "start", start, "end", Double.isNaN(end) ? start : end));
        }
        if (words.isEmpty()) for (Object o : Json.list(data, "segments")) {
            if (!(o instanceof Map<?, ?> sg)) continue;
            String[] list = String.valueOf(sg.get("text") == null ? "" : sg.get("text")).trim().split("\\s+");
            double s0 = Json.num(sg.get("start"), 0), s1 = Json.num(sg.get("end"), s0), d = (s1 - s0) / Math.max(1, list.length);
            for (int i = 0; i < list.length; i++) if (!list[i].isEmpty()) words.add(Json.map("word", list[i], "start", s0 + d * i, "end", s0 + d * (i + 1)));
        }
        if (words.isEmpty()) throw ApiException.upstream("No se ha reconocido voz en el vídeo.");
        return words;
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) {
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }

    private static void write(ByteArrayOutputStream out, String s) { out.writeBytes(s.getBytes(StandardCharsets.UTF_8)); }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) throw new ApiException(428, "Introduce tu clave de Groq en Configuración (⚙, arriba a la derecha).");
    }
}
