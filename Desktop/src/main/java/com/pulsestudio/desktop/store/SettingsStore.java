package com.pulsestudio.desktop.store;

import com.pulsestudio.desktop.server.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/** Preferencias no secretas (modelos y carpeta de salida) en settings.json. */
public final class SettingsStore {
    public static final List<String> GROQ_MODELS = List.of("openai/gpt-oss-20b", "openai/gpt-oss-120b");
    public static final List<String> HF_MODELS = List.of("black-forest-labs/FLUX.1-schnell",
        "stabilityai/stable-diffusion-xl-base-1.0", "stabilityai/stable-diffusion-3-medium-diffusers");
    private final AppPaths paths;
    private String groqModel = GROQ_MODELS.get(0);
    private String hfModel = HF_MODELS.get(0);
    private Path outputDir = AppPaths.defaultOutputDir();

    public SettingsStore(AppPaths paths) {
        this.paths = paths;
        try {
            if (Files.exists(paths.settingsFile())) {
                Map<String, Object> m = Json.parseObject(Files.readString(paths.settingsFile(), StandardCharsets.UTF_8));
                if (GROQ_MODELS.contains(Json.str(m, "groqModel"))) groqModel = Json.str(m, "groqModel");
                if (HF_MODELS.contains(Json.str(m, "hfModel"))) hfModel = Json.str(m, "hfModel");
                String out = Json.str(m, "outputDir");
                if (!out.isBlank()) outputDir = Paths.get(out);
            }
        } catch (Exception ignored) { /* ajustes dañados: valores por defecto */ }
    }

    public synchronized String groqModel() { return groqModel; }
    public synchronized String hfModel() { return hfModel; }
    public synchronized Path outputDir() { return outputDir; }

    public synchronized void update(String groq, String hf, String out) throws IOException {
        if (groq != null && !groq.isBlank()) {
            if (!GROQ_MODELS.contains(groq)) throw new IllegalArgumentException("Modelo de Groq no admitido.");
            groqModel = groq;
        }
        if (hf != null && !hf.isBlank()) {
            if (!HF_MODELS.contains(hf)) throw new IllegalArgumentException("Modelo de Hugging Face no admitido.");
            hfModel = hf;
        }
        if (out != null && !out.isBlank()) {
            Path p = Paths.get(out).toAbsolutePath().normalize();
            Files.createDirectories(p);
            if (!Files.isWritable(p)) throw new IllegalArgumentException("No se puede escribir en la carpeta elegida.");
            outputDir = p;
        }
        SecretStore.writeAtomic(paths.settingsFile(), Json.stringify(Json.map("groqModel", groqModel, "hfModel", hfModel,
            "outputDir", outputDir.toString())).getBytes(StandardCharsets.UTF_8));
    }
}
