package com.pulsestudio.desktop.store;

import com.pulsestudio.desktop.server.ApiException;
import com.pulsestudio.desktop.service.FileService;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class StoresTest {
    @Test public void secretsAreEncryptedAndTamperIsDetected() throws Exception {
        AppPaths paths = new AppPaths(Files.createTempDirectory("pulse-data"));
        SecretStore store = new SecretStore(paths);
        Map<String, String> keys = new LinkedHashMap<>(Map.of("groq", "gsk_secret_value", "pexels", "px", "hf", "hf_token"));
        store.save(keys);
        assertFalse(new String(Files.readAllBytes(paths.secretsFile()), StandardCharsets.ISO_8859_1).contains("gsk_secret"));
        assertEquals("hf_token", new SecretStore(paths).load().get("hf"));
        byte[] packed = Files.readAllBytes(paths.secretsFile());
        packed[packed.length - 1] ^= 1;
        Files.write(paths.secretsFile(), packed);
        try { store.load(); fail(); } catch (java.io.IOException expected) { }
        store.clear();
        assertEquals("", store.load().get("groq"));
    }

    @Test public void filesAreSavedInsideOutputFolderWithSafeNames() throws Exception {
        Path data = Files.createTempDirectory("pulse-data"), out = Files.createTempDirectory("pulse-out");
        SettingsStore settings = new SettingsStore(new AppPaths(data));
        settings.update(null, null, out.toString());
        FileService files = new FileService(settings);
        Path a = files.save("PulseStudio_1", "guion_tiktok.txt", new ByteArrayInputStream("hola".getBytes()), 4);
        Path b = files.save("PulseStudio_1", "guion_tiktok.txt", new ByteArrayInputStream("adios".getBytes()), 5);
        assertEquals(out.resolve("PulseStudio_1/guion_tiktok.txt"), a);
        assertEquals("guion_tiktok (2).txt", b.getFileName().toString());
        for (String[] bad : new String[][]{{"..", "a.txt"}, {"x", "../a.txt"}, {"x", "a.exe"}, {"x/y", "a.txt"}, {"x", "a.txt\n"}}) {
            try { files.save(bad[0], bad[1], new ByteArrayInputStream(new byte[]{1}), 1); fail(bad[0] + bad[1]); } catch (ApiException expected) { }
        }
        assertEquals(a.getParent(), files.inside(a.getParent().toString()));
        try { files.inside(data.toString()); fail(); } catch (ApiException e) { assertEquals(403, e.status()); }
        try { settings.update("modelo-inventado", null, null); fail(); } catch (IllegalArgumentException expected) { }
    }
}
