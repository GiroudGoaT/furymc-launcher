package com.mcfaction.launcher;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The remote update manifest (a small JSON file we publish ourselves alongside each release). Parsed by
 * hand with a simple flat-field regex instead of pulling in a JSON library - the format is entirely our
 * own and never nested, so this stays reliable without adding a dependency.
 *
 * <p>
 * Two independent update tracks, so a normal mod release only requires downloading a few MB instead of
 * the whole bundle (JRE + libraries + natives + Mojang assets, ~300 MB, none of which changes from one
 * mod release to the next):
 * <ul>
 * <li><b>base</b> - JRE8/libraries/natives/assets. Published rarely (see assemble-base-bundle.ps1).
 * <li><b>mod</b> - a patched library jar (the mod merged in) + game-dir config. Published on every
 * version bump (see assemble-bundle.ps1).
 * </ul>
 *
 * <p>
 * A third, optional track lets the launcher application itself (furymc-launcher.jar) self-update -
 * see SelfUpdater. These fields are optional (and safe to omit on older manifests) so that adding them
 * is never a breaking change for whichever launcher version happens to read it.
 *
 * <p>
 * Expected shape:
 *
 * <pre>
 * {
 *   "version": "1.0.7",
 *   "modUrl": "https://github.com/.../releases/download/v1.0.7/update.zip",
 *   "modSha256": "...",
 *   "baseVersion": "1",
 *   "baseUrl": "https://github.com/.../releases/download/base-v1/base.zip",
 *   "baseSha256": "...",
 *   "launcherVersion": "1.1.0",
 *   "launcherJarUrl": "https://github.com/.../releases/download/launcher-v1.1.0/furymc-launcher.jar",
 *   "launcherJarSha256": "...",
 *   "serverAddress": "host:25565"
 * }
 * </pre>
 */
public class VersionManifest {

    private final String version;
    private final String modUrl;
    private final String modSha256;
    private final String baseVersion;
    private final String baseUrl;
    private final String baseSha256;
    private final String launcherVersion;
    private final String launcherJarUrl;
    private final String launcherJarSha256;
    private final String serverAddress;

    private VersionManifest(String version, String modUrl, String modSha256, String baseVersion,
        String baseUrl, String baseSha256, String launcherVersion, String launcherJarUrl,
        String launcherJarSha256, String serverAddress) {
        this.version = version;
        this.modUrl = modUrl;
        this.modSha256 = modSha256;
        this.baseVersion = baseVersion;
        this.baseUrl = baseUrl;
        this.baseSha256 = baseSha256;
        this.launcherVersion = launcherVersion;
        this.launcherJarUrl = launcherJarUrl;
        this.launcherJarSha256 = launcherJarSha256;
        this.serverAddress = serverAddress;
    }

    public static VersionManifest parse(String json) {
        String version = extract(json, "version");
        String modUrl = extract(json, "modUrl");
        String modSha256 = extract(json, "modSha256");
        String baseVersion = extract(json, "baseVersion");
        String baseUrl = extract(json, "baseUrl");
        String baseSha256 = extract(json, "baseSha256");
        String launcherVersion = extract(json, "launcherVersion");
        String launcherJarUrl = extract(json, "launcherJarUrl");
        String launcherJarSha256 = extract(json, "launcherJarSha256");
        String serverAddress = extract(json, "serverAddress");
        if (version == null || modUrl == null || baseVersion == null || baseUrl == null) {
            throw new LauncherException(
                "Update manifest is missing required fields (version/modUrl/baseVersion/baseUrl)");
        }
        return new VersionManifest(version, modUrl, modSha256, baseVersion, baseUrl, baseSha256,
            launcherVersion, launcherJarUrl, launcherJarSha256, serverAddress);
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"")
            .matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    public String getVersion() {
        return version;
    }

    public String getModUrl() {
        return modUrl;
    }

    public String getModSha256() {
        return modSha256;
    }

    public String getBaseVersion() {
        return baseVersion;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getBaseSha256() {
        return baseSha256;
    }

    public String getLauncherVersion() {
        return launcherVersion;
    }

    public String getLauncherJarUrl() {
        return launcherJarUrl;
    }

    public String getLauncherJarSha256() {
        return launcherJarSha256;
    }

    public String getServerAddress() {
        return serverAddress;
    }
}
