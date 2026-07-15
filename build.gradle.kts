plugins {
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass.set("com.mcfaction.launcher.FuryMcLauncher")
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "com.mcfaction.launcher.FuryMcLauncher")
    }
}

// Packages the launcher as a standalone Windows app-image (bundled JRE, no separate Java install
// needed) via jpackage. Produces build/dist/FuryMc Launcher/FuryMc Launcher.exe - a plain portable
// exe, not an installer, since an installer (--type exe/msi) needs the WiX Toolset v3 which in turn
// needs the .NET Framework 3.5 Windows feature enabled, which requires admin/UAC elevation we don't
// have here. Run after `./gradlew jar`.
//
// The jpackage tool is pinned to the toolchain's own JDK (not left to resolve "jpackage" off PATH) -
// a bare PATH lookup previously picked up a stray JDK 17 install on this machine, which jlinked a
// JDK 17 runtime into the app-image while compileJava (via the toolchain above) produced Java 25
// class files. A JDK 17 JVM can't load Java 25 bytecode - it threw UnsupportedClassVersionError
// before any of our code ran, and since this is a windowed app-image with no console attached, that
// failure was completely invisible (no window, no log, no error dialog - main() never got that far).
// Deliberately NOT passing --runtime-image: without it, jpackage jlinks its own minimal runtime from
// whichever JDK it's invoked from (here, forced to be JDK 25 to match), so this both fixes the
// version mismatch and keeps the bundled runtime small - a full JDK 25 install is ~3x the size.
val jpackage = javaToolchains.launcherFor(java.toolchain)
    .get()
    .metadata
    .installationPath
    .asFile.resolve("bin/jpackage").absolutePath

tasks.register<Exec>("packageExe") {
    dependsOn(tasks.jar)
    doFirst {
        delete("build/dist")
    }
    val args = mutableListOf(
        jpackage,
        "--type", "app-image",
        "--name", "FuryMc Launcher",
        "--app-version", project.findProperty("launcherVersion")?.toString() ?: "1.0.0",
        "--input", "build/libs",
        "--main-jar", "${project.name}.jar",
        "--main-class", "com.mcfaction.launcher.FuryMcLauncher",
        "--dest", "build/dist"
    )
    val icon = file("src/main/resources/launcher_icon.ico")
    if (icon.exists()) {
        args.add("--icon")
        args.add(icon.absolutePath)
    }
    commandLine(args)
}
