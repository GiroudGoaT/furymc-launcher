package com.mcfaction.launcher;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The remote update manifest (a small JSON file we publish ourselves alongside each bundle release).
 * Parsed by hand with a simple flat-field regex instead of pulling in a JSON library - the format is
 * entirely our own and never nested, so this stays reliable without adding a dependency.
 *
 * <p>
 * Expected shape:
 *
 * <pre>
 * {
 *   "version": "1.0.0",
 *   "bundleUrl": "https://github.com/.../furymc-bundle.zip",
 *   "sha256": "...",
 *   "serverAddress": "host:25565"
 * }
 * </pre>
 */
public class VersionManifest {

    private final String version;
    private final String bundleUrl;
    private final String sha256;
    private final String serverAddress;

    private VersionManifest(String version, String bundleUrl, String sha256, String serverAddress) {
        this.version = version;
        this.bundleUrl = bundleUrl;
        this.sha256 = sha256;
        this.serverAddress = serverAddress;
    }

    public static VersionManifest parse(String json) {
        String version = extract(json, "version");
        String bundleUrl = extract(json, "bundleUrl");
        String sha256 = extract(json, "sha256");
        String serverAddress = extract(json, "serverAddress");
        if (version == null || bundleUrl == null) {
            throw new LauncherException("Update manifest is missing required fields (version/bundleUrl)");
        }
        return new VersionManifest(version, bundleUrl, sha256, serverAddress);
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"")
            .matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    public String getVersion() {
        return version;
    }

    public String getBundleUrl() {
        return bundleUrl;
    }

    public String getSha256() {
        return sha256;
    }

    public String getServerAddress() {
        return serverAddress;
    }
}
