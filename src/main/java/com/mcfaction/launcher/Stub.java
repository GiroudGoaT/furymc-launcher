package com.mcfaction.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Entry point for the native-image "single exe" distribution. Deliberately the only class in its own
 * dependency graph - unlike FuryMcLauncher, it never touches javax.swing/java.awt, so its own
 * native-image build needs none of the AWT native libraries (awt.dll, jawt.dll, etc.) that make the
 * Swing app's build need a companion folder of DLLs. That's what lets THIS exe be the one truly
 * standalone file a player downloads and keeps: it checks the manifest, downloads/caches a fresh copy
 * of the real Swing app into %LocalAppData%\FuryMc\bin\ if the cached one is out of date, launches
 * it, and exits.
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
    private static final String APP_EXE_NAME = "FuryMc-Launcher.exe";

    public static void main(String[] args) {
        try {
            Path cacheDir = resolveCacheDir();
            Files.createDirectories(cacheDir);

            UpdateManager updateManager = new UpdateManager();
            VersionManifest manifest = updateManager.fetchManifest(MANIFEST_URL);

            Path appExe = cacheDir.resolve(APP_EXE_NAME);
            boolean missing = !Files.isRegularFile(appExe);
            boolean outdated = manifest.getLauncherNativeZipUrl() != null
                && updateManager.needsNativeAppUpdate(cacheDir, manifest);

            if (missing || outdated) {
                if (manifest.getLauncherNativeZipUrl() == null) {
                    throw new LauncherException(
                        "No cached app found and the update manifest has no launcherNativeZipUrl yet");
                }
                updateManager.downloadAndInstallNativeApp(
                    cacheDir,
                    manifest,
                    (percent, status) -> System.out.println(status));
            }

            launch(cacheDir, appExe, args);
        } catch (Exception e) {
            System.err.println("FuryMc Launcher n'a pas pu démarrer : " + e);
            e.printStackTrace();
            System.exit(1);
        }
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
