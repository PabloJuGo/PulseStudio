package com.pulsestudio.app;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Run only on a disposable test installation: this test deletes that app's saved keys. */
public class SecretStoreInstrumentedTest {
    private Context getContext() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private File cipherFile() { return new File(getContext().getNoBackupFilesDir(),"credentials.aesgcm"); }
    private byte[] bytes() throws Exception {
        try(FileInputStream in=new FileInputStream(cipherFile()); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[1024]; int n; while((n=in.read(buffer))!=-1)out.write(buffer,0,n); return out.toByteArray();
        }
    }
    @Test public void testCiphertextRoundTripFreshIvAndDeletion() throws Exception {
        SecretStore store=new SecretStore(getContext());store.clear();
        try {
            store.save("dummy-groq-instrumentation","dummy-pexels-instrumentation");
            byte[] first=bytes(); assertFalse(new String(first,StandardCharsets.ISO_8859_1).contains("dummy-groq"));
            assertEquals("dummy-groq-instrumentation",new SecretStore(getContext()).load().getString("groq"));
            store.save("dummy-groq-instrumentation","dummy-pexels-instrumentation");assertFalse(Arrays.equals(first,bytes()));
            store.save("dummy-groq-instrumentation","dummy-pexels-instrumentation","hf_dummy-instrumentation");
            assertFalse(new String(bytes(),StandardCharsets.ISO_8859_1).contains("hf_dummy"));
            assertEquals("hf_dummy-instrumentation",new SecretStore(getContext()).load().getString("hf"));
            store.clear();assertFalse(cipherFile().exists());assertEquals("",store.load().getString("groq"));assertEquals("",store.load().getString("hf"));
        } finally { store.clear(); }
    }
    @Test public void testTamperingRejected() throws Exception {
        SecretStore store=new SecretStore(getContext());store.clear();
        try {
            store.save("dummy","dummy2");byte[] packed=bytes();packed[packed.length-1]^=1;
            try(FileOutputStream out=new FileOutputStream(cipherFile())){out.write(packed);}
            try { store.load();fail("Tampered GCM ciphertext accepted"); } catch(javax.crypto.AEADBadTagException expected) {}
        } finally { store.clear(); }
    }
}
