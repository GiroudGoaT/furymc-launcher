package com.mcfaction.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks a remote version.json (hosted alongside the bundle releases on GitHub) against the locally
 * installed version, and downloads+extracts a fresh bundle when they differ. The bundle itself is a
 * single zip containing everything needed to run the modded client standalone (Forge+MC jar, libraries,
 * natives, assets, Java 8 runtime, and the mod jar pre-placed in mods/) - see the launcher project's
 * README for how that zip is assembled.
 */
public class UpdateManager {

    private static final String VERSION_FILE_NAME = "version.txt";
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    public VersionManifest fetchManifest(String manifestUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(manifestUrl))
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new LauncherException("Could not reach the update server (HTTP " + response.statusCode() + ")");
            }
            return VersionManifest.parse(response.body());
        } catch (IOException | InterruptedException e) {
            throw new LauncherException("Could not check for updates - check your internet connection", e);
        }
    }

    public String getLocalVersion(Path installDir) {
        Path versionFile = installDir.resolve(VERSION_FILE_NAME);
        if (!Files.exists(versionFile)) {
            return null;
        }
        try {
            return Files.readString(versionFile, StandardCharsets.UTF_8)
                .trim();
        } catch (IOException e) {
            return null;
        }
    }

    public boolean needsUpdate(Path installDir, VersionManifest remote) {
        String local = getLocalVersion(installDir);
        return local == null || !local.equals(remote.getVersion());
    }

    public void downloadAndInstall(Path installDir, VersionManifest remote, ProgressListener listener) {
        try {
            Files.createDirectories(installDir);
            Path downloadTarget = installDir.resolveSibling("download.zip");

            listener.onProgress(0, "Téléchargement de la mise à jour...");
            downloadWithProgress(remote.getBundleUrl(), downloadTarget, listener);

            if (remote.getSha256() != null && !remote.getSha256()
                .isBlank()) {
                listener.onProgress(-1, "Vérification du fichier...");
                verifyChecksum(downloadTarget, remote.getSha256());
            }

            listener.onProgress(-1, "Installation...");
            extractZip(downloadTarget, installDir, listener);

            Files.writeString(installDir.resolve(VERSION_FILE_NAME), remote.getVersion());
            Files.deleteIfExists(downloadTarget);
        } catch (IOException e) {
            throw new LauncherException("Échec de la mise à jour", e);
        }
    }

    private void downloadWithProgress(String url, Path target, ProgressListener listener) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .build();
            HttpResponse<InputStream> response = httpClient
                .send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new LauncherException("Le téléchargement a échoué (HTTP " + response.statusCode() + ")");
            }
            long total = response.headers()
                .firstValueAsLong("Content-Length")
                .orElse(-1);

            try (InputStream in = response.body()) {
                Files.createDirectories(target.getParent());
                try (var out = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[1 << 16];
                    long downloaded = 0;
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        downloaded += read;
                        if (total > 0) {
                            listener.onProgress(
                                (int) (downloaded * 100 / total),
                                "Téléchargement... " + (downloaded / 1_000_000) + " / " + (total / 1_000_000) + " Mo");
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            throw new LauncherException("Téléchargement interrompu", e);
        }
    }

    private void verifyChecksum(Path file, String expectedSha256) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            if (!hex.toString()
                .equalsIgnoreCase(expectedSha256)) {
                throw new LauncherException("Le fichier téléchargé est corrompu (somme de contrôle invalide)");
            }
        } catch (NoSuchAlgorithmException e) {
            throw new LauncherException("SHA-256 unavailable on this JVM", e);
        }
    }

    private void extractZip(Path zipFile, Path destDir, ProgressListener listener) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            int total = zip.size();
            int count = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                // PowerShell's Compress-Archive writes entry names with backslashes on Windows, which
                // ZipEntry#isDirectory() doesn't recognize (it only checks for a trailing '/') - normalize
                // before doing anything else so directory entries are actually treated as directories.
                String entryName = entry.getName()
                    .replace('\\', '/');
                Path outPath = destDir.resolve(entryName)
                    .normalize();
                if (!outPath.startsWith(destDir)) {
                    throw new LauncherException("Bundle contains an invalid path: " + entryName);
                }
                if (entryName.endsWith("/")) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, outPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                count++;
                listener.onProgress(count * 100 / Math.max(1, total), "Installation... " + count + "/" + total);
            }
        }
    }
}
