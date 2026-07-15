package com.mcfaction.launcher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

/**
 * Self-updates the launcher application itself - separate from UpdateManager, which updates game
 * content (mod/base). jpackage's app-image puts a thin native stub at "FuryMc Launcher.exe" that just
 * launches whatever's in app/furymc-launcher.jar (see build.gradle.kts's packageExe task and
 * app/FuryMc Launcher.cfg) - the exe itself never needs to change, so this only ever swaps that jar.
 * That means changes to the launcher's own code (this class included) reach players the same way mod
 * updates do: relaunch, no manual reinstall.
 *
 * <p>
 * Windows won't let a running process's own jar be overwritten in place, so this writes a tiny batch
 * script that waits for this process to exit, replaces the jar, and relaunches the app - then this
 * process exits immediately so the file unlocks.
 */
public class SelfUpdater {

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    /**
     * @return true if an update was found and a relaunch was kicked off - the caller should exit
     *         immediately without doing anything else. false if already up to date, the manifest has
     *         no launcher-update info, or self-update isn't possible (e.g. running from an IDE/plain
     *         jar outside a jpackage app-image) - in all of those cases the caller should proceed
     *         normally.
     */
    public boolean checkAndApply(String currentVersion, VersionManifest manifest) {
        String remoteVersion = manifest.getLauncherVersion();
        if (remoteVersion == null || remoteVersion.equals(currentVersion) || manifest.getLauncherJarUrl() == null) {
            return false;
        }
        Path currentJar = currentJarPath();
        if (currentJar == null) {
            return false;
        }
        try {
            Path newJar = Files.createTempFile("furymc-launcher-", ".jar");
            downloadTo(manifest.getLauncherJarUrl(), newJar);
            if (manifest.getLauncherJarSha256() != null && !manifest.getLauncherJarSha256()
                .isBlank()) {
                verifyChecksum(newJar, manifest.getLauncherJarSha256());
            }
            launchSwapScript(currentJar, newJar);
            return true;
        } catch (Exception e) {
            // Self-update is a nice-to-have on top of the normal game-content update path - if it
            // fails for any reason, just carry on with the current launcher version instead of
            // blocking the player from playing.
            return false;
        }
    }

    private Path currentJarPath() {
        try {
            Path path = Path.of(
                SelfUpdater.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            return path.toString()
                .endsWith(".jar") ? path : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void downloadTo(String url, Path target) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .GET()
            .build();
        HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() != 200) {
            throw new LauncherException("Launcher self-update download failed (HTTP " + response.statusCode() + ")");
        }
    }

    private void verifyChecksum(Path file, String expectedSha256) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(file));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            if (!hex.toString()
                .equalsIgnoreCase(expectedSha256)) {
                throw new LauncherException("Downloaded launcher update is corrupt (checksum mismatch)");
            }
        } catch (NoSuchAlgorithmException e) {
            throw new LauncherException("SHA-256 unavailable on this JVM", e);
        }
    }

    /**
     * Writes and launches a detached helper: retry-copy the new jar over the current one until the
     * still-shutting-down JVM releases its lock, relaunch the app's own exe (one directory up from
     * app/, per the jpackage app-image layout), then delete the temp files behind itself.
     */
    private void launchSwapScript(Path currentJar, Path newJar) throws IOException {
        Path appDir = currentJar.getParent();
        Path exe = appDir.getParent()
            .resolve("FuryMc Launcher.exe");
        Path script = Files.createTempFile("furymc-launcher-update-", ".bat");

        String content = "@echo off\r\n" + ":wait\r\n" + "copy /y \"" + newJar + "\" \"" + currentJar
            + "\" >nul 2>&1\r\n" + "if errorlevel 1 (\r\n" + "  timeout /t 1 /nobreak >nul\r\n" + "  goto wait\r\n"
            + ")\r\n" + "start \"\" \"" + exe + "\"\r\n" + "del \"" + newJar + "\"\r\n" + "del \"%~f0\"\r\n";
        Files.writeString(script, content);

        ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/c", script.toString());
        builder.directory(appDir.toFile());
        try {
            builder.start();
        } catch (IOException e) {
            throw new LauncherException("Could not start the launcher update helper", e);
        }
    }
}
