## Phase 1: Client Auto-Updater Subsystem

### 1.1 Create `UpdateChecker.java`
* **File Location:** `client-launcher/src/main/java/com/mcmanager/client/update/UpdateChecker.java`
* **Purpose:** Asynchronously fetch `latest.json` from R2, compare SemVer strings, download update payloads, and launch installers.

```java
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
import java.nio.file.StandardCopyOption;
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
```

### 1.2 Hook Update Checker into UI Controller
* **File Location:** `client-launcher/src/main/java/com/mcmanager/client/ui/controller/MainController.java`
* **Task:** In `init()`, call `UpdateChecker.checkForUpdatesAsync()`. If an update is found, display an AtlantaFX-styled JavaFX dialog prompt.

```java
// Inside MainController.java -> init() method:
UpdateChecker.checkForUpdatesAsync(manifest -> {
    Platform.runLater(() -> promptUpdateDialog(manifest));
});
```

* **Dialog Implementation (`promptUpdateDialog`):**
```java
private void promptUpdateDialog(UpdateChecker.UpdateManifest manifest) {
    String platformKey = UpdateChecker.detectPlatformKey();
    String downloadUrl = manifest.downloads() != null ? manifest.downloads().get(platformKey) : null;

    if (downloadUrl == null || downloadUrl.isBlank()) {
        log.warn("No update download URL found for platform key: {}", platformKey);
        return;
    }

    Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
    alert.setTitle("Zircon Launcher Update");
    alert.setHeaderText("Version " + manifest.version() + " is available!");
    alert.setContentText("Release Notes:\n" + manifest.releaseNotes() + "\n\nWould you like to update now?");

    ButtonType btnUpdate = new ButtonType("Update & Restart", ButtonBar.ButtonData.OK_DONE);
    ButtonType btnLater = new ButtonType("Later", ButtonBar.ButtonData.CANCEL_CLOSE);
    alert.getButtonTypes().setAll(btnUpdate, btnLater);

    alert.showAndWait().ifPresent(type -> {
        if (type == btnUpdate) {
            status("Downloading update " + manifest.version() + "...");
            progressBar.setVisible(true);
            Thread.ofVirtual().start(() -> {
                try {
                    UpdateChecker.downloadAndApplyUpdate(downloadUrl, progress -> {
                        Platform.runLater(() -> progressBar.setProgress(progress));
                    });
                } catch (Exception e) {
                    log.error("Failed to apply update", e);
                    Platform.runLater(() -> status("Update failed: " + e.getMessage()));
                }
            });
        }
    });
}
```

---

## Phase 2: Server Dockerization & Deployment Assets

### 2.1 Server Dockerfile
* **File Location:** `server-manager/Dockerfile`
* **Requirements:** Base image must be `eclipse-temurin:21-jdk-alpine` (JDK is required to execute Forge/NeoForge headless installer commands via `java`).

```dockerfile
FROM eclipse-temurin:21-jdk-alpine

WORKDIR /app

# Install bash, curl, and tar (required for server installers and decompression)
RUN apk add --no-cache bash curl tar

# Copy built Fat JAR from Gradle Shadow build
COPY build/libs/server-manager-all.jar /app/server-manager.jar

# Exposed Ports:
# 25565 - Public TCP Multiplexer (Minecraft & HTTP Ingress)
# 25564 - Admin Web API / UI
# 25700-25710 - Internal Server Instance Pool
EXPOSE 25565 25564 25700-25710

# External volume mount for persistent server data
VOLUME ["/app/server-data"]

# Run server manager with explicit data directory property
ENTRYPOINT ["java", "-Dmcmanager.dataDir=/app/server-data", "-Xms1G", "-Xmx4G", "-jar", "/app/server-manager.jar"]
```

### 2.2 Root Docker Compose Template
* **File Location:** `docker-compose.yml`

```yaml
version: '3.8'

services:
  zircon-server:
    image: ghcr.io/your-org/zircon-server:latest
    container_name: zircon-server
    restart: unless-stopped
    ports:
      - "25565:25565" # Public Minecraft Multiplexer
      - "25564:25564" # Web Admin Dashboard
    volumes:
      - ./server-data:/app/server-data
```

### 2.3 Dockerignore
* **File Location:** `.dockerignore`

```
.git
.gradle
build
*/build
.idea
*.file
*.log
```

---

## Phase 3: Gradle & Native Dependency Verification

### 3.1 Verify LWJGL Cross-Platform Natives in `build.gradle`
Ensure `client-launcher` subproject imports all LWJGL native classifiers so that native packaging (`jpackage`) on Windows x64/ARM64, macOS x64/ARM64, and Linux includes the binaries.

* **File Location:** `client-launcher/build.gradle` (or main `build.gradle`):

```groovy
ext {
    lwjgl_version = "3.3.3"
}

dependencies {
    // LWJGL Core & Binding Libraries
    implementation "org.lwjgl:lwjgl:$lwjgl_version"
    implementation "org.lwjgl:lwjgl-glfw:$lwjgl_version"
    implementation "org.lwjgl:lwjgl-opengl:$lwjgl_version"

    // LWJGL Platform Natives for cross-platform support
    runtimeOnly "org.lwjgl:lwjgl:$lwjgl_version:natives-windows"
    runtimeOnly "org.lwjgl:lwjgl:$lwjgl_version:natives-windows-arm64"
    runtimeOnly "org.lwjgl:lwjgl:$lwjgl_version:natives-linux"
    runtimeOnly "org.lwjgl:lwjgl:$lwjgl_version:natives-macos"
    runtimeOnly "org.lwjgl:lwjgl:$lwjgl_version:natives-macos-arm64"
    
    runtimeOnly "org.lwjgl:lwjgl-glfw:$lwjgl_version:natives-windows"
    runtimeOnly "org.lwjgl:lwjgl-glfw:$lwjgl_version:natives-windows-arm64"
    runtimeOnly "org.lwjgl:lwjgl-glfw:$lwjgl_version:natives-linux"
    runtimeOnly "org.lwjgl:lwjgl-glfw:$lwjgl_version:natives-macos"
    runtimeOnly "org.lwjgl:lwjgl-glfw:$lwjgl_version:natives-macos-arm64"

    runtimeOnly "org.lwjgl:lwjgl-opengl:$lwjgl_version:natives-windows"
    runtimeOnly "org.lwjgl:lwjgl-opengl:$lwjgl_version:natives-windows-arm64"
    runtimeOnly "org.lwjgl:lwjgl-opengl:$lwjgl_version:natives-linux"
    runtimeOnly "org.lwjgl:lwjgl-opengl:$lwjgl_version:natives-macos"
    runtimeOnly "org.lwjgl:lwjgl-opengl:$lwjgl_version:natives-macos-arm64"
}
```

---

## Phase 4: Snapcraft Configuration for Linux

### 4.1 Create Snapcraft Spec
* **File Location:** `snap/snapcraft.yaml`

```yaml
name: zircon
base: core22
version: '1.0.0'
summary: Zircon Minecraft Launcher & Server Manager Client
description: |
  Zircon is an automated Minecraft launcher with mod synchronization, 
  3D player preview, offline instance management, and server integration.

grade: stable
confinement: strict

architectures:
  - build-on: amd64

apps:
  zircon:
    command: bin/desktop-launcher
    desktop: meta/gui/zircon.desktop
    plugs:
      - network
      - text-secure-metadata
      - home
      - opengl
      - audio-playback

parts:
  zircon-client:
    plugin: dump
    source: client-launcher/build/libs/
    source-type: local
    stage-packages:
      - openjdk-21-jre
      - libgl1-mesa-glx
      - libpulse0
```

---

## Phase 5: GitHub Actions CI/CD Pipeline

### 5.1 Create Unified Release Workflow
* **File Location:** `.github/workflows/build-and-release.yml`

```yaml
name: Build Release & Deploy

on:
  push:
    tags:
      - 'v*'

env:
  R2_BUCKET: zircon-releases

jobs:
  # ==================================================================
  # JOB 1: MATRIX BUILD FOR CLIENT LAUNCHER (jpackage)
  # ==================================================================
  build-client-installers:
    name: Build Launcher (${{ matrix.os_key }})
    runs-on: ${{ matrix.os }}
    strategy:
      fail-fast: false
      matrix:
        include:
          - os: windows-latest
            os_key: windows-x64
            pkg_type: msi
            artifact_ext: msi
          - os: windows-11-arm64
            os_key: windows-arm64
            pkg_type: msi
            artifact_ext: msi
          - os: macos-13
            os_key: macos-x64
            pkg_type: dmg
            artifact_ext: dmg
          - os: macos-14
            os_key: macos-arm64
            pkg_type: dmg
            artifact_ext: dmg
          - os: ubuntu-latest
            os_key: linux-deb
            pkg_type: deb
            artifact_ext: deb
          - os: ubuntu-latest
            os_key: linux-rpm
            pkg_type: rpm
            artifact_ext: rpm

    steps:
      - name: Checkout Code
        uses: actions/checkout@v4

      - name: Setup JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v3

      - name: Build Fat JAR
        run: ./gradlew :client-launcher:shadowJar

      - name: Extract Tag Version
        id: get_version
        shell: bash
        run: echo "VERSION=${GITHUB_REF_NAME#v}" >> $GITHUB_OUTPUT

      - name: Run jpackage
        shell: bash
        run: |
          mkdir -p dist/
          jpackage \
            --type ${{ matrix.pkg_type }} \
            --dest dist/ \
            --name Zircon \
            --app-version ${{ steps.get_version.outputs.VERSION }} \
            --input client-launcher/build/libs/ \
            --main-jar client-launcher-all.jar \
            --main-class com.mcmanager.client.Main \
            --java-options "-Xmx2G" \
            --vendor "Zircon"

      - name: Rename Artifact for R2 Storage
        shell: bash
        run: |
          mv dist/*.${{ matrix.artifact_ext }} dist/Zircon-${{ steps.get_version.outputs.VERSION }}-${{ matrix.os_key }}.${{ matrix.artifact_ext }}

      - name: Upload Artifact to Cloudflare R2
        uses: jakejarvis/s3-sync-action@master
        with:
          args: --acl public-read --follow-symlinks
        env:
          AWS_S3_BUCKET: ${{ env.R2_BUCKET }}
          AWS_ACCESS_KEY_ID: ${{ secrets.R2_ACCESS_KEY_ID }}
          AWS_SECRET_ACCESS_KEY: ${{ secrets.R2_SECRET_ACCESS_KEY }}
          AWS_S3_ENDPOINT: https://${{ secrets.CLOUDFLARE_ACCOUNT_ID }}.r2.cloudflarestorage.com
          DEST_DIR: releases/${{ github.ref_name }}/
          SOURCE_DIR: dist/

  # ==================================================================
  # JOB 2: GENERATE AND PUBLISH MANIFEST TO R2
  # ==================================================================
  publish-manifest:
    name: Generate & Publish Update Manifest
    needs: build-client-installers
    runs-on: ubuntu-latest
    steps:
      - name: Checkout Code
        uses: actions/checkout@v4

      - name: Extract Clean Version
        id: version
        run: echo "TAG=${GITHUB_REF_NAME#v}" >> $GITHUB_OUTPUT

      - name: Generate latest.json
        run: |
          R2_BASE="https://pub-r2.yourdomain.com/releases/${{ github.ref_name }}"
          cat <<EOF > latest.json
          {
            "version": "${{ steps.version.outputs.TAG }}",
            "releaseDate": "$(date -u +%Y-%m-%d)",
            "releaseNotes": "Zircon Release ${{ github.ref_name }}",
            "downloads": {
              "windows-x64": "${R2_BASE}/Zircon-${{ steps.version.outputs.TAG }}-windows-x64.msi",
              "windows-arm64": "${R2_BASE}/Zircon-${{ steps.version.outputs.TAG }}-windows-arm64.msi",
              "macos-x64": "${R2_BASE}/Zircon-${{ steps.version.outputs.TAG }}-macos-x64.dmg",
              "macos-arm64": "${R2_BASE}/Zircon-${{ steps.version.outputs.TAG }}-macos-arm64.dmg",
              "linux-deb": "${R2_BASE}/Zircon-${{ steps.version.outputs.TAG }}-linux-deb.deb",
              "linux-rpm": "${R2_BASE}/Zircon-${{ steps.version.outputs.TAG }}-linux-rpm.rpm"
            }
          }
          EOF

      - name: Upload latest.json to R2 Root
        uses: jakejarvis/s3-sync-action@master
        with:
          args: --acl public-read
        env:
          AWS_S3_BUCKET: ${{ env.R2_BUCKET }}
          AWS_ACCESS_KEY_ID: ${{ secrets.R2_ACCESS_KEY_ID }}
          AWS_SECRET_ACCESS_KEY: ${{ secrets.R2_SECRET_ACCESS_KEY }}
          AWS_S3_ENDPOINT: https://${{ secrets.CLOUDFLARE_ACCOUNT_ID }}.r2.cloudflarestorage.com
          DEST_DIR: updates/
          SOURCE_DIR: .

  # ==================================================================
  # JOB 3: BUILD SNAP STORE PACKAGE
  # ==================================================================
  build-snap:
    name: Build & Publish Snap
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v3
      - run: ./gradlew :client-launcher:shadowJar

      - name: Build Snap
        uses: snapcore/action-build@v1
        id: snapcraft

      - name: Publish to Snap Store
        uses: snapcore/action-publish@v1
        env:
          SNAPCRAFT_STORE_CREDENTIALS: ${{ secrets.SNAPCRAFT_LOGIN_TOKEN }}
        with:
          snap: ${{ steps.snapcraft.outputs.snap }}
          release: edge

  # ==================================================================
  # JOB 4: DOCKER BUILD FOR SERVER MANAGER
  # ==================================================================
  build-server-docker:
    name: Build & Push Server Docker Image
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v3
      - run: ./gradlew :server-manager:shadowJar

      - name: Set up QEMU
        uses: docker/setup-qemu-action@v3

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Log in to GHCR
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Build and Push Multi-Arch Docker Image
        uses: docker/build-push-action@v5
        with:
          context: .
          file: ./server-manager/Dockerfile
          platforms: linux/amd64,linux/arm64
          push: true
          tags: |
            ghcr.io/${{ github.repository }}/zircon-server:latest
            ghcr.io/${{ github.repository }}/zircon-server:${{ github.ref_name }}
```

---

## Phase 6: Implementation Verification & Unit Testing Checklist

Agent must ensure the following tests and verifications pass locally before submitting code:

1. **SemVer Parsing Unit Tests:**
   * Verify `UpdateChecker.isNewerVersion("1.1.0", "1.0.0") == true`
   * Verify `UpdateChecker.isNewerVersion("1.0.0", "1.0.0") == false`
   * Verify `UpdateChecker.isNewerVersion("1.0.1", "1.0.0") == true`
   * Verify `UpdateChecker.isNewerVersion("1.0.0", "1.0.1") == false`

2. **Gradle Shadows Verification:**
   * Run `./gradlew :client-launcher:shadowJar` -> Verify `client-launcher-all.jar` is produced and executable (`java -jar ...`).
   * Run `./gradlew :server-manager:shadowJar` -> Verify `server-manager-all.jar` is produced.

3. **Dockerfile Build Local Check:**
   * Run `docker build -f server-manager/Dockerfile -t zircon-server:local .` -> Ensure build completes without error.
