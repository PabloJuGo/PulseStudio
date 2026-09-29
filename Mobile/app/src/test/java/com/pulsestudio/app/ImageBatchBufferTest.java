package com.pulsestudio.app;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class ImageBatchBufferTest {
    @Test public void receivesSixJpegsAndCleansUp() throws Exception {
        File dir=Files.createTempDirectory("pulse-images").toFile();
        String[] names={"imagen_1.jpg","imagen_2.jpg","imagen_3.jpg","imagen_4.jpg","imagen_5.jpg","imagen_6.jpg"};
        long[] sizes={3,3,3,3,3,3};
        ImageBatchBuffer batch=new ImageBatchBuffer(dir,"PulseStudio_20260926",names,sizes);
        for(int i=0;i<6;i++) batch.append(i,0,new byte[]{1,2,3});
        File[] files=batch.finish();
        assertEquals(6,files.length);
        for(File file:files) assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(file.toPath()));
        batch.close(); assertEquals(0,dir.list().length); assertTrue(dir.delete());
    }
    @Test public void rejectsInvalidCountFolderAndIndex() throws Exception {
        File dir=Files.createTempDirectory("pulse-images").toFile();
        try { new ImageBatchBuffer(dir,"../bad",new String[]{"a.jpg"},new long[]{3}); fail(); } catch(IllegalArgumentException expected) {}
        String[] names={"imagen_1.jpg","imagen_2.jpg","imagen_3.jpg","imagen_4.jpg","imagen_5.jpg","imagen_6.jpg"};
        long[] sizes={3,3,3,3,3,3};
        try(ImageBatchBuffer batch=new ImageBatchBuffer(dir,"PulseStudio_test",names,sizes)) {
            try { batch.append(6,0,new byte[]{1}); fail(); } catch(IllegalArgumentException expected) {}
        }
        assertEquals(0,dir.list().length); assertTrue(dir.delete());
    }
}
