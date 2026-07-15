package com.mcfaction.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

/** Local launcher settings (username, install dir) - stored in %APPDATA%/FuryMcLauncher/launcher.properties.
 *  No real account/authentication: the server runs in offline mode, so a saved username is all that's
 *  needed, matching how the mod's own server is already configured. */
public class LauncherConfig {

    private static final String APP_FOLDER_NAME = "FuryMcLauncher";
    private static final String PROPERTIES_FILE = "launcher.properties";

    private final Path configDir;
    private final Path installDir;
    private final Properties properties = new Properties();

    public LauncherConfig() {
        String appData = System.getenv("APPDATA");
        Path base = appData != null ? Path.of(appData) : Path.of(System.getProperty("user.home"));
        configDir = base.resolve(APP_FOLDER_NAME);
        installDir = configDir.resolve("instance");
        load();
    }

    private void load() {
        Path file = configDir.resolve(PROPERTIES_FILE);
        if (!Files.exists(file)) {
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            // Corrupt/missing config just means defaults apply - not worth failing the launcher over.
        }
    }

    public void save() {
        try {
            Files.createDirectories(configDir);
            try (OutputStream out = Files.newOutputStream(configDir.resolve(PROPERTIES_FILE))) {
                properties.store(out, "FuryMc Launcher settings");
            }
        } catch (IOException e) {
            throw new LauncherException("Could not save launcher settings", e);
        }
    }

    public String getUsername() {
        return properties.getProperty("username", "");
    }

    public void setUsername(String username) {
        properties.setProperty("username", username);
    }

    /** Stable per-install offline UUID - generated once and reused, so the same player always gets the
     *  same UUID across launches (matters for playerdata/permissions on an offline-mode server). */
    public String getOrCreateUuid() {
        String uuid = properties.getProperty("uuid");
        if (uuid == null) {
            uuid = UUID.randomUUID()
                .toString()
                .replace("-", "");
            properties.setProperty("uuid", uuid);
        }
        return uuid;
    }

    public Path getInstallDir() {
        return installDir;
    }
}
