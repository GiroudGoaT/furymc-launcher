package com.mcfaction.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Entry point for the native-image "single exe" distribution. Deliberately the only class in its own
 * dependency graph - unlike FuryMcLauncher, it never touches javax.swing/java.awt, so its own
 * native-image build needs none of the AWT native libraries (awt.dll, jawt.dll, etc.) that make the
 * Swing app's build need a companion folder of DLLs. That's what lets THIS exe be the one truly
 * standalone file a player downloads and keeps: on first run it seeds %LocalAppData%\FuryMc\bin\ from
 * a copy of the real Swing app embedded in its own resources (no network wait before the player sees
 * a window - see {@link #seedFromEmbeddedBundle}), then checks the manifest and re-downloads into that
 * same cache whenever a newer version is published, launches it, and exits.
 *
 * <p>
 * Why this exists instead of running the Swing app directly from a self-extracting wrapper (the
 * previous approach, via IExpress): a native-image exe launched from inside such a wrapper only knows
 * the throwaway temp folder it was extracted into, never its own original downloaded file - so it has
 * no reliable "self" to replace the way SelfUpdater does for the jar-based distributions. This stub
 * sits one layer above that problem: IT is the permanent file the player keeps, and it manages its
 * own cache of the (frequently-updated) real app instead of trying to update itself in place.
 *
 * <p>
 * Deliberately minimal and expected to change rarely, if ever - it has no update mechanism of its
 * own (see PROJECT_STATUS.md for why that's an accepted tradeoff, not an oversight).
 */
public final class Stub {

    // Kept as its own copy rather than shared with FuryMcLauncher's identical constant - pulling in
    // any part of that class here would drag Swing into this exe's native-image build.
    private static final String MANIFEST_URL = "https://raw.githubusercontent.com/GiroudGoaT/furymc-launcher/main/version.json";
    private static final String APP_EXE_NAME = "FuryMc.exe";

    // A copy of the app bundle (FuryMc.exe + app/ + runtime/, same zip published as
    // FuryMc-Launcher-native.zip) embedded into this exe's own resources at build time - see
    // native-build's stub packaging step. EMBEDDED_BUNDLE_VERSION must be bumped to match whatever
    // launcherVersion that embedded copy actually is whenever the stub gets rebuilt with a fresh
    // bundle; it's written out as the cached app's version marker so the normal update check below
    // treats the seeded copy as current unless the manifest has since moved past it.
    private static final String EMBEDDED_BUNDLE_RESOURCE = "/native-app-bundle.zip";
    private static final String EMBEDDED_BUNDLE_VERSION = "1.4.5";

    public static void main(String[] args) {
        try {
            Path cacheDir = resolveCacheDir();
            Files.createDirectories(cacheDir);
            Path appExe = cacheDir.resolve(APP_EXE_NAME);

            if (!Files.isRegularFile(appExe)) {
                // First run (or a cleared cache): seed instantly from the bundle embedded in this exe
                // instead of making the player stare at nothing for ~30s while ~70MB downloads over
                // the network before any window can appear.
                seedFromEmbeddedBundle(cacheDir);
            }

            UpdateManager updateManager = new UpdateManager();
            VersionManifest manifest;
            try {
                manifest = updateManager.fetchManifest(MANIFEST_URL);
            } catch (RuntimeException e) {
                // Can't even reach the manifest (offline, GitHub hiccup) - if a cached copy already
                // works, that's still strictly better than refusing to launch at all.
                if (Files.isRegularFile(appExe)) {
                    launch(cacheDir, appExe, args);
                    return;
                }
                throw e;
            }

            boolean missing = !Files.isRegularFile(appExe);
            boolean outdated = manifest.getLauncherNativeZipUrl() != null
                && updateManager.needsNativeAppUpdate(cacheDir, manifest);

            if (missing || outdated) {
                if (manifest.getLauncherNativeZipUrl() == null) {
                    throw new LauncherException(
                        "No cached app found and the update manifest has no launcherNativeZipUrl yet");
                }
                try {
                    updateManager.downloadAndInstallNativeApp(
                        cacheDir,
                        manifest,
                        (percent, status) -> System.out.println(status));
                } catch (RuntimeException e) {
                    // A bad/broken update (corrupt download, wrong package layout, transient network
                    // failure...) must never brick an already-working install - see the v1.4.3 incident,
                    // where this exact gap turned one bad release into "the launcher doesn't open at all"
                    // for every player, with no way to fall back. Only escalate if there's truly nothing
                    // to launch.
                    if (Files.isRegularFile(appExe)) {
                        System.err.println("Mise à jour échouée, on garde la version déjà installée : " + e);
                    } else {
                        throw e;
                    }
                }
            }

            launch(cacheDir, appExe, args);
        } catch (Exception e) {
            System.err.println("FuryMc Launcher n'a pas pu démarrer : " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Extracts the app bundle embedded in this exe's own resources straight to the cache dir - no
     * network involved, so a brand new player sees the real window in a couple of seconds instead of
     * waiting through a silent ~70MB download with zero feedback. Writes
     * {@link #EMBEDDED_BUNDLE_VERSION} out as the cached app's version marker so the ordinary update
     * check right after this call in {@code main} correctly treats it as current (and skips a
     * redundant re-download) unless the manifest has since moved past it.
     */
    private static void seedFromEmbeddedBundle(Path cacheDir) throws IOException {
        // Materialized to a real file first rather than read straight off the resource stream via
        // ZipInputStream: ZipInputStream determines each entry's name/directory-ness from the local
        // file header as it streams past, and PowerShell's Compress-Archive (used to build this bundle)
        // writes directory entries with backslash separators, which desyncs that streaming read
        // partway through - java.util.zip.ZipFile reads the (authoritative) central directory instead,
        // same as UpdateManager#extractZip already relies on for the equivalent network-downloaded copy.
        Path tempZip = Files.createTempFile(cacheDir, "embedded-bundle-", ".zip");
        try {
            try (InputStream in = Stub.class.getResourceAsStream(EMBEDDED_BUNDLE_RESOURCE)) {
                if (in == null) {
                    // A dev build with no embedded bundle - fall through and let the normal network
                    // download path in main() handle it instead.
                    return;
                }
                Files.copy(in, tempZip, StandardCopyOption.REPLACE_EXISTING);
            }
            try (ZipFile zip = new ZipFile(tempZip.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName()
                        .replace('\\', '/');
                    Path outPath = cacheDir.resolve(name)
                        .normalize();
                    if (!outPath.startsWith(cacheDir)) {
                        throw new LauncherException("Embedded bundle contains an invalid path: " + name);
                    }
                    if (name.endsWith("/")) {
                        Files.createDirectories(outPath);
                    } else {
                        Files.createDirectories(outPath.getParent());
                        try (InputStream entryIn = zip.getInputStream(entry)) {
                            Files.copy(entryIn, outPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }
        } finally {
            Files.deleteIfExists(tempZip);
        }
        Files.writeString(cacheDir.resolve("launcher-native-version.txt"), EMBEDDED_BUNDLE_VERSION);
    }

    private static Path resolveCacheDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = localAppData != null ? Path.of(localAppData) : Path.of(System.getProperty("user.home"));
        return base.resolve("FuryMc")
            .resolve("bin");
    }

    private static void launch(Path cacheDir, Path appExe, String[] args) throws IOException {
        if (!Files.isExecutable(appExe)) {
            throw new LauncherException("Cached app not found or not executable at " + appExe);
        }
        List<String> command = new ArrayList<>();
        command.add(appExe.toAbsolutePath()
            .toString());
        command.addAll(Arrays.asList(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cacheDir.toFile());
        builder.inheritIO();
        builder.start();
    }
}
