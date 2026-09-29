package com.pulsestudio.desktop.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

/** Rutas de datos de la aplicación: %LOCALAPPDATA%\PulseStudio en Windows, ~/.pulsestudio en el resto. */
public final class AppPaths {
    private final Path dataDir;

    public AppPaths(Path override) throws IOException {
        Path base;
        if (override != null) base = override;
        else {
            String local = System.getenv("LOCALAPPDATA");
            base = local != null && !local.isBlank() ? Paths.get(local, "PulseStudio") : Paths.get(System.getProperty("user.home"), ".pulsestudio");
        }
        this.dataDir = base.toAbsolutePath().normalize();
        Files.createDirectories(dataDir);
        restrictToOwner(dataDir);
    }

    public Path dataDir() { return dataDir; }
    public Path settingsFile() { return dataDir.resolve("settings.json"); }
    public Path secretsFile() { return dataDir.resolve("credentials.aesgcm"); }
    public Path keyFile() { return dataDir.resolve("credentials.key"); }
    public Path browserProfile() { return dataDir.resolve("window-profile"); }
    public Path instanceFile() { return dataDir.resolve("instance.json"); }
    public Path cacheDir() throws IOException { Path p = dataDir.resolve("cache"); Files.createDirectories(p); return p; }

    /** Carpeta de salida por defecto: Documentos\PulseStudio (o ~/PulseStudio). */
    public static Path defaultOutputDir() {
        Path home = Paths.get(System.getProperty("user.home"));
        Path docs = home.resolve("Documents");
        return (Files.isDirectory(docs) ? docs : home).resolve("PulseStudio");
    }

    /** Solo el usuario actual puede leer/escribir (ACL en Windows, permisos POSIX 700/600 en el resto). */
    public static void restrictToOwner(Path p) {
        try {
            AclFileAttributeView acl = Files.getFileAttributeView(p, AclFileAttributeView.class);
            if (acl != null) {
                UserPrincipal owner = Files.getOwner(p);
                AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
                acl.setAcl(List.of(entry));
                return;
            }
            boolean dir = Files.isDirectory(p);
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(dir ? "rwx------" : "rw-------"));
        } catch (Exception ignored) {
            // Sistemas de archivos sin ACL/POSIX (p. ej. FAT): se mantiene el permiso por defecto.
        }
    }
}
