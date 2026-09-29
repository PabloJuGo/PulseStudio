package com.pulsestudio.app;

import java.io.*;

/** Receives exactly six JPEG files in bounded, ordered chunks before one folder selection. */
public final class ImageBatchBuffer implements Closeable {
    public static final int IMAGE_COUNT=6;
    public final String folderName;
    private final ExportBuffer[] files;

    public ImageBatchBuffer(File directory,String folderName,String[] names,long[] sizes) throws IOException {
        if(folderName==null || !folderName.matches("[a-zA-Z0-9_-]{1,80}"))
            throw new IllegalArgumentException("Nombre de carpeta no admitido.");
        if(names==null || sizes==null || names.length!=IMAGE_COUNT || sizes.length!=IMAGE_COUNT)
            throw new IllegalArgumentException("Se requieren seis imágenes.");
        this.folderName=folderName;
        files=new ExportBuffer[IMAGE_COUNT];
        try {
            long total=0;
            for(int i=0;i<IMAGE_COUNT;i++) {
                total+=sizes[i];
                if(total>ExportBuffer.MAX_BYTES) throw new IllegalArgumentException("El lote supera 64 MiB.");
                files[i]=new ExportBuffer(directory,sizes[i],names[i],"image/jpeg");
            }
        } catch(Exception error) {
            try { close(); } catch(IOException ignored) {}
            if(error instanceof IOException) throw (IOException)error;
            throw error;
        }
    }

    public synchronized void append(int fileIndex,int sequence,byte[] bytes) throws IOException {
        if(fileIndex<0 || fileIndex>=files.length) throw new IllegalArgumentException("Índice de imagen inválido.");
        files[fileIndex].append(sequence,bytes);
    }

    public synchronized File[] finish() throws IOException {
        File[] ready=new File[files.length];
        for(int i=0;i<files.length;i++) ready[i]=files[i].finish();
        return ready;
    }

    public String nameAt(int index) { return files[index].name; }

    @Override public synchronized void close() throws IOException {
        IOException first=null;
        for(ExportBuffer file:files) if(file!=null) try { file.close(); } catch(IOException e) { if(first==null) first=e; }
        if(first!=null) throw first;
    }
}
