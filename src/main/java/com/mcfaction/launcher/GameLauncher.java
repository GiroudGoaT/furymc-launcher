package com.mcfaction.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Builds and runs the exact java invocation validated by hand for this project: LaunchWrapper +
 * FMLTweaker, with the mod jar living in the game dir's own mods/ folder (never on the classpath - FML
 * either crashes on a duplicate-mod check or throws a NullPointerException in
 * CoreModManager#discoverCoreMods if mods/ doesn't already exist when it starts scanning).
 *
 * <p>
 * Expects the bundle layout produced by tools/assemble-bundle.ps1:
 *
 * <pre>
 * installDir/
 *   jre8/bin/java.exe
 *   libraries/*.jar      (flat - every runtime dependency jar, Forge+MC jar included)
 *   natives/              (Windows LWJGL/JInput natives)
 *   assets/               (Mojang 1.7.10 assets: indexes/, objects/)
 *   instance/             (the actual game dir: mods/, config/, saves/, options.txt, ...)
 * </pre>
 */
public class GameLauncher {

    private static final String MAIN_CLASS = "net.minecraft.launchwrapper.Launch";
    private static final String TWEAK_CLASS = "cpw.mods.fml.common.launcher.FMLTweaker";
    private static final String MC_VERSION = "1.7.10";

    public Process launch(Path installDir, String username, String uuid) {
        Path javaExe = installDir.resolve("jre8/bin/java.exe");
        Path librariesDir = installDir.resolve("libraries");
        Path nativesDir = installDir.resolve("natives");
        Path assetsDir = installDir.resolve("assets");
        Path gameDir = installDir.resolve("instance");
        Path modsDir = gameDir.resolve("mods");

        if (!Files.isExecutable(javaExe)) {
            throw new LauncherException("Bundled Java runtime not found at " + javaExe);
        }

        try {
            // FML throws a NullPointerException in CoreModManager if this doesn't already exist when
            // launched - see class comment.
            Files.createDirectories(modsDir);
        } catch (IOException e) {
            throw new LauncherException("Could not prepare the game directory", e);
        }

        List<String> command = new ArrayList<>();
        command.add(javaExe.toAbsolutePath()
            .toString());
        command.add("-Djava.library.path=" + nativesDir.toAbsolutePath());
        command.add("-cp");
        command.add(buildClasspath(librariesDir));
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

    private String buildClasspath(Path librariesDir) {
        if (!Files.isDirectory(librariesDir)) {
            throw new LauncherException("Libraries folder not found at " + librariesDir);
        }
        try (Stream<Path> files = Files.list(librariesDir)) {
            StringBuilder classpath = new StringBuilder();
            files.filter(p -> p.toString()
                .endsWith(".jar"))
                .forEach(p -> classpath.append(p.toAbsolutePath())
                    .append(File.pathSeparator));
            if (classpath.length() == 0) {
                throw new LauncherException("No library jars found in " + librariesDir);
            }
            return classpath.toString();
        } catch (IOException e) {
            throw new LauncherException("Could not list library jars", e);
        }
    }
}
