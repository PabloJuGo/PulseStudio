package com.pulsestudio.desktop.service;

import com.pulsestudio.desktop.server.ApiException;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/** Integración con el sistema operativo: Explorador de archivos, navegador y selector de carpetas nativo. */
public final class SystemService {
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** Abre la carpeta (o muestra el archivo seleccionado en el Explorador de Windows). */
    public void reveal(Path p) throws Exception {
        if (!Files.exists(p)) throw ApiException.notFound("La ruta ya no existe.");
        if (WINDOWS) {
            if (Files.isDirectory(p)) new ProcessBuilder("explorer.exe", p.toString()).start();
            else new ProcessBuilder("explorer.exe", "/select,", p.toString()).start();
            return;
        }
        if (!Desktop.isDesktopSupported()) throw new ApiException(501, "El sistema no permite abrir carpetas desde la aplicación.");
        Desktop.getDesktop().open(Files.isDirectory(p) ? p.toFile() : p.getParent().toFile());
    }

    /** Enlaces externos (fuente de la noticia, Pexels, consolas de API) en el navegador predeterminado. */
    public void openUrl(String raw) throws Exception {
        URI u;
        try { u = new URI(raw); } catch (Exception e) { throw ApiException.badRequest("Enlace no válido."); }
        String host = u.getHost() == null ? "" : u.getHost();
        if (!("https".equals(u.getScheme()) || "http".equals(u.getScheme())) || host.isEmpty() || u.getRawUserInfo() != null
            || host.equals("127.0.0.1") || host.equals("localhost")) throw ApiException.badRequest("Enlace externo bloqueado.");
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) { Desktop.getDesktop().browse(u); return; }
        if (WINDOWS) { new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", u.toString()).start(); return; }
        throw new ApiException(501, "No hay navegador disponible.");
    }

    /** Selector de carpeta nativo (Swing). Devuelve null si el usuario cancela. */
    public Path chooseFolder(Path current) throws Exception {
        if (GraphicsEnvironment.isHeadless()) throw new ApiException(501, "Selector de carpetas no disponible en este entorno.");
        AtomicReference<Path> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
            JFrame owner = new JFrame("Pulse Studio");
            owner.setUndecorated(true);
            owner.setAlwaysOnTop(true);
            owner.setLocationRelativeTo(null);
            owner.setVisible(true);
            try {
                JFileChooser chooser = new JFileChooser(current == null ? null : current.toFile());
                chooser.setDialogTitle("Carpeta donde guardar lo que exporta Pulse Studio");
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                chooser.setAcceptAllFileFilterUsed(false);
                if (chooser.showDialog(owner, "Elegir carpeta") == JFileChooser.APPROVE_OPTION) result.set(chooser.getSelectedFile().toPath());
            } finally { owner.dispose(); }
        });
        return result.get();
    }
}
