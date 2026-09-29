package com.pulsestudio.app;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class ExportBufferTest {
    @Test public void roundTripAndCleanup() throws Exception {
        File dir=Files.createTempDirectory("pulse-test").toFile();
        ExportBuffer b=new ExportBuffer(dir, 5, "guion.txt", "text/plain");
        b.append(0, new byte[]{1,2}); b.append(1,new byte[]{3,4,5});
        File f=b.finish(); assertArrayEquals(new byte[]{1,2,3,4,5},Files.readAllBytes(f.toPath()));
        b.close(); assertFalse(f.exists()); assertTrue(dir.delete());
    }
    @Test public void rejectsOutOfOrderOverflowAndIncomplete() throws Exception {
        File dir=Files.createTempDirectory("pulse-test").toFile();
        try(ExportBuffer b=new ExportBuffer(dir, 3, "imagen_1.jpg", "image/jpeg")) {
            try { b.append(1,new byte[]{1}); fail(); } catch(IllegalArgumentException expected) {}
            try { b.append(0,new byte[]{1,2,3,4}); fail(); } catch(IllegalArgumentException expected) {}
            b.append(0,new byte[]{1});
            try { b.finish(); fail(); } catch(IllegalStateException expected) {}
        }
        assertEquals(0,dir.list().length); assertTrue(dir.delete());
    }
    @Test public void rejectsUnsafeNamesMimeAndSize() throws Exception {
        File dir=Files.createTempDirectory("pulse-test").toFile();
        for(String name:new String[]{"../secret.jpg","a/b.jpg","pack.html","a.jpg\n"}) {
            try { new ExportBuffer(dir,2,name,"image/jpeg"); fail(name); } catch(IllegalArgumentException expected) {}
        }
        try { new ExportBuffer(dir,ExportBuffer.MAX_BYTES+1,"a.jpg","image/jpeg"); fail(); } catch(IllegalArgumentException expected) {}
        try { new ExportBuffer(dir,2,"a.jpg","text/html"); fail(); } catch(IllegalArgumentException expected) {}
        try(ExportBuffer jpeg=new ExportBuffer(dir,2,"imagen_1.jpg","image/jpeg")) { jpeg.append(0,new byte[]{1,2}); jpeg.finish(); }
        assertEquals(0,dir.list().length); assertTrue(dir.delete());
    }
    @Test public void acceptsThumbnailAndVideoWithTheirOwnLimits() throws Exception {
        File dir=Files.createTempDirectory("pulse-test").toFile();
        assertEquals(ExportBuffer.MAX_BYTES,ExportBuffer.maxBytes("miniatura_tiktok.png","image/png"));
        assertEquals(ExportBuffer.MAX_VIDEO_BYTES,ExportBuffer.maxBytes("pulse_reel_20260928.mp4","video/mp4"));
        assertEquals(ExportBuffer.MAX_VIDEO_BYTES,ExportBuffer.maxBytes("pulse_reel.webm","video/webm"));
        for(String[] bad:new String[][]{{"reel.webm","video/mp4"},{"reel.mp4","video/webm"},{"a.png","image/jpeg"},{"../reel.mp4","video/mp4"},{"reel.mov","video/quicktime"},{"x.svg","image/svg+xml"}})
            assertEquals(bad[0],-1,ExportBuffer.maxBytes(bad[0],bad[1]));
        try(ExportBuffer video=new ExportBuffer(dir,ExportBuffer.MAX_BYTES+1,"pulse_reel.webm","video/webm")) { assertTrue(video.isVideo()); assertFalse(video.isImage()); }
        try { new ExportBuffer(dir,ExportBuffer.MAX_VIDEO_BYTES+1,"pulse_reel.mp4","video/mp4"); fail(); } catch(IllegalArgumentException expected) {}
        try { new ExportBuffer(dir,ExportBuffer.MAX_BYTES+1,"miniatura.png","image/png"); fail(); } catch(IllegalArgumentException expected) {}
        assertEquals(0,dir.list().length); assertTrue(dir.delete());
    }
}
