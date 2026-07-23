package com.mcfaction.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds and runs the exact java invocation validated by hand for this project: LaunchWrapper +
 * FMLTweaker. There is no mod jar anywhere on disk - factionaddon's classes/assets are baked directly
 * into one of the ordinary library jars (see tools/assemble-bundle.ps1) at build time, so FML discovers
 * it the same way it discovers any classpath-resident mod (ModDiscoverer#findClasspathMods /
 * CoreModManager's per-jar manifest scan for FMLCorePlugin), with nothing extra for a player to find or
 * drop a file into. FML's own "mods" directory is neutralized separately, at the bytecode level (see
 * tools/PatchCoreModManager.java) - it never touches installDir at all any more.
 *
 * <p>
 * The classpath itself is built from an explicit manifest (installDir/libraries.txt, shipped inside
 * base.zip by tools/assemble-base-bundle.ps1) rather than by listing whatever's in libraries/ - listing
 * the directory would let anyone add an extra jar to libraries/ and have it silently picked up.
 *
 * <p>
 * Expects the flat bundle layout produced by tools/assemble-bundle.ps1 and
 * tools/assemble-base-bundle.ps1 - deliberately a single directory, matching how comparable third-party
 * clients (Velthar, Vanadia) lay theirs out, with no "instance" wrapper folder to tell apart install
 * content from game content:
 *
 * <pre>
 * installDir/                (== the game dir passed to Minecraft directly - no separate nesting)
 *   jre8/bin/java.exe
 *   libraries/*.jar        (flat - every runtime dependency jar, Forge+MC jar included, mod content merged in)
 *   libraries.txt           (fixed, ordered list of the exact filenames above - the classpath source of truth)
 *   natives/                (Windows LWJGL/JInput natives)
 *   assets/                 (Mojang 1.7.10 assets: indexes/, objects/)
 *   config/, saves/, resourcepacks/, options.txt, ...  (ordinary game content, created/used by Minecraft itself)
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
        Path gameDir = installDir;

        if (!Files.isExecutable(javaExe)) {
            throw new LauncherException("Bundled Java runtime not found at " + javaExe);
        }

        cleanStaleLayout(installDir);

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
            // Deliberately no post-exit cleanup here: FuryMcLauncher closes its own window (and with it,
            // this JVM) right after the game process starts - a Process.onExit() callback registered here
            // would almost never actually run, since nothing keeps this process alive for the hours the
            // game itself might run. cleanStaleLayout() runs unconditionally at launcher startup instead
            // (see FuryMcLauncher.startUpdateSequence) - that's the reliable place for it.
            return builder.start();
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
     * Migrates any legacy nested game dir and removes any stale mod/mods folder. Safe to call every time
     * regardless of install state (both steps are no-ops once already clean) - called unconditionally at
     * launcher startup (see FuryMcLauncher.startUpdateSequence), not just around an actual game launch,
     * since that's the one place guaranteed to run every time the app opens.
     */
    public void cleanStaleLayout(Path installDir) {
        migrateLegacyNestedGameDir(installDir);
        removeStaleModLayout(installDir);
    }

    /**
     * One-time migration for installs from before the game dir was flattened into installDir directly:
     * moves everything out of the old installDir/instance/ (config/, saves/, resourcepacks/, options.txt,
     * ...) up into installDir itself, then removes the now-empty instance/ folder. Runs on every launch
     * but is a no-op once migrated (the old folder won't exist any more). Existing filenames win over the
     * migrated ones (shouldn't happen - the two layouts don't share names - but a player's world data is
     * not something to silently clobber if it somehow does).
     */
    private void migrateLegacyNestedGameDir(Path installDir) {
        Path legacyGameDir = installDir.resolve("instance");
        if (!Files.isDirectory(legacyGameDir)) {
            return;
        }
        boolean allMoved;
        try (var children = Files.list(legacyGameDir)) {
            allMoved = children.allMatch(child -> {
                Path target = installDir.resolve(child.getFileName());
                if (Files.exists(target)) {
                    // Shouldn't happen (the two layouts don't share names), but if it ever does, leave
                    // both the source and the pre-existing target alone rather than guessing which one
                    // to keep - this one child stays un-migrated and legacyGameDir stays around too.
                    return false;
                }
                try {
                    Files.move(child, target, StandardCopyOption.ATOMIC_MOVE);
                    return true;
                } catch (IOException e) {
                    return false;
                }
            });
        } catch (IOException ignored) {
            return;
        }
        // Only remove the legacy folder once everything's been moved out of it - if anything was
        // skipped or failed above, it's left in place (still fully playable via the legacy path on this
        // launch) instead of deleting data we didn't actually migrate.
        if (!allMoved) {
            return;
        }
        try {
            Files.delete(legacyGameDir);
        } catch (IOException ignored) {
            // Leftover empty dir here is harmless clutter, not worth failing the launch over.
        }
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
