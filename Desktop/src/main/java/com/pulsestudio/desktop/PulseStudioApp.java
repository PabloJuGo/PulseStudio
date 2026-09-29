package com.pulsestudio.desktop;

import com.pulsestudio.desktop.api.ContentController;
import com.pulsestudio.desktop.api.FilesController;
import com.pulsestudio.desktop.api.MediaController;
import com.pulsestudio.desktop.api.NewsController;
import com.pulsestudio.desktop.api.Services;
import com.pulsestudio.desktop.api.SettingsController;
import com.pulsestudio.desktop.api.SystemController;
import com.pulsestudio.desktop.server.Json;
import com.pulsestudio.desktop.server.LocalServer;
import com.pulsestudio.desktop.server.Router;
import com.pulsestudio.desktop.server.StaticFiles;
import com.pulsestudio.desktop.store.AppPaths;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Punto de entrada de PulseStudio.exe.
 *  1. Una sola instancia: si ya hay una abierta, solo se abre otra ventana contra su servidor.
 *  2. Arranca el backend REST en 127.0.0.1 (puerto aleatorio) con un token de sesión aleatorio.
 *  3. Abre la ventana de la aplicación (Edge/Chrome en modo app) con el token en el fragmento de la URL.
 *  4. Se cierra al cerrar la ventana (fin del proceso o falta de latidos de la interfaz).
 *
 * Opciones de desarrollo: --no-window, --port=N, --web=RUTA (sirve la interfaz desde disco), --data=RUTA,
 * --token=VALOR (solo pruebas automáticas).
 */
public final class PulseStudioApp {
    private static final Logger LOG = Logger.getLogger("PulseStudio");
    private static final long HEARTBEAT_TIMEOUT_MS = 45_000, STARTUP_GRACE_MS = 90_000;

    public static void main(String[] args) {
        try { run(args); }
        catch (Throwable t) {
            LOG.log(Level.SEVERE, "No se pudo iniciar Pulse Studio", t);
            if (!GraphicsEnvironment.isHeadless()) JOptionPane.showMessageDialog(null, "No se pudo iniciar Pulse Studio:\n" + t.getMessage(), "Pulse Studio", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    private static String arg(String[] args, String name) {
        for (String a : args) if (a.startsWith("--" + name + "=")) return a.substring(name.length() + 3);
        return null;
    }

    private static boolean flag(String[] args, String name) {
        for (String a : args) if (a.equals("--" + name)) return true;
        return false;
    }

    private static void run(String[] args) throws Exception {
        String data = arg(args, "data");
        AppPaths paths = new AppPaths(data == null ? null : Paths.get(data));
        setupLogging(paths);
        boolean noWindow = flag(args, "no-window");

        // 1. Instancia única.
        FileChannel lockChannel = FileChannel.open(paths.dataDir().resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = lockChannel.tryLock();
        if (lock == null) {
            Map<String, Object> running = Json.parseObject(Files.readString(paths.instanceFile(), StandardCharsets.UTF_8));
            LOG.info("Ya hay una instancia abierta; se abre otra ventana.");
            if (!noWindow) WindowLauncher.open(Json.str(running, "url") + "#t=" + Json.str(running, "token"), paths.browserProfile());
            return;
        }

        // 2. Backend.
        String token = arg(args, "token");
        if (token == null || token.length() < 16) {
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        }
        String web = arg(args, "web");
        String portArg = arg(args, "port");
        Services services = new Services(paths);
        Router router = new Router();
        LocalServer[] holder = new LocalServer[1];
        new SettingsController(services).register(router);
        new NewsController(services).register(router);
        new ContentController(services).register(router);
        new MediaController(services).register(router);
        new FilesController(services).register(router);
        new SystemController(holder).register(router);
        LocalServer server = new LocalServer(portArg == null ? 0 : Integer.parseInt(portArg), token, router,
            new StaticFiles(web == null ? null : Paths.get(web).toAbsolutePath().normalize()));
        holder[0] = server;
        server.start();
        Path instance = paths.instanceFile();
        Files.writeString(instance, Json.stringify(Json.map("url", server.url(), "token", token, "pid", ProcessHandle.current().pid())), StandardCharsets.UTF_8);
        AppPaths.restrictToOwner(instance);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            try { Files.deleteIfExists(instance); lock.release(); lockChannel.close(); } catch (IOException ignored) { }
        }));
        LOG.info("Pulse Studio escuchando en " + server.url());
        System.out.println("Pulse Studio: " + server.url() + (noWindow ? "#t=" + token : ""));

        // 3. Ventana y 4. ciclo de vida.
        if (noWindow) { Thread.currentThread().join(); return; } // desarrollo/pruebas: vivo hasta Ctrl+C
        Process window = WindowLauncher.open(server.url() + "#t=" + token, paths.browserProfile());
        long started = System.currentTimeMillis();
        if (window != null) {
            Thread waiter = new Thread(() -> {
                try {
                    window.waitFor();
                    // Si el proceso termina enseguida, Chromium ha delegado en una ventana ya abierta: manda el latido.
                    if (System.currentTimeMillis() - started > 5000) { LOG.info("Ventana cerrada."); System.exit(0); }
                } catch (InterruptedException ignored) { }
            }, "pulse-window");
            waiter.setDaemon(true);
            waiter.start();
        }
        var watchdog = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "pulse-watchdog"); t.setDaemon(true); return t; });
        watchdog.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            if (now - started > STARTUP_GRACE_MS && now - server.lastPing() > HEARTBEAT_TIMEOUT_MS) {
                LOG.info("La interfaz ya no está abierta; se cierra el backend.");
                System.exit(0);
            }
        }, 15, 15, TimeUnit.SECONDS);
        Thread.currentThread().join(); // el hilo principal espera; la salida la deciden la ventana o el watchdog
    }

    private static void setupLogging(AppPaths paths) {
        try {
            FileHandler fh = new FileHandler(paths.dataDir().resolve("pulse-studio.log").toString(), 2_000_000, 2, true);
            fh.setFormatter(new SimpleFormatter());
            fh.setEncoding("UTF-8");
            LOG.addHandler(fh);
        } catch (IOException ignored) { }
    }
}
