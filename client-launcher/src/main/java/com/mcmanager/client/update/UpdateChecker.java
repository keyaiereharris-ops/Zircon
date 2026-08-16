package com.mcmanager.client.update;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

public class UpdateChecker {
    private static final Logger log = LoggerFactory.getLogger(UpdateChecker.class);

    // Constant for the running client version
    public static final String CURRENT_VERSION = "1.0.0";

    // Default fallback R2 endpoint (can be overridden via -Dzircon.updateUrl)
    private static final String DEFAULT_UPDATE_URL = System.getProperty(
            "zircon.updateUrl", "https://pub-r2.yourdomain.com/updates/latest.json");

    private static final Gson GSON = new Gson();

    public record UpdateManifest(
            String version,
            String releaseDate,
            String releaseNotes,
            Map<String, String> downloads
    ) {}

    /**
     * Checks R2 asynchronously for an available update.
     * Never blocks the UI thread or startup flow.
     */
    public static void checkForUpdatesAsync(java.util.function.Consumer<UpdateManifest> onUpdateAvailable) {
        Thread.ofVirtual().name("update-checker").start(() -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(DEFAULT_UPDATE_URL))
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body() != null && !response.body().isBlank()) {
                    UpdateManifest manifest = GSON.fromJson(response.body(), UpdateManifest.class);
                    if (manifest != null && isNewerVersion(manifest.version(), CURRENT_VERSION)) {
                        log.info("Update available: {} (Current: {})", manifest.version(), CURRENT_VERSION);
                        onUpdateAvailable.accept(manifest);
                    }
                }
            } catch (Exception e) {
                log.debug("Update check skipped or failed: {}", e.getMessage());
            }
        });
    }

    /**
     * Detects host operating system and CPU architecture key matching the manifest.
     */
    public static String detectPlatformKey() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean isArm = arch.contains("aarch64") || arch.contains("arm64");

        if (os.contains("win")) {
            return isArm ? "windows-arm64" : "windows-x64";
        } else if (os.contains("mac")) {
            return isArm ? "macos-arm64" : "macos-x64";
        } else {
            return "linux-deb"; // Default fallback for Debian/Ubuntu based systems
        }
    }

    /**
     * Downloads update installer to temp directory, opens it, and terminates application.
     */
    public static void downloadAndApplyUpdate(String downloadUrl, java.util.function.Consumer<Double> progressCallback)
            throws IOException, InterruptedException {
        String fileName = downloadUrl.substring(downloadUrl.lastIndexOf('/') + 1);
        Path tempTarget = Path.of(System.getProperty("java.io.tmpdir")).resolve(fileName);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(downloadUrl)).GET().build();
        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() / 100 != 2) {
            throw new IOException("Download failed with HTTP " + response.statusCode());
        }

        long totalBytes = response.headers().firstValueAsLong("content-length").orElse(-1L);
        try (var in = response.body(); var out = Files.newOutputStream(tempTarget)) {
            byte[] buffer = new byte[8192];
            long readSoFar = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                readSoFar += read;
                if (totalBytes > 0 && progressCallback != null) {
                    progressCallback.accept((double) readSoFar / totalBytes);
                }
            }
        }

        log.info("Downloaded update installer to {}", tempTarget);
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(tempTarget.toFile());
            System.exit(0);
        } else {
            // Linux fallback if Desktop.open is unsupported
            new ProcessBuilder("xdg-open", tempTarget.toString()).start();
            System.exit(0);
        }
    }

    public static boolean isNewerVersion(String remote, String current) {
        if (remote == null || current == null) return false;
        String[] r = remote.split("-")[0].split("\\.");
        String[] c = current.split("-")[0].split("\\.");
        int max = Math.max(r.length, c.length);
        for (int i = 0; i < max; i++) {
            int rv = i < r.length ? parseSafe(r[i]) : 0;
            int cv = i < c.length ? parseSafe(c[i]) : 0;
            if (rv > cv) return true;
            if (rv < cv) return false;
        }
        return false;
    }

    private static int parseSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D+", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
