package com.pulsestudio.desktop;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Abre la interfaz en una ventana de aplicación (sin pestañas ni barra de direcciones) usando el motor Chromium
 * que ya trae Windows: Microsoft Edge (o Google Chrome si Edge no está). Perfil propio en %LOCALAPPDATA%, así que
 * no mezcla historial, cookies ni extensiones con el navegador personal del usuario.
 *
 * ¿Por qué no JavaFX WebView? Su WebKit no tiene WebCodecs ni el WebGL2/WebAssembly que necesitan el editor de vídeo
 * (Mediabunny) y el recorte (MediaPipe). Con Edge en modo aplicación se conserva el 100 % de las funciones.
 */
final class WindowLauncher {
    private static final Logger LOG = Logger.getLogger("PulseStudio");
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    private WindowLauncher() {}

    /** Devuelve el proceso de la ventana, o null si se ha tenido que usar el navegador predeterminado. */
    static Process open(String url, Path profile) throws Exception {
        for (Path exe : candidates()) {
            if (!Files.isExecutable(exe)) continue;
            List<String> cmd = new ArrayList<>(List.of(exe.toString(), "--app=" + url, "--user-data-dir=" + profile,
                "--no-first-run", "--no-default-browser-check", "--disable-background-mode", "--window-size=1440,920",
                "--disable-features=Translate,msEdgeSidebarV2,msUndersideButton"));
            LOG.info("Abriendo ventana con " + exe);
            return new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        }
        LOG.warning("No se encontró Edge ni Chrome; se usa el navegador predeterminado.");
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) { Desktop.getDesktop().browse(URI.create(url)); return null; }
        throw new IllegalStateException("No hay ningún navegador compatible (Edge o Chrome).");
    }

    private static List<Path> candidates() {
        List<Path> c = new ArrayList<>();
        if (OS.contains("win")) {
            for (String env : new String[]{"ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA"}) {
                String base = System.getenv(env);
                if (base == null) continue;
                c.add(Paths.get(base, "Microsoft", "Edge", "Application", "msedge.exe"));
            }
            for (String env : new String[]{"ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"}) {
                String base = System.getenv(env);
                if (base == null) continue;
                c.add(Paths.get(base, "Google", "Chrome", "Application", "chrome.exe"));
            }
        } else if (OS.contains("mac")) {
            c.add(Paths.get("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"));
            c.add(Paths.get("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"));
            c.add(Paths.get("/Applications/Chromium.app/Contents/MacOS/Chromium"));
        } else {
            String path = System.getenv("PATH");
            for (String name : new String[]{"microsoft-edge", "microsoft-edge-stable", "google-chrome", "google-chrome-stable", "chromium", "chromium-browser"})
                if (path != null) for (String dir : path.split(File.pathSeparator)) c.add(Paths.get(dir, name));
        }
        return c;
    }
}
