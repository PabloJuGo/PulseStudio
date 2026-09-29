package com.pulsestudio.app;

import java.io.*;

/** Bounded, ordered transfer to one private temporary file. Never interprets a path from JS. */
public final class ExportBuffer implements Closeable {
    /** Limit for text and images (script, pack JPEGs, thumbnail). */
    public static final long MAX_BYTES=64L*1024*1024;
    /** Limit for the video recorded in the in-app editor. */
    public static final long MAX_VIDEO_BYTES=256L*1024*1024;
    public static final int CHUNK_BYTES=48*1024;
    public final String name,mime;
    private final long expected;
    private final File file;
    private FileOutputStream output;
    private long written;
    private int next;
    public ExportBuffer(File directory,long size,String name,String mime) throws IOException {
        long max=maxBytes(name,mime);
        if(max<0 || size<1 || size>max) throw new IllegalArgumentException("Archivo no admitido o demasiado grande.");
        this.name=name; this.mime=mime; expected=size;
        file=File.createTempFile("pulse-export-",".tmp",directory);
        output=new FileOutputStream(file);
    }
    /** Maximum size for an allowed name/MIME pair, or -1 when the pair is not allowed. */
    public static long maxBytes(String name,String mime) {
        if(name==null || mime==null) return -1;
        String base="[a-zA-Z0-9_-]{1,100}";
        switch(mime) {
            case "text/plain": return name.matches(base+"\\.txt")?MAX_BYTES:-1;
            case "image/jpeg": return name.matches(base+"\\.(?:jpg|jpeg)")?MAX_BYTES:-1;
            case "image/png": return name.matches(base+"\\.png")?MAX_BYTES:-1;
            case "video/mp4": return name.matches(base+"\\.mp4")?MAX_VIDEO_BYTES:-1;
            case "video/webm": return name.matches(base+"\\.webm")?MAX_VIDEO_BYTES:-1;
            default: return -1;
        }
    }
    public boolean isVideo() { return mime.startsWith("video/"); }
    public boolean isImage() { return mime.startsWith("image/"); }
    public synchronized void append(int sequence,byte[] bytes) throws IOException {
        if(output==null || sequence!=next || bytes.length==0 || bytes.length>CHUNK_BYTES || written+bytes.length>expected)
            throw new IllegalArgumentException("Fragmento fuera de orden o tamaño incorrecto.");
        output.write(bytes); written+=bytes.length; next++;
    }
    public synchronized File finish() throws IOException {
        if(output==null || written!=expected) throw new IllegalStateException("Transferencia incompleta.");
        output.getFD().sync(); output.close(); output=null; return file;
    }
    @Override public synchronized void close() throws IOException {
        try { if(output!=null) output.close(); }
        finally { output=null; if(file.exists()&&!file.delete()) file.deleteOnExit(); }
    }
}
