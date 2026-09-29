package com.pulsestudio.desktop.store;

import com.pulsestudio.desktop.server.Json;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Claves de API cifradas en disco con AES-256-GCM (equivalente de escritorio al Android Keystore de la app móvil).
 * La clave AES es aleatoria y vive en un archivo aparte con ACL de solo-propietario dentro del perfil del usuario.
 * Las claves nunca se envían a la interfaz: el backend las usa directamente para llamar a las APIs.
 */
public final class SecretStore {
    public static final String[] SERVICES = {"groq", "pexels", "hf"};
    private static final byte[] AAD = "PulseStudioDesktop|credentials|v1".getBytes(StandardCharsets.UTF_8);
    private final AppPaths paths;
    private final SecureRandom random = new SecureRandom();

    public SecretStore(AppPaths paths) { this.paths = paths; }

    private synchronized byte[] key(boolean create) throws IOException {
        Path k = paths.keyFile();
        if (Files.exists(k)) {
            byte[] key = Files.readAllBytes(k);
            if (key.length != 32) throw new IOException("Clave local dañada.");
            return key;
        }
        if (!create) return null;
        byte[] key = new byte[32];
        random.nextBytes(key);
        writeAtomic(k, key);
        return key;
    }

    public synchronized Map<String, String> load() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String s : SERVICES) out.put(s, "");
        Path f = paths.secretsFile();
        byte[] key = key(false);
        if (!Files.exists(f) || key == null) return out;
        byte[] packed = Files.readAllBytes(f);
        if (packed.length < 30 || packed[0] != 1) throw new IOException("Almacén de claves incompatible.");
        byte[] clear = null;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, packed, 1, 12));
            c.updateAAD(AAD);
            clear = c.doFinal(packed, 13, packed.length - 13);
            Map<String, Object> m = Json.parseObject(new String(clear, StandardCharsets.UTF_8));
            for (String s : SERVICES) out.put(s, Json.str(m, s));
            return out;
        } catch (Exception e) {
            throw new IOException("No se pudo descifrar el almacén de claves. Bórralas y vuelve a configurarlas.", e);
        } finally {
            if (clear != null) Arrays.fill(clear, (byte) 0);
            Arrays.fill(key, (byte) 0);
        }
    }

    public synchronized void save(Map<String, String> keys) throws IOException {
        for (String v : keys.values()) {
            if (v.length() > 4096 || v.contains("\n") || v.contains("\r")) throw new IllegalArgumentException("Clave no válida.");
        }
        byte[] key = key(true);
        byte[] clear = Json.stringify(keys).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(AAD);
            byte[] enc = c.doFinal(clear);
            byte[] packed = new byte[1 + iv.length + enc.length];
            packed[0] = 1;
            System.arraycopy(iv, 0, packed, 1, iv.length);
            System.arraycopy(enc, 0, packed, 13, enc.length);
            writeAtomic(paths.secretsFile(), packed);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("No se pudo cifrar el almacén de claves.", e);
        } finally {
            Arrays.fill(clear, (byte) 0);
            Arrays.fill(key, (byte) 0);
        }
    }

    public synchronized void clear() throws IOException {
        Files.deleteIfExists(paths.secretsFile());
        Files.deleteIfExists(paths.keyFile());
    }

    static void writeAtomic(Path target, byte[] data) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), ".tmp-", ".part");
        AppPaths.restrictToOwner(tmp);
        Files.write(tmp, data);
        try { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
        AppPaths.restrictToOwner(target);
    }
}
