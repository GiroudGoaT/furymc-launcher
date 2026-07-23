package com.mcfaction.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds and runs the exact java invocation validated by hand for this project: LaunchWrapper +
 * FMLTweaker. There is no mod jar anywhere on disk - factionaddon's classes/assets are baked directly
 * into one of the ordinary library jars (see tools/assemble-bundle.ps1) at build time, so FML discovers
 * it the same way it discovers any classpath-resident mod (ModDiscoverer#findClasspathMods /
 * CoreModManager's per-jar manifest scan for FMLCorePlugin), with nothing extra for a player to find or
 * drop a file into.
 *
 * <p>
 * The classpath itself is built from an explicit manifest (installDir/libraries.txt, shipped inside
 * base.zip by tools/assemble-base-bundle.ps1) rather than by listing whatever's in libraries/ - listing
 * the directory would let anyone add an extra jar to libraries/ and have it silently picked up.
 *
 * <p>
 * Expects the bundle layout produced by tools/assemble-bundle.ps1 and tools/assemble-base-bundle.ps1:
 *
 * <pre>
 * installDir/
 *   jre8/bin/java.exe
 *   libraries/*.jar        (flat - every runtime dependency jar, Forge+MC jar included, mod content merged in)
 *   libraries.txt           (fixed, ordered list of the exact filenames above - the classpath source of truth)
 *   natives/                (Windows LWJGL/JInput natives)
 *   assets/                 (Mojang 1.7.10 assets: indexes/, objects/)
 *   instance/               (the actual game dir: config/, saves/, options.txt, ...)
 * </pre>
 */
public class GameLauncher {

    private static final String MAIN_CLASS = "net.minecraft.launchwrapper.Launch";
    private static final String TWEAK_CLASS = "cpw.mods.fml.common.launcher.FMLTweaker";
    private static final String MC_VERSION = "1.7.10";

    public Process launch(Path installDir, String username, String uuid, int ramMb) {
        Path javaExe = installDir.resolve("jre8/bin/java.exe");
        Path librariesDir = installDir.resolve("libraries");
        Path librariesManifest = installDir.resolve("libraries.txt");
        Path nativesDir = installDir.resolve("natives");
        Path assetsDir = installDir.resolve("assets");
        Path gameDir = installDir.resolve("instance");

        if (!Files.isExecutable(javaExe)) {
            throw new LauncherException("Bundled Java runtime not found at " + javaExe);
        }

        removeStaleModLayout(gameDir);

        List<String> command = new ArrayList<>();
        command.add(javaExe.toAbsolutePath()
            .toString());
        command.add("-Xmx" + ramMb + "M");
        command.add("-Djava.library.path=" + nativesDir.toAbsolutePath());
        command.add("-cp");
        command.add(buildClasspath(librariesDir, librariesManifest));
        command.add(MAIN_CLASS);
        command.add("--username");
        command.add(username);
        command.add("--uuid");
        command.add(uuid);
        command.add("--version");
        command.add(MC_VERSION);
        command.add("--gameDir");
        command.add(gameDir.toAbsolutePath()
            .toString());
        command.add("--assetsDir");
        command.add(assetsDir.toAbsolutePath()
            .toString());
        command.add("--assetIndex");
        command.add(MC_VERSION);
        command.add("--accessToken");
        command.add("0");
        command.add("--userProperties");
        command.add("{}");
        command.add("--tweakClass");
        command.add(TWEAK_CLASS);

        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(gameDir.toFile());
            builder.redirectOutput(gameDir.resolve("launcher_last_run.log")
                .toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            // FML re-creates an empty mods/ dir itself on every launch regardless of what we do (it
            // doesn't NPE without one, confirmed empirically, so we no longer pre-create it either) -
            // clean it back up once the game closes so there's nothing left on disk between sessions.
            process.onExit()
                .thenRun(() -> removeStaleModLayout(gameDir));
            return process;
        } catch (IOException e) {
            throw new LauncherException("Could not start the game process", e);
        }
    }

    /**
     * Builds the classpath from the fixed {@code libraries.txt} manifest instead of listing
     * {@code librariesDir}'s actual contents - listing the directory would let anyone drop an extra jar
     * in there and have it silently picked up, which is exactly what this bundle format avoids.
     */
    private String buildClasspath(Path librariesDir, Path librariesManifest) {
        if (!Files.isDirectory(librariesDir)) {
            throw new LauncherException("Libraries folder not found at " + librariesDir);
        }
        if (!Files.isRegularFile(librariesManifest)) {
            throw new LauncherException(
                "Libraries manifest not found at " + librariesManifest + " - the base bundle may be out of date");
        }

        List<String> names;
        try {
            names = Files.readAllLines(librariesManifest);
        } catch (IOException e) {
            throw new LauncherException("Could not read libraries manifest", e);
        }

        StringBuilder classpath = new StringBuilder();
        for (String name : names) {
            name = name.trim();
            if (name.isEmpty()) {
                continue;
            }
            Path jar = librariesDir.resolve(name);
            if (!Files.isRegularFile(jar)) {
                throw new LauncherException("Library jar listed in libraries.txt is missing: " + jar);
            }
            classpath.append(jar.toAbsolutePath())
                .append(File.pathSeparator);
        }
        if (classpath.length() == 0) {
            throw new LauncherException("libraries.txt is empty");
        }
        // Trailing separator is harmless - the JVM ignores an empty classpath entry.
        return classpath.toString();
    }

    /**
     * Deletes the old instance/mod (fixed factionaddon.jar) and instance/mods (kept empty for FML) dirs
     * left behind by installs from before the mod was merged into a library jar - see class comment.
     * Best-effort: a leftover empty dir isn't worth failing the launch over.
     */
    private void removeStaleModLayout(Path gameDir) {
        for (String stale : new String[] { "mod", "mods" }) {
            Path dir = gameDir.resolve(stale);
            try {
                if (Files.isDirectory(dir)) {
                    try (var walk = Files.walk(dir)) {
                        walk.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                    // Best-effort - see method javadoc.
                                }
                            });
                    }
                }
            } catch (IOException ignored) {
                // Best-effort - see method javadoc.
            }
        }
    }
}
