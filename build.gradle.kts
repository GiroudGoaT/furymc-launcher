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

// Packages the launcher as a standalone Windows .exe (with a bundled JRE, so players don't need Java
// installed) via jpackage. Run after `./gradlew jar`. Output goes to build/dist/.
tasks.register<Exec>("packageExe") {
    dependsOn(tasks.jar)
    doFirst {
        delete("build/dist")
    }
    val jpackage = System.getenv("JPACKAGE_HOME")?.let { "$it/bin/jpackage" } ?: "jpackage"
    val args = mutableListOf(
        jpackage,
        "--type", "exe",
        "--name", "FuryMc Launcher",
        "--app-version", project.findProperty("launcherVersion")?.toString() ?: "1.0.0",
        "--input", "build/libs",
        "--main-jar", "${project.name}.jar",
        "--main-class", "com.mcfaction.launcher.FuryMcLauncher",
        "--dest", "build/dist",
        "--win-console"
    )
    val icon = file("src/main/resources/launcher_icon.ico")
    if (icon.exists()) {
        args.add("--icon")
        args.add(icon.absolutePath)
    }
    commandLine(args)
}
