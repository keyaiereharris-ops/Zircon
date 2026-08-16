# Agent Implementation & Execution Plan: Launcher Refactoring & Dynamic Mod Staging

## 📌 Context & Objectives

This document provides a step-by-step, self-contained implementation plan for an automated coding agent. The goal is to refactor the **McManager / Zircon Minecraft Client Launcher** to introduce dynamic mod staging, server list persistence, skin customization, and a redesigned JavaFX user interface.

### Key Objectives
1. **Dynamic Mod Staging (`ModSyncEngine.java`)**: Download mods into a temporary staging area (`.mod_staging`) before launching, then dynamically reconcile the instance's active `mods/` directory by copying verified mods from staging and purging unlisted/stale JARs.
2. **Saved Server Storage (`SavedServer.java`)**: Persist played/saved servers to `~/.mcmanager/servers.json` sorted by most recent play time.
3. **Skin Management (`SkinManager.java`)**: Store and load custom PNG skins in `~/.mcmanager/skins/active_skin.png`.
4. **UI Navigation Redesign (`MainApp.java`)**: Implement a left navigation sidebar with tabs for **Server List**, **Change Skin**, **Settings**, and **Play Offline** mode toggle.
5. **UI Logic (`MainController.java`)**: Drive view switching, server connection launches, skin uploads, and settings persistence.

---

## 📂 Project File Structure Map

Target Directory: `client-launcher/src/main/java/`

```text
com/mcmanager/client/
├── model/
│   └── SavedServer.java                  <-- [NEW] Persistence for server list
├── skin/
│   └── SkinManager.java                  <-- [NEW] Handles local PNG skin storage/loading
├── sync/
│   └── ModSyncEngine.java                <-- [UPDATED] Downloads to .mod_staging & syncs active mods/
└── ui/
    ├── MainApp.java                      <-- [UPDATED] Redesigned JavaFX sidebar layout
    └── controller/
        └── MainController.java          <-- [UPDATED] Drives navigation, views & launch flow
```

---

## 📋 Task Checklist for Coding Agent

- [ ] **Step 1**: Update `com/mcmanager/client/sync/ModSyncEngine.java` with the staging area logic.
- [ ] **Step 2**: Create `com/mcmanager/client/model/SavedServer.java` for server list persistence.
- [ ] **Step 3**: Create `com/mcmanager/client/skin/SkinManager.java` for skin PNG management.
- [ ] **Step 4**: Update `com/mcmanager/client/ui/MainApp.java` with the sidebar and views layout.
- [ ] **Step 5**: Update `com/mcmanager/client/ui/controller/MainController.java` to bind all views and controls.
- [ ] **Step 6**: Execute Gradle build and test launcher startup.

---

## 🛠️ Complete Source Code Files

### File 1: `com/mcmanager/client/sync/ModSyncEngine.java`

```java
package com.mcmanager.client.sync;

import com.mcmanager.core.api.CurseForgeApiClient;
import com.mcmanager.core.api.ModrinthApiClient;
import com.mcmanager.core.model.BillOfMaterials;
import com.mcmanager.core.model.BomJson;
import com.mcmanager.core.model.ModEntry;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Brings the local instance mods folder in line with the server's Bill of Materials:
 * <ol>
 *   <li>Fetch {@code /bom} from the server.</li>
 *   <li>Batch-verify hashes against Modrinth / CurseForge.</li>
 *   <li>Download missing / mismatched JARs into a staging area ({@code .mod_staging}).</li>
 *   <li>Dynamically reconcile the active {@code mods/} directory against the staging area,
 *       removing unlisted mods and copying the staged BOM mods.</li>
 * </ol>
 */
public class ModSyncEngine {

    private static final Logger log = LoggerFactory.getLogger(ModSyncEngine.class);

    private final HttpClient http;

    public ModSyncEngine() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public interface ProgressListener {
        void onStatus(String message);

        void onProgress(double fraction, String detail);
    }

    public static class SyncResult {
        public BillOfMaterials bom;
        public List<String> downloaded = new ArrayList<>();
        public List<String> removed = new ArrayList<>();
        public List<String> kept = new ArrayList<>();
        public List<String> unverified = new ArrayList<>();
        public boolean aborted;
        public String abortReason;
    }

    /**
     * Synchronizes the mods folder with the server using a temporary staging area.
     */
    public SyncResult sync(String serverBaseUrl, Path gameDir, boolean strictVerification,
                           boolean trustDirectMods, ProgressListener listener)
            throws IOException, InterruptedException {
        String base = serverBaseUrl.endsWith("/")
                ? serverBaseUrl.substring(0, serverBaseUrl.length() - 1)
                : serverBaseUrl;

        SyncResult result = new SyncResult();
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);

        // Staging directory where downloads land before moving into active mods/
        Path stagingDir = gameDir.resolve(".mod_staging");
        Files.createDirectories(stagingDir);

        // --- Step 1: fetch the BOM ---
        listener.onStatus("Fetching mod list from " + base + "...");
        String bomJson = get(base + "/bom");
        result.bom = BomJson.fromJson(bomJson);
        List<ModEntry> mods = result.bom.getMods();
        log.info("BOM: {} mods for MC {}", mods.size(), result.bom.getMinecraftVersion());

        // --- Step 2: verify hashes against Modrinth / CurseForge ---
        listener.onStatus("Verifying mod hashes...");
        String curseForgeKey = resolveCurseForgeKey(base);
        verifyAgainstProviders(mods, curseForgeKey, result, strictVerification, trustDirectMods);
        if (result.aborted) {
            return result;
        }

        // --- Step 3: download missing / mismatched mods into STAGING AREA ---
        long totalBytes = mods.stream().mapToLong(ModEntry::getFileSize).sum();
        AtomicLong downloadedBytes = new AtomicLong();

        for (int i = 0; i < mods.size(); i++) {
            ModEntry mod = mods.get(i);
            Path stagedTarget = stagingDir.resolve(mod.getFilename());

            if (HashVerifier.matches(stagedTarget, mod)) {
                result.kept.add(mod.getFilename());
                continue;
            }

            String url = base + "/files/mods/" + urlEncode(mod.getFilename());
            listener.onStatus("Downloading " + mod.getFilename() + " (" + (i + 1) + "/" + mods.size() + ") to staging...");
            long size = download(url, stagedTarget);
            downloadedBytes.addAndGet(size);
            result.downloaded.add(mod.getFilename());

            double fraction = totalBytes > 0 ? Math.min(1.0, downloadedBytes.get() / (double) totalBytes) : 0;
            listener.onProgress(fraction, mod.getFilename());
        }

        // --- Step 4: dynamically reconcile the active instance mods/ directory ---
        listener.onStatus("Synchronizing active instance mods folder...");
        Set<String> wanted = new HashSet<>();
        for (ModEntry mod : mods) {
            wanted.add(mod.getFilename());
        }

        // Delete local JARs in mods/ that are NOT part of the BOM
        try (var stream = Files.list(modsDir)) {
            for (Path file : stream.filter(p -> HashVerifier.isModJar(p.getFileName().toString())).toList()) {
                if (!wanted.contains(file.getFileName().toString())) {
                    Files.deleteIfExists(file);
                    result.removed.add(file.getFileName().toString());
                    log.info("Removed stale/unlisted mod from instance: {}", file.getFileName());
                }
            }
        }

        // Copy/move verified mods from staging into active instance mods/
        for (ModEntry mod : mods) {
            Path stagedFile = stagingDir.resolve(mod.getFilename());
            Path activeTarget = modsDir.resolve(mod.getFilename());
            if (Files.isRegularFile(stagedFile)) {
                Files.copy(stagedFile, activeTarget, StandardCopyOption.REPLACE_EXISTING);
            } else {
                log.warn("Staged file missing for mod: {}", mod.getFilename());
            }
        }

        listener.onProgress(1.0, "Done");
        listener.onStatus("Mods up to date (" + result.kept.size() + " kept, "
                + result.downloaded.size() + " downloaded, " + result.removed.size() + " removed)");
        return result;
    }

    private String resolveCurseForgeKey(String baseUrl) {
        try {
            String configJson = get(baseUrl + "/api/config");
            JsonObject config = BomJson.gson().fromJson(configJson, JsonObject.class);
            if (config != null && config.has("curseforgeApiKey")
                    && !config.get("curseforgeApiKey").getAsString().isBlank()) {
                return config.get("curseforgeApiKey").getAsString();
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("Could not read CurseForge key from server config: {}", e.getMessage());
        }
        return System.getProperty("mcmanager.curseforgeApiKey", "");
    }

    private void verifyAgainstProviders(List<ModEntry> mods, String curseForgeApiKey,
                                        SyncResult result,
                                        boolean strict, boolean trustDirect) {
        List<String> sha1s = new ArrayList<>();
        List<Long> fingerprints = new ArrayList<>();
        for (ModEntry mod : mods) {
            if ("modrinth".equals(mod.getOrigin()) && mod.getSha1() != null) {
                sha1s.add(mod.getSha1());
            } else if ("curseforge".equals(mod.getOrigin()) && mod.getMurmur3() != 0) {
                fingerprints.add(mod.getMurmur3());
            }
        }

        Set<String> verifiedSha1 = new HashSet<>();
        Set<Long> verifiedFp = new HashSet<>();

        boolean modrinthChecked = sha1s.isEmpty();
        boolean curseForgeChecked = fingerprints.isEmpty();

        if (!sha1s.isEmpty()) {
            try {
                ModrinthApiClient modrinth = new ModrinthApiClient();
                Map<String, ModrinthApiClient.ModrinthVersion> found = modrinth.verifyHashes(sha1s);
                verifiedSha1.addAll(found.keySet());
                modrinthChecked = true;
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("Modrinth hash verification unavailable: {}", e.getMessage());
            }
        }

        if (!fingerprints.isEmpty()) {
            if (curseForgeApiKey == null || curseForgeApiKey.isBlank()) {
                log.info("No CurseForge API key configured — skipping fingerprint verification");
            } else {
                try {
                    CurseForgeApiClient cf = new CurseForgeApiClient(curseForgeApiKey);
                    for (CurseForgeApiClient.CurseForgeFile file : cf.verifyFingerprints(fingerprints)) {
                        verifiedFp.add(file.fileFingerprint);
                    }
                    curseForgeChecked = true;
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    log.warn("CurseForge fingerprint verification unavailable: {}", e.getMessage());
                }
            }
        }

        for (ModEntry mod : mods) {
            boolean verified;
            if ("modrinth".equals(mod.getOrigin())) {
                verified = mod.getSha1() == null || !modrinthChecked || verifiedSha1.contains(mod.getSha1());
            } else if ("curseforge".equals(mod.getOrigin())) {
                verified = mod.getMurmur3() == 0 || !curseForgeChecked || verifiedFp.contains(mod.getMurmur3());
            } else {
                verified = trustDirect;
            }
            if (!verified) {
                result.unverified.add(mod.getFilename());
                log.warn("Unverified mod: {} ({})", mod.getFilename(), mod.getOrigin());
            }
        }

        if (strict && !result.unverified.isEmpty()) {
            result.aborted = true;
            result.abortReason = "The following mods could not be verified against their source: "
                    + String.join(", ", result.unverified)
                    + ". Enable 'trust custom mods' or fix the server BOM to continue.";
        }
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("GET " + url + " failed: HTTP " + response.statusCode());
        }
        return response.body();
    }

    private long download(String url, Path target) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Download " + url + " failed: HTTP " + response.statusCode());
        }
        long written = 0;
        try (InputStream in = response.body()) {
            byte[] buffer = new byte[8192];
            int read;
            try (var out = Files.newOutputStream(target)) {
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    written += read;
                }
            }
        }
        return written;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
```

---

### File 2: `main/java/com/mcmanager/client/model/SavedServer.java`

```java
package com.mcmanager.client.model;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Represents a saved/played-on server entry in the client launcher.
 * Persisted in {@code ~/.mcmanager/servers.json}.
 */
public class SavedServer {

    private String name;
    private String address;
    private long lastPlayed;

    public SavedServer() {
    }

    public SavedServer(String name, String address, long lastPlayed) {
        this.name = name;
        this.address = address;
        this.lastPlayed = lastPlayed;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public long getLastPlayed() {
        return lastPlayed;
    }

    public void setLastPlayed(long lastPlayed) {
        this.lastPlayed = lastPlayed;
    }

    private static final Path SERVERS_FILE = Path.of(System.getProperty("user.home"), ".mcmanager", "servers.json");
    private static final Gson GSON = new Gson();

    public static List<SavedServer> load() {
        if (!Files.isRegularFile(SERVERS_FILE)) {
            return new ArrayList<>();
        }
        try {
            String json = Files.readString(SERVERS_FILE, StandardCharsets.UTF_8);
            Type type = new TypeToken<List<SavedServer>>() {}.getType();
            List<SavedServer> list = GSON.fromJson(json, type);
            if (list != null) {
                list.sort(Comparator.comparingLong(SavedServer::getLastPlayed).reversed());
                return list;
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<>();
    }

    public static void save(List<SavedServer> servers) {
        try {
            Files.createDirectories(SERVERS_FILE.getParent());
            servers.sort(Comparator.comparingLong(SavedServer::getLastPlayed).reversed());
            Files.writeString(SERVERS_FILE, GSON.toJson(servers), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    public static void recordPlayed(String name, String address) {
        List<SavedServer> servers = load();
        SavedServer existing = null;
        for (SavedServer s : servers) {
            if (s.getAddress().equalsIgnoreCase(address.trim())) {
                existing = s;
                break;
            }
        }
        if (existing != null) {
            if (name != null && !name.isBlank()) {
                existing.setName(name.trim());
            }
            existing.setLastPlayed(System.currentTimeMillis());
        } else {
            String serverName = (name != null && !name.isBlank()) ? name.trim() : address.trim();
            servers.add(new SavedServer(serverName, address.trim(), System.currentTimeMillis()));
        }
        save(servers);
    }
}
```

---

### File 3: `main/java/com/mcmanager/client/skin/SkinManager.java`

```java
package com.mcmanager.client.skin;

import javafx.scene.image.Image;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Stores and loads custom player PNG skin files.
 */
public class SkinManager {

    private static final Path SKIN_DIR = Path.of(System.getProperty("user.home"), ".mcmanager", "skins");
    private static final Path ACTIVE_SKIN = SKIN_DIR.resolve("active_skin.png");

    public static void saveSkin(File sourcePng) throws IOException {
        Files.createDirectories(SKIN_DIR);
        Files.copy(sourcePng.toPath(), ACTIVE_SKIN, StandardCopyOption.REPLACE_EXISTING);
    }

    public static boolean hasCustomSkin() {
        return Files.isRegularFile(ACTIVE_SKIN);
    }

    public static Path getActiveSkinPath() {
        return ACTIVE_SKIN;
    }

    public static Image loadActiveSkinImage() {
        if (hasCustomSkin()) {
            try (FileInputStream fis = new FileInputStream(ACTIVE_SKIN.toFile())) {
                return new Image(fis);
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    public static void resetSkin() {
        try {
            Files.deleteIfExists(ACTIVE_SKIN);
        } catch (IOException ignored) {
        }
    }
}
```

---

### File 4: `main/java/com/mcmanager/client/ui/MainApp.java`

```java
package com.mcmanager.client.ui;

import atlantafx.base.theme.PrimerDark;
import com.mcmanager.client.ui.controller.MainController;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.stage.Stage;

/**
 * JavaFX application shell for McManager client:
 * Left Navigation Sidebar (Server List, Change Skin, Settings, Play Offline)
 * and rich central views matching the required launcher layout.
 */
public class MainApp extends Application {

    @Override
    public void start(Stage stage) {
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());

        // --- Sidebar ---
        Label logo = new Label("⚡");
        logo.setFont(new Font(22));
        logo.setStyle("-fx-background-color: #2da44e; -fx-text-fill: white; "
                + "-fx-background-radius: 8; -fx-padding: 4 10;");

        Label appName = new Label("McManager");
        appName.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: white;");
        Label appSubtitle = new Label("mod-synced launcher");
        appSubtitle.setStyle("-fx-font-size: 10px; -fx-text-fill: #8b949e;");
        VBox titleBox = new VBox(2, appName, appSubtitle);

        HBox brandHeader = new HBox(10, logo, titleBox);
        brandHeader.setAlignment(Pos.CENTER_LEFT);
        brandHeader.setPadding(new Insets(16, 16, 20, 16));

        // Navigation Buttons
        Button navServerList = new Button("⚡  Server List");
        Button navChangeSkin = new Button("👕  Change Skin");
        Button navSettings = new Button("⚙️  Settings");

        for (Button btn : new Button[]{navServerList, navChangeSkin, navSettings}) {
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.setAlignment(Pos.CENTER_LEFT);
            btn.setStyle("-fx-font-size: 14px; -fx-padding: 10 14; -fx-background-radius: 8; "
                    + "-fx-background-color: transparent; -fx-text-fill: #c9d1d9;");
        }

        VBox navBox = new VBox(6, navServerList, navChangeSkin, navSettings);
        navBox.setPadding(new Insets(0, 12, 0, 12));

        Region sidebarSpacer = new Region();
        VBox.setVgrow(sidebarSpacer, Priority.ALWAYS);

        // Sidebar Footer User Card
        Circle avatar = new Circle(14, javafx.scene.paint.Color.web("#2da44e"));
        Label userLabel = new Label("Not signed in");
        userLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: white; -fx-font-weight: bold;");
        Button logoutButton = new Button("Logout");
        logoutButton.setStyle("-fx-font-size: 10px; -fx-padding: 2 8;");
        logoutButton.setVisible(false);

        HBox userHeader = new HBox(8, avatar, userLabel, logoutButton);
        userHeader.setAlignment(Pos.CENTER_LEFT);

        ToggleButton offlineToggle = new ToggleButton("Play Offline");
        offlineToggle.setMaxWidth(Double.MAX_VALUE);
        offlineToggle.setStyle("-fx-font-size: 11px; -fx-padding: 6 10;");

        VBox userCard = new VBox(10, userHeader, offlineToggle);
        userCard.setStyle("-fx-background-color: #161b22; -fx-background-radius: 10; -fx-padding: 12;");

        VBox sidebar = new VBox(brandHeader, navBox, sidebarSpacer, userCard);
        sidebar.setPrefWidth(220);
        sidebar.setMinWidth(220);
        sidebar.setPadding(new Insets(0, 0, 16, 0));
        sidebar.setStyle("-fx-background-color: #0d1117; -fx-border-color: #21262d; -fx-border-width: 0 1 0 0;");

        // --- View 1: Server List ---
        Label sectionYourServers = new Label("Your Servers");
        sectionYourServers.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: white;");

        Button addServerBtn = new Button("+ Add Server");
        addServerBtn.setStyle("-fx-background-color: #21262d; -fx-text-fill: #58a6ff; -fx-font-size: 12px; -fx-font-weight: bold;");

        Region yourSpacer = new Region();
        HBox.setHgrow(yourSpacer, Priority.ALWAYS);
        HBox yourHeader = new HBox(sectionYourServers, yourSpacer, addServerBtn);
        yourHeader.setAlignment(Pos.CENTER_LEFT);

        VBox savedServersContainer = new VBox(10);
        ScrollPane savedScroll = new ScrollPane(savedServersContainer);
        savedScroll.setFitToWidth(true);
        savedScroll.setPrefHeight(200);
        savedScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        Label sectionRecommended = new Label("Recommended Servers");
        sectionRecommended.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: white; -fx-padding: 10 0 0 0;");

        VBox recommendedContainer = new VBox(10);

        VBox serverListView = new VBox(14, yourHeader, savedScroll, sectionRecommended, recommendedContainer);
        serverListView.setPadding(new Insets(20));

        // --- View 2: Change Skin ---
        Label skinTitle = new Label("Skin Customizer");
        skinTitle.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: white;");
        Label skinSubtitle = new Label("Upload a custom 64x64 PNG skin for your Minecraft player");
        skinSubtitle.setStyle("-fx-font-size: 12px; -fx-text-fill: #8b949e;");

        ImageView skinPreview = new ImageView();
        skinPreview.setFitWidth(128);
        skinPreview.setFitHeight(128);
        skinPreview.setPreserveRatio(true);
        skinPreview.setSmooth(false); // Sharp pixel scaling

        StackPane skinBox = new StackPane(skinPreview);
        skinBox.setPrefSize(160, 160);
        skinBox.setMaxSize(160, 160);
        skinBox.setStyle("-fx-background-color: #161b22; -fx-border-color: #30363d; -fx-border-radius: 12; -fx-background-radius: 12;");

        Button uploadSkinBtn = new Button("Upload .PNG Skin");
        uploadSkinBtn.setStyle("-fx-background-color: #2da44e; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 16;");

        Button resetSkinBtn = new Button("Reset to Default");
        resetSkinBtn.setStyle("-fx-background-color: #21262d; -fx-text-fill: #c9d1d9; -fx-padding: 8 16;");

        HBox skinActionBox = new HBox(12, uploadSkinBtn, resetSkinBtn);
        skinActionBox.setAlignment(Pos.CENTER);

        Label skinStatus = new Label("Default Steve / Alex");
        skinStatus.setStyle("-fx-font-size: 12px; -fx-text-fill: #8b949e;");

        VBox changeSkinView = new VBox(16, skinTitle, skinSubtitle, skinBox, skinActionBox, skinStatus);
        changeSkinView.setAlignment(Pos.TOP_CENTER);
        changeSkinView.setPadding(new Insets(30));

        // --- View 3: Settings ---
        Label settingsTitle = new Label("Launcher Settings");
        settingsTitle.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: white;");

        Label ramLabel = new Label("Max Memory Allocation (RAM): 4 GB");
        ramLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #c9d1d9;");
        Slider ramSlider = new Slider(2, 16, 4);
        ramSlider.setMajorTickUnit(2);
        ramSlider.setMinorTickCount(1);
        ramSlider.setSnapToTicks(true);
        ramSlider.setShowTickLabels(true);

        CheckBox strictVerifyCheck = new CheckBox("Strict Hash Verification (Abort on unverified mods)");
        strictVerifyCheck.setSelected(true);

        CheckBox trustDirectCheck = new CheckBox("Trust Direct Custom Mods");
        trustDirectCheck.setSelected(false);

        Label clientIdLabel = new Label("Azure App Client ID");
        clientIdLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #8b949e;");
        TextField clientIdField = new TextField();
        clientIdField.setPromptText("Microsoft App Client ID");

        VBox settingsView = new VBox(18, settingsTitle, ramLabel, ramSlider, strictVerifyCheck, trustDirectCheck, clientIdLabel, clientIdField);
        settingsView.setPadding(new Insets(24));
        settingsView.setMaxWidth(500);

        // --- Central View Switcher ---
        StackPane centerContainer = new StackPane(serverListView, changeSkinView, settingsView);
        changeSkinView.setVisible(false);
        settingsView.setVisible(false);

        // --- Bottom Notification / Launch Bar ---
        Label statusLabel = new Label("Ready to play.");
        statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #8b949e;");

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(6);
        progressBar.setVisible(false);

        VBox bottomStatusBox = new VBox(6, statusLabel, progressBar);
        bottomStatusBox.setPadding(new Insets(10, 20, 14, 20));
        bottomStatusBox.setStyle("-fx-background-color: #0d1117; -fx-border-color: #21262d; -fx-border-width: 1 0 0 0;");

        BorderPane mainContentLayout = new BorderPane();
        mainContentLayout.setCenter(centerContainer);
        mainContentLayout.setBottom(bottomStatusBox);
        mainContentLayout.setStyle("-fx-background-color: #161b22;");

        HBox root = new HBox(sidebar, mainContentLayout);
        HBox.setHgrow(mainContentLayout, Priority.ALWAYS);

        MainController controller = new MainController(
                navServerList, navChangeSkin, navSettings,
                serverListView, changeSkinView, settingsView,
                savedServersContainer, recommendedContainer, addServerBtn,
                skinPreview, uploadSkinBtn, resetSkinBtn, skinStatus,
                ramSlider, ramLabel, strictVerifyCheck, trustDirectCheck, clientIdField,
                statusLabel, progressBar, userLabel, logoutButton, offlineToggle, stage
        );
        controller.init();

        Scene scene = new Scene(root, 960, 600);
        stage.setTitle("McManager Launcher");
        stage.setScene(scene);
        stage.setMinWidth(800);
        stage.setMinHeight(520);
        stage.show();

        stage.setOnCloseRequest(e -> {
            controller.shutdown();
            Platform.exit();
        });
    }
}
```

---

### File 5: `main/java/com/mcmanager/client/ui/controller/MainController.java`

```java
package com.mcmanager.client.ui.controller;

import com.mcmanager.client.auth.MicrosoftAuthService;
import com.mcmanager.client.auth.SessionData;
import com.mcmanager.client.launch.JavaRuntimeSelector;
import com.mcmanager.client.launch.MinecraftClasspathBuilder;
import com.mcmanager.client.launch.MinecraftRunner;
import com.mcmanager.client.model.SavedServer;
import com.mcmanager.client.skin.SkinManager;
import com.mcmanager.client.sync.ModSyncEngine;
import com.mcmanager.core.model.BillOfMaterials;
import com.mcmanager.core.model.ModLoaderInfo;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controller driving navigation views, server list management,
 * custom skin uploads, settings, dynamic mod staging sync, and game launches.
 */
public class MainController {

    private static final Logger log = LoggerFactory.getLogger(MainController.class);
    private static final String DEFAULT_SERVER_PORT = "25565";

    private static final Path INSTANCES_ROOT = Path.of(
            System.getProperty("user.home"), ".zircon", "instances");

    // Sidebar & View Controls
    private final Button navServerList;
    private final Button navChangeSkin;
    private final Button navSettings;
    private final Node serverListView;
    private final Node changeSkinView;
    private final Node settingsView;

    // Server List View Controls
    private final VBox savedServersContainer;
    private final VBox recommendedContainer;
    private final Button addServerBtn;

    // Skin View Controls
    private final ImageView skinPreview;
    private final Button uploadSkinBtn;
    private final Button resetSkinBtn;
    private final Label skinStatus;

    // Settings Controls
    private final Slider ramSlider;
    private final Label ramLabel;
    private final CheckBox strictVerifyCheck;
    private final CheckBox trustDirectCheck;
    private final TextField clientIdField;

    // Global Status & Auth Controls
    private final Label statusLabel;
    private final ProgressBar progressBar;
    private final Label userLabel;
    private final Button logoutButton;
    private final ToggleButton offlineToggle;
    private final Stage stage;

    private final MicrosoftAuthService auth = new MicrosoftAuthService();
    private final ModSyncEngine syncEngine = new ModSyncEngine();
    private final MinecraftClasspathBuilder classpathBuilder = new MinecraftClasspathBuilder();
    private final MinecraftRunner runner = new MinecraftRunner();

    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile SessionData session;
    private volatile Process gameProcess;
    private boolean offlineMode = Boolean.parseBoolean(System.getProperty("mcmanager.offline", "false"));

    public MainController(Button navServerList, Button navChangeSkin, Button navSettings,
                          Node serverListView, Node changeSkinView, Node settingsView,
                          VBox savedServersContainer, VBox recommendedContainer, Button addServerBtn,
                          ImageView skinPreview, Button uploadSkinBtn, Button resetSkinBtn, Label skinStatus,
                          Slider ramSlider, Label ramLabel, CheckBox strictVerifyCheck, CheckBox trustDirectCheck,
                          TextField clientIdField, Label statusLabel, ProgressBar progressBar,
                          Label userLabel, Button logoutButton, ToggleButton offlineToggle, Stage stage) {
        this.navServerList = navServerList;
        this.navChangeSkin = navChangeSkin;
        this.navSettings = navSettings;
        this.serverListView = serverListView;
        this.changeSkinView = changeSkinView;
        this.settingsView = settingsView;
        this.savedServersContainer = savedServersContainer;
        this.recommendedContainer = recommendedContainer;
        this.addServerBtn = addServerBtn;
        this.skinPreview = skinPreview;
        this.uploadSkinBtn = uploadSkinBtn;
        this.resetSkinBtn = resetSkinBtn;
        this.skinStatus = skinStatus;
        this.ramSlider = ramSlider;
        this.ramLabel = ramLabel;
        this.strictVerifyCheck = strictVerifyCheck;
        this.trustDirectCheck = trustDirectCheck;
        this.clientIdField = clientIdField;
        this.statusLabel = statusLabel;
        this.progressBar = progressBar;
        this.userLabel = userLabel;
        this.logoutButton = logoutButton;
        this.offlineToggle = offlineToggle;
        this.stage = stage;
    }

    public void init() {
        // Setup Navigation Tabs
        navServerList.setOnAction(e -> switchTab(serverListView, navServerList));
        navChangeSkin.setOnAction(e -> switchTab(changeSkinView, navChangeSkin));
        navSettings.setOnAction(e -> switchTab(settingsView, navSettings));
        switchTab(serverListView, navServerList);

        // Auth initialization
        offlineToggle.setSelected(offlineMode);
        offlineToggle.setOnAction(e -> {
            offlineMode = offlineToggle.isSelected();
            initSession();
        });

        initSession();

        // Server List Setup
        addServerBtn.setOnAction(e -> promptAddServer());
        populateServerList();
        populateRecommendedServers();

        // Skin Customizer Setup
        refreshSkinPreview();
        uploadSkinBtn.setOnAction(e -> handleUploadSkin());
        resetSkinBtn.setOnAction(e -> handleResetSkin());

        // Settings Setup
        ramSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            ramLabel.setText("Max Memory Allocation (RAM): " + newVal.intValue() + " GB");
        });

        logoutButton.setOnAction(e -> onLogout());
    }

    private void switchTab(Node targetView, Button activeBtn) {
        serverListView.setVisible(targetView == serverListView);
        changeSkinView.setVisible(targetView == changeSkinView);
        settingsView.setVisible(targetView == settingsView);

        for (Button btn : new Button[]{navServerList, navChangeSkin, navSettings}) {
            if (btn == activeBtn) {
                btn.setStyle("-fx-font-size: 14px; -fx-padding: 10 14; -fx-background-radius: 8; "
                        + "-fx-background-color: #21262d; -fx-text-fill: white; -fx-font-weight: bold;");
            } else {
                btn.setStyle("-fx-font-size: 14px; -fx-padding: 10 14; -fx-background-radius: 8; "
                        + "-fx-background-color: transparent; -fx-text-fill: #c9d1d9;");
            }
        }
    }

    private void initSession() {
        if (offlineMode) {
            String name = System.getProperty("mcmanager.offlineUsername", "DevPlayer");
            session = SessionData.offline(name);
            userLabel.setText(session.getUsername() + " (offline)");
            logoutButton.setVisible(false);
            status("Offline mode enabled");
        } else {
            session = auth.loadCached();
            if (session != null) {
                userLabel.setText(session.getUsername());
                logoutButton.setVisible(true);
                status("Signed in as " + session.getUsername());
            } else {
                userLabel.setText("Not signed in");
                logoutButton.setVisible(false);
                status("Ready to sign in.");
            }
        }
    }

    public void shutdown() {
        if (gameProcess != null && gameProcess.isAlive()) {
            gameProcess.destroy();
        }
    }

    // ------------------------------------------------------------------
    // Server List Management
    // ------------------------------------------------------------------

    private void populateServerList() {
        savedServersContainer.getChildren().clear();
        List<SavedServer> saved = SavedServer.load();
        if (saved.isEmpty()) {
            // Seed a local default server on first run
            SavedServer.recordPlayed("Localhost Server", "localhost:25565");
            saved = SavedServer.load();
        }

        for (SavedServer s : saved) {
            savedServersContainer.getChildren().add(createSavedServerCard(s));
        }
    }

    private HBox createSavedServerCard(SavedServer server) {
        Label nameLbl = new Label(server.getName());
        nameLbl.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: white;");

        Label addrLbl = new Label(server.getAddress());
        addrLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #8b949e;");

        VBox text = new VBox(2, nameLbl, addrLbl);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button playBtn = new Button("PLAY");
        playBtn.setStyle("-fx-background-color: #2da44e; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 6 16;");
        playBtn.setOnAction(e -> launchServer(server.getName(), server.getAddress()));

        HBox card = new HBox(12, text, spacer, playBtn);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(12));
        card.setStyle("-fx-background-color: #161b22; -fx-border-color: #30363d; -fx-border-radius: 8; -fx-background-radius: 8;");
        return card;
    }

    private void populateRecommendedServers() {
        recommendedContainer.getChildren().clear();
        List<String[]> dummy = List.of(
                new String[]{"Hypixel Network", "mc.hypixel.net", "Popular Minigames & SkyBlock"},
                new String[]{"Wynncraft", "play.wynncraft.net", "The Minecraft MMORPG"},
                new String[]{"Zircon Official", "mc.zircon.example.com:25565", "Official Mod-Synced Server"}
        );

        for (String[] rec : dummy) {
            Label nameLbl = new Label(rec[0]);
            nameLbl.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: white;");

            Label descLbl = new Label(rec[2] + " (" + rec[1] + ")");
            descLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #8b949e;");

            VBox text = new VBox(2, nameLbl, descLbl);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Button joinBtn = new Button("Add & Play");
            joinBtn.setStyle("-fx-background-color: #21262d; -fx-text-fill: #58a6ff; -fx-padding: 4 12; -fx-font-size: 11px;");
            joinBtn.setOnAction(e -> {
                SavedServer.recordPlayed(rec[0], rec[1]);
                populateServerList();
                launchServer(rec[0], rec[1]);
            });

            HBox card = new HBox(12, text, spacer, joinBtn);
            card.setAlignment(Pos.CENTER_LEFT);
            card.setPadding(new Insets(10, 12, 10, 12));
            card.setStyle("-fx-background-color: #0d1117; -fx-border-color: #21262d; -fx-border-radius: 8; -fx-background-radius: 8;");
            recommendedContainer.getChildren().add(card);
        }
    }

    private void promptAddServer() {
        TextInputDialog dialog = new TextInputDialog("localhost:25565");
        dialog.setTitle("Add Minecraft Server");
        dialog.setHeaderText("Connect to a Mod-Synced Minecraft Server");
        dialog.setContentText("Server Address (host:port):");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(addr -> {
            if (!addr.isBlank()) {
                SavedServer.recordPlayed("Custom Server", addr.trim());
                populateServerList();
                launchServer("Custom Server", addr.trim());
            }
        });
    }

    // ------------------------------------------------------------------
    // Skin Customizer
    // ------------------------------------------------------------------

    private void refreshSkinPreview() {
        Image customSkin = SkinManager.loadActiveSkinImage();
        if (customSkin != null) {
            skinPreview.setImage(customSkin);
            skinStatus.setText("Active Skin: Custom Upload (.PNG)");
        } else {
            skinPreview.setImage(null);
            skinStatus.setText("Active Skin: Default Steve / Alex");
        }
    }

    private void handleUploadSkin() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Minecraft Skin PNG");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG Images", "*.png"));
        File selected = chooser.showOpenDialog(stage);
        if (selected != null) {
            try {
                SkinManager.saveSkin(selected);
                refreshSkinPreview();
                status("Uploaded new skin: " + selected.getName());
            } catch (IOException e) {
                log.warn("Failed to save custom skin", e);
                status("Failed to save skin: " + e.getMessage());
            }
        }
    }

    private void handleResetSkin() {
        SkinManager.resetSkin();
        refreshSkinPreview();
        status("Reset skin to default.");
    }

    // ------------------------------------------------------------------
    // Launch Pipeline
    // ------------------------------------------------------------------

    private void launchServer(String name, String serverAddress) {
        if (gameProcess != null && gameProcess.isAlive()) {
            gameProcess.destroy();
            status("Game process stopped.");
            gameProcess = null;
            return;
        }
        if (busy.compareAndSet(false, true)) {
            SavedServer.recordPlayed(name, serverAddress);
            populateServerList();
            setBusyUi(true);
            Thread.ofVirtual().name("launcher-flow").start(() -> runFlow(serverAddress));
        }
    }

    private void runFlow(String serverAddress) {
        try {
            // 1. Authenticate (sign in / silent refresh)
            if (session == null) {
                status("Opening browser for Microsoft login...");
                session = auth.login();
            } else if (!offlineMode && session.isExpired()) {
                status("Renewing session...");
                try {
                    session = auth.refresh(session);
                } catch (Exception e) {
                    log.info("Silent refresh failed, re-authenticating: {}", e.getMessage());
                    session = auth.login();
                }
            }

            // 2. Parse the server address
            String[] hostPort = parseServerAddress(serverAddress);
            String host = hostPort[0];
            int port = Integer.parseInt(hostPort[1]);
            String baseUrl = "http://" + host + ":" + port;
            status((offlineMode ? "[OFFLINE MODE] " : "") + "Server: " + baseUrl);

            Path gameDir = instanceGameDir(host, String.valueOf(port));
            Files.createDirectories(gameDir);

            // 3. Fetch BOM
            BillOfMaterials bom = fetchBom(baseUrl);
            ModLoaderInfo loader = bom.getModLoader();

            // 4. Resolve Launch Environment
            status("Resolving Minecraft " + bom.getMinecraftVersion() + " runtime...");
            int requiredJava = JavaRuntimeSelector.getRequiredJavaMajorVersion(bom.getMinecraftVersion());
            MinecraftClasspathBuilder.LaunchData launchData =
                    classpathBuilder.resolve(bom.getMinecraftVersion(), loader, requiredJava);

            // 5. Sync Mods using Staging Area & Reconciler
            status("Checking mod hashes & synchronizing staging area...");
            Platform.runLater(() -> progressBar.setVisible(true));
            boolean strict = strictVerifyCheck.isSelected();
            boolean trustDirect = trustDirectCheck.isSelected();

            ModSyncEngine.SyncResult syncResult = syncEngine.sync(baseUrl, gameDir, strict, trustDirect,
                    new ModSyncEngine.ProgressListener() {
                        @Override
                        public void onStatus(String message) {
                            status(message);
                        }

                        @Override
                        public void onProgress(double fraction, String detail) {
                            progress(fraction);
                        }
                    });
            if (syncResult.aborted) {
                status("Sync aborted: " + syncResult.abortReason);
                return;
            }

            // 6. Launch Game
            status("Starting Minecraft process...");
            gameProcess = runner.launch(launchData, session, gameDir, host, port, null);
            status("Game running — connected to " + host + ":" + port);
            Thread.ofVirtual().name("game-wait").start(() -> {
                try {
                    int code = gameProcess.waitFor();
                    gameProcess = null;
                    Platform.runLater(() -> status("Game exited (code " + code + ")."));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } catch (Exception e) {
            log.error("Launcher flow failed", e);
            status("Error: " + e.getMessage());
        } finally {
            Platform.runLater(() -> {
                busy.set(false);
                setBusyUi(false);
            });
        }
    }

    private BillOfMaterials fetchBom(String baseUrl) throws IOException, InterruptedException {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(baseUrl + "/bom"))
                .GET()
                .build();
        java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("GET /bom failed: HTTP " + response.statusCode());
        }
        return com.mcmanager.core.model.BomJson.fromJson(response.body());
    }

    private static Path instanceGameDir(String host, String portOrName) {
        String safeHost = host.replaceAll("[^A-Za-z0-9._-]", "_");
        return INSTANCES_ROOT.resolve(safeHost + "_" + portOrName);
    }

    private String[] parseServerAddress(String input) {
        String address = input == null ? "" : input.trim();
        if (address.isEmpty()) {
            return new String[]{"localhost", DEFAULT_SERVER_PORT};
        }
        String host = address;
        String port = DEFAULT_SERVER_PORT;
        if (address.startsWith("[")) {
            int end = address.indexOf(']');
            if (end > 0) {
                host = address.substring(1, end);
                if (end + 1 < address.length() && address.charAt(end + 1) == ':') {
                    port = address.substring(end + 2);
                }
            }
        } else {
            int colon = address.lastIndexOf(':');
            if (colon > 0) {
                host = address.substring(0, colon);
                port = address.substring(colon + 1);
            }
        }
        return new String[]{host, port};
    }

    private void onLogout() {
        if (!offlineMode) {
            try {
                auth.clearCache();
            } catch (IOException e) {
                log.warn("Could not clear auth cache", e);
            }
        }
        session = null;
        userLabel.setText("Not signed in");
        logoutButton.setVisible(false);
        status("Signed out.");
    }

    private void status(String text) {
        Platform.runLater(() -> statusLabel.setText(text));
    }

    private void progress(double fraction) {
        Platform.runLater(() -> {
            progressBar.setProgress(fraction);
            progressBar.setVisible(true);
        });
    }

    private void setBusyUi(boolean busy) {
        Platform.runLater(() -> {
            progressBar.setProgress(busy ? ProgressBar.INDETERMINATE_PROGRESS : 0);
            progressBar.setVisible(busy);
        });
    }
}
```

---

## 🔨 Steps to Compile & Execute

### 1. Build the Entire Project
Run Gradle from the root of the repository:

```bash
./gradlew build
```

This compiles both shared modules, the server manager, and the client launcher.

---

### 2. Run the Refactored Client Launcher
To run the launcher in standard Microsoft Account mode:

```bash
./gradlew :client-launcher:run
```

To run the launcher in **Offline/Dev Mode** (skips Microsoft OAuth, connects to local servers with `online-mode=false`):

```bash
./gradlew :client-launcher:run --args="--offline --username=DevPlayer"
```

---

### 3. Verify All New Features

1. **Dynamic Mod Staging Verification**:
   - Join a server with mods.
   - Check `<instance>/.mod_staging/` to verify mods are downloaded there first.
   - Check `<instance>/mods/` to verify that active mods match the BOM and any manually placed extra files were purged.

2. **Server List**:
   - Click **+ Add Server** to add a server URL (`localhost:25565`).
   - Confirm the server entry is saved to `~/.mcmanager/servers.json` and sorted with the most recent play at the top.

3. **Skin Selection**:
   - Click **Change Skin** in the sidebar.
   - Select a 64x64 `.png` skin image.
   - Confirm the preview image displays the skin and `~/.mcmanager/skins/active_skin.png` is updated.

4. **Settings View**:
   - Navigate to **Settings** and adjust RAM allocation.
   - Confirm settings toggles (Strict Verification, Trust Direct Mods) adjust launch behavior correctly.
