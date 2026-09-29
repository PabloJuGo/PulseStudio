package com.pulsestudio.desktop.service;

import com.pulsestudio.desktop.server.ApiException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Caché temporal de JPEG del pack (se vacía al arrancar). Se sirven por id opaco, nunca por ruta. */
public final class ImageCache {
    public static final int W = 1080, H = 1920;
    private final Path dir;

    public ImageCache(Path cacheRoot) throws IOException {
        this.dir = cacheRoot.resolve("images");
        Files.createDirectories(dir);
        try (DirectoryStream<Path> old = Files.newDirectoryStream(dir, "*.jpg")) { for (Path p : old) Files.deleteIfExists(p); }
    }

    public String put(byte[] jpeg) throws IOException {
        String id = UUID.randomUUID().toString();
        Files.write(dir.resolve(id + ".jpg"), jpeg);
        return id;
    }

    public Path get(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw ApiException.notFound("Imagen no encontrada.");
        Path p = dir.resolve(id + ".jpg");
        if (!Files.isRegularFile(p)) throw ApiException.notFound("Imagen no encontrada.");
        return p;
    }

    /** Recorte «cover» a 1080 × 1920 y JPEG de calidad 0,88 (igual que la app móvil). */
    public static byte[] toVerticalJpeg(byte[] source) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(source));
        if (img == null) throw ApiException.upstream("No se puede decodificar la fotografía descargada.");
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(new Color(0x10, 0x1d, 0x2e));
            g.fillRect(0, 0, W, H);
            double scale = Math.max((double) W / img.getWidth(), (double) H / img.getHeight());
            int w = (int) Math.round(img.getWidth() * scale), h = (int) Math.round(img.getHeight() * scale);
            g.drawImage(img, (W - w) / 2, (H - h) / 2, w, h, null);
        } finally { g.dispose(); }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(ios);
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(0.88f);
            writer.write(null, new IIOImage(out, null, null), p);
        } finally { writer.dispose(); }
        return bytes.toByteArray();
    }
}
