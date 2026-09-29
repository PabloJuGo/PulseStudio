package com.pulsestudio.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

/** API secrets are ciphertext at rest; the AES key is non-exportable via AndroidKeyStore. */
final class SecretStore {
    private static final String ALIAS="pulse.studio.credentials.v1";
    private static final byte[] AAD="PulseStudio|credentials|v1".getBytes(StandardCharsets.UTF_8);
    private final AtomicFile file;
    SecretStore(Context context) { file=new AtomicFile(new File(context.getNoBackupFilesDir(),"credentials.aesgcm")); }
    private SecretKey key(boolean create) throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if(ks.containsAlias(ALIAS)) return (SecretKey)ks.getKey(ALIAS,null);
        if(!create) throw new IOException("Keystore no disponible; elimina y vuelve a guardar las claves.");
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
    synchronized JSONObject load() throws Exception {
        if(!file.getBaseFile().exists()) return new JSONObject().put("groq","").put("pexels","").put("hf","");
        if(file.getBaseFile().length()>20000) throw new IOException("Almacén inválido.");
        byte[] packed=file.readFully();
        if(packed.length<30 || packed[0]!=1) throw new IOException("Almacén incompatible.");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(false),new GCMParameterSpec(128,Arrays.copyOfRange(packed,1,13)));
        cipher.updateAAD(AAD);
        byte[] clear=cipher.doFinal(packed,13,packed.length-13);
        try {
            JSONObject keys=new JSONObject(new String(clear,StandardCharsets.UTF_8));
            // Stores written by v1.5.x only contain Groq and Pexels.
            if(!keys.has("hf")) keys.put("hf","");
            return keys;
        }
        finally { Arrays.fill(clear,(byte)0); }
    }
    private static void check(String key) {
        if(key.length()>4096 || key.contains("\n") || key.contains("\r")) throw new IllegalArgumentException("Clave no válida.");
    }
    synchronized void save(String groq,String pexels) throws Exception { save(groq,pexels,""); }
    synchronized void save(String groq,String pexels,String hf) throws Exception {
        check(groq); check(pexels); check(hf);
        byte[] clear=new JSONObject().put("groq",groq).put("pexels",pexels).put("hf",hf).toString().getBytes(StandardCharsets.UTF_8);
        FileOutputStream stream=null;
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key(true)); cipher.updateAAD(AAD);
            byte[] encrypted=cipher.doFinal(clear),iv=cipher.getIV();
            if(iv.length!=12) throw new IOException("IV incompatible.");
            stream=file.startWrite(); stream.write(1); stream.write(iv); stream.write(encrypted); file.finishWrite(stream); stream=null;
        } finally { Arrays.fill(clear,(byte)0); if(stream!=null) file.failWrite(stream); }
    }
    synchronized void clear() throws Exception {
        file.delete();
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null); ks.deleteEntry(ALIAS);
        if(file.getBaseFile().exists()) throw new IOException("No se pudo eliminar el almacén.");
    }
}
