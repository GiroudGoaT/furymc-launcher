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
tasks.register<Exec>("packageExe") {
    dependsOn(tasks.jar)
    doFirst {
        delete("build/dist")
    }
    val jpackage = System.getenv("JPACKAGE_HOME")?.let { "$it/bin/jpackage" } ?: "jpackage"
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
