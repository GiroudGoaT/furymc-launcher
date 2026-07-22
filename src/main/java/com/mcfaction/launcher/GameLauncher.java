package com.mcfaction.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds and runs the exact java invocation validated by hand for this project: LaunchWrapper +
 * FMLTweaker, with the mod jar placed directly on the classpath (never in a mods/ folder). FML 1.7.10
 * discovers classpath-resident mods on its own (ModDiscoverer#findClasspathMods) with no ASM patching
 * needed - confirmed empirically, a real client successfully connected and joined a real server this
 * way. mods/ is still created (FML throws a NullPointerException in CoreModManager if it doesn't exist
 * when scanning starts) but is deliberately left empty and never referenced otherwise: there is no
 * folder here a player can drop an extra jar into and have it loaded by this launcher.
 *
 * <p>
 * The classpath itself is built from an explicit manifest (installDir/libraries.txt, shipped inside
 * base.zip by tools/assemble-base-bundle.ps1) rather than by listing whatever's in libraries/ - listing
 * the directory would just move the same "drop a file in a folder, get it loaded" problem from mods/ to
 * libraries/.
 *
 * <p>
 * Expects the bundle layout produced by tools/assemble-bundle.ps1 and tools/assemble-base-bundle.ps1:
 *
 * <pre>
 * installDir/
 *   jre8/bin/java.exe
 *   libraries/*.jar        (flat - every runtime dependency jar, Forge+MC jar included)
 *   libraries.txt           (fixed, ordered list of the exact filenames above - the classpath source of truth)
 *   natives/                (Windows LWJGL/JInput natives)
 *   assets/                 (Mojang 1.7.10 assets: indexes/, objects/)
 *   instance/               (the actual game dir: config/, saves/, options.txt, ...)
 *     mod/factionaddon.jar  (the mod jar - fixed filename, on the classpath, never in mods/)
 *     mods/                 (kept empty - see class comment)
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
        Path modsDir = gameDir.resolve("mods");
        Path modJar = gameDir.resolve("mod/factionaddon.jar");

        if (!Files.isExecutable(javaExe)) {
            throw new LauncherException("Bundled Java runtime not found at " + javaExe);
        }
        if (!Files.isRegularFile(modJar)) {
            throw new LauncherException("Mod jar not found at " + modJar + " - the update may not have installed correctly");
        }

        try {
            // FML throws a NullPointerException in CoreModManager if this doesn't already exist when
            // launched - see class comment. Deliberately left empty otherwise.
            Files.createDirectories(modsDir);
        } catch (IOException e) {
            throw new LauncherException("Could not prepare the game directory", e);
        }

        List<String> command = new ArrayList<>();
        command.add(javaExe.toAbsolutePath()
            .toString());
        command.add("-Xmx" + ramMb + "M");
        command.add("-Djava.library.path=" + nativesDir.toAbsolutePath());
        command.add("-cp");
        command.add(buildClasspath(librariesDir, librariesManifest, modJar));
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
            return builder.start();
        } catch (IOException e) {
            throw new LauncherException("Could not start the game process", e);
        }
    }

    /**
     * Builds the classpath from the fixed {@code libraries.txt} manifest instead of listing
     * {@code librariesDir}'s actual contents - see class comment for why (this is the whole point of
     * the mods/-folder fix: don't reintroduce the same problem one directory over).
     */
    private String buildClasspath(Path librariesDir, Path librariesManifest, Path modJar) {
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

        classpath.append(modJar.toAbsolutePath());
        return classpath.toString();
    }
}
