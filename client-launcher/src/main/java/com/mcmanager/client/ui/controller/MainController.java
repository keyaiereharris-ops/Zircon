package com.mcmanager.client.ui.controller;

import com.mcmanager.client.auth.MicrosoftAuthService;
import com.mcmanager.client.auth.SessionData;
import com.mcmanager.client.launch.JavaRuntimeSelector;
import com.mcmanager.client.launch.MinecraftClasspathBuilder;
import com.mcmanager.client.launch.MinecraftRunner;
import com.mcmanager.client.model.SavedServer;
import com.mcmanager.client.offline.OfflineInstance;
import com.mcmanager.client.offline.OfflineInstanceManager;
import com.mcmanager.client.pack.ClientPackManager;
import com.mcmanager.client.pack.PackSelection;
import com.mcmanager.client.skin.BundledSkins;
import com.mcmanager.client.skin.MojangSkinService;
import com.mcmanager.client.skin.SkinManager;
import com.mcmanager.client.sync.ModSyncEngine;
import com.mcmanager.client.sync.PackSyncEngine;
import com.mcmanager.client.update.UpdateChecker;
import com.mcmanager.client.ui.component.Player3DRenderer;
import com.mcmanager.core.api.ModrinthApiClient;
import com.mcmanager.core.model.BillOfMaterials;
import com.mcmanager.core.model.BomJson;
import com.mcmanager.core.model.ModLoaderInfo;
import com.mcmanager.core.model.PackEntry;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.input.DragEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Controller driving the full Zircon launcher UI: Microsoft login overlay,
 * sidebar navigation, server sync &amp; launch, offline instance management,
 * skin history &amp; 3D preview, settings, and shader/texture pack syncing.
 */
public class MainController {

    private static final Logger log = LoggerFactory.getLogger(MainController.class);
    private static final String DEFAULT_SERVER_PORT = "25565";

    /** Offline packs combo value meaning "no shaderpack selected". */
    private static final String SHADERPACK_NONE = "None (shaders disabled)";

    private static final Path INSTANCES_ROOT = Path.of(
            System.getProperty("user.home"), ".zircon", "instances");

    private static final String NAV_STYLE = "-fx-font-size: 14px; -fx-padding: 10 14; -fx-background-radius: 8; "
            + "-fx-background-color: transparent; -fx-text-fill: #c9d1d9;";
    private static final String NAV_ACTIVE_STYLE = "-fx-font-size: 14px; -fx-padding: 10 14; -fx-background-radius: 8; "
            + "-fx-background-color: #21262d; -fx-text-fill: white; -fx-font-weight: bold;";
    private static final String PLAY_BTN_STYLE = "-fx-background-color: #47d2c9; -fx-text-fill: #022c29; "
            + "-fx-font-weight: bold; -fx-padding: 6 16; -fx-background-radius: 6;";
    private static final String PLAY_BTN_BUSY_STYLE = "-fx-background-color: #47d2c9; -fx-text-fill: #022c29; "
            + "-fx-font-weight: bold; -fx-padding: 6 16; -fx-background-radius: 6; -fx-graphic-text-gap: 6;";
    private static final String PLAY_BTN_DISABLED_STYLE = "-fx-background-color: #21262d; -fx-text-fill: #6e7681; "
            + "-fx-font-weight: bold; -fx-padding: 6 16; -fx-background-radius: 6;";

    // Login overlay & global chrome
    private final Button loginButton;
    private final Label loginStatus;
    private final Node loginView;
    private final Node mainLayout;
    private final Label userLabel;
    private final ImageView userAvatar;
    private final Button logoutButton;
    private final Label statusLabel;
    private final ProgressBar progressBar;
    private final Stage stage;

    // Navigation buttons & views
    private final Button navServers;
    private final Button navOffline;
    private final Button navSkins;
    private final Button navSettings;
    private final Node serverListView;
    private final Node offlineView;
    private final Node skinsView;
    private final Node settingsView;

    // Server list controls
    private final VBox savedServersContainer;
    private final VBox recommendedContainer;
    private final Button addServerBtn;

    // 3D player renderers
    private final Player3DRenderer serverRenderer;
    private final Player3DRenderer skinsRenderer;

    // Skin controls
    private final Button saveSkinBtn;
    private final Button removeSkinBtn;
    private final Label skinStatus;
    private final TilePane skinsGalleryContainer;

    // Offline instance controls
    private final VBox offlineInstancesContainer;
    private final Button newInstanceBtn;
    private final Label offlineDetailTitle;
    private final Label offlineVersionLabel;
    private final Label offlineLoaderLabel;
    private final Label offlineLoaderVersionLabel;
    private final VBox offlineModsContainer;
    private final VBox offlineDropZone;
    private final TextField modrinthQuery;
    private final Button modrinthSearchBtn;
    private final VBox modrinthResultsContainer;
    private final Button offlinePlayBtn;
    private final Button offlineDeleteBtn;
    private final ComboBox<String> offlineShaderpackCombo;
    private final VBox offlineShaderpackList;
    private final VBox offlineResourcepackContainer;
    private final Button offlineAddShaderpackBtn;
    private final Button offlineAddResourcepackBtn;
    private final TextField offlineShaderQuery;
    private final Button offlineShaderSearchBtn;
    private final VBox offlineShaderResultsContainer;
    private final TextField offlineTextureQuery;
    private final Button offlineTextureSearchBtn;
    private final VBox offlineTextureResultsContainer;

    // Settings controls
    private final Slider ramSlider;
    private final Label ramLabel;
    private final CheckBox strictVerifyCheck;
    private final CheckBox trustDirectCheck;

    private final MicrosoftAuthService auth = new MicrosoftAuthService();
    private final ModSyncEngine syncEngine = new ModSyncEngine();
    private final PackSyncEngine packSyncEngine = new PackSyncEngine();
    private final MinecraftClasspathBuilder classpathBuilder = new MinecraftClasspathBuilder();
    private final MinecraftRunner runner = new MinecraftRunner();
    private final ModrinthApiClient modrinth = new ModrinthApiClient();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile SessionData session;
    private volatile Process gameProcess;
    private volatile OfflineInstance selectedOfflineInstance;

    /** Modrinth project ids with an install download in flight (shows row spinners). */
    private final Set<String> installingProjects = ConcurrentHashMap.newKeySet();
    private final AtomicInteger modSearchSeq = new AtomicInteger();
    private final AtomicInteger shaderSearchSeq = new AtomicInteger();
    private final AtomicInteger textureSearchSeq = new AtomicInteger();
    private List<ModrinthApiClient.ModrinthSearchHit> modSearchHits = List.of();
    private List<ModrinthApiClient.ModrinthSearchHit> shaderSearchHits = List.of();
    private List<ModrinthApiClient.ModrinthSearchHit> textureSearchHits = List.of();

    /** Gallery selection: a {@link Path} string or one of the {@code SKIN_DEFAULT_*} keys. */
    private volatile String selectedSkinKey;

    /** True while a server launch flow is running — PLAY buttons render busy. */
    private volatile boolean launchingPlay;
    /** True while a game process is alive — PLAY buttons render greyed out. */
    private volatile boolean gameRunning;

    public MainController(Button loginButton, Label loginStatus, Node loginView, Node mainLayout,
                          Label userLabel, ImageView userAvatar, Button logoutButton,
                          Label statusLabel, ProgressBar progressBar, Stage stage,
                          Button navServers, Button navOffline, Button navSkins,
                          Button navSettings,
                          Node serverListView, Node offlineView, Node skinsView,
                          Node settingsView,
                          VBox savedServersContainer, VBox recommendedContainer, Button addServerBtn,
                          Player3DRenderer serverRenderer, Player3DRenderer skinsRenderer,
                          Button saveSkinBtn, Button removeSkinBtn, Label skinStatus,
                          TilePane skinsGalleryContainer,
                          VBox offlineInstancesContainer, Button newInstanceBtn,
                          Label offlineDetailTitle, Label offlineVersionLabel,
                          Label offlineLoaderLabel, Label offlineLoaderVersionLabel,
                          VBox offlineModsContainer, VBox offlineDropZone,
                          TextField modrinthQuery, Button modrinthSearchBtn,
                          VBox modrinthResultsContainer,
                          Button offlinePlayBtn, Button offlineDeleteBtn,
                          ComboBox<String> offlineShaderpackCombo, VBox offlineShaderpackList,
                          VBox offlineResourcepackContainer,
                          Button offlineAddShaderpackBtn, Button offlineAddResourcepackBtn,
                          TextField offlineShaderQuery, Button offlineShaderSearchBtn,
                          VBox offlineShaderResultsContainer,
                          TextField offlineTextureQuery, Button offlineTextureSearchBtn,
                          VBox offlineTextureResultsContainer,
                          Slider ramSlider, Label ramLabel, CheckBox strictVerifyCheck,
                          CheckBox trustDirectCheck) {
        this.loginButton = loginButton;
        this.loginStatus = loginStatus;
        this.loginView = loginView;
        this.mainLayout = mainLayout;
        this.userLabel = userLabel;
        this.userAvatar = userAvatar;
        this.logoutButton = logoutButton;
        this.statusLabel = statusLabel;
        this.progressBar = progressBar;
        this.stage = stage;

        this.navServers = navServers;
        this.navOffline = navOffline;
        this.navSkins = navSkins;
        this.navSettings = navSettings;
        this.serverListView = serverListView;
        this.offlineView = offlineView;
        this.skinsView = skinsView;
        this.settingsView = settingsView;

        this.savedServersContainer = savedServersContainer;
        this.recommendedContainer = recommendedContainer;
        this.addServerBtn = addServerBtn;

        this.serverRenderer = serverRenderer;
        this.skinsRenderer = skinsRenderer;

        this.saveSkinBtn = saveSkinBtn;
        this.removeSkinBtn = removeSkinBtn;
        this.skinStatus = skinStatus;
        this.skinsGalleryContainer = skinsGalleryContainer;

        this.offlineInstancesContainer = offlineInstancesContainer;
        this.newInstanceBtn = newInstanceBtn;
        this.offlineDetailTitle = offlineDetailTitle;
        this.offlineVersionLabel = offlineVersionLabel;
        this.offlineLoaderLabel = offlineLoaderLabel;
        this.offlineLoaderVersionLabel = offlineLoaderVersionLabel;
        this.offlineModsContainer = offlineModsContainer;
        this.offlineDropZone = offlineDropZone;
        this.modrinthQuery = modrinthQuery;
        this.modrinthSearchBtn = modrinthSearchBtn;
        this.modrinthResultsContainer = modrinthResultsContainer;
        this.offlinePlayBtn = offlinePlayBtn;
        this.offlineDeleteBtn = offlineDeleteBtn;
        this.offlineShaderpackCombo = offlineShaderpackCombo;
        this.offlineShaderpackList = offlineShaderpackList;
        this.offlineResourcepackContainer = offlineResourcepackContainer;
        this.offlineAddShaderpackBtn = offlineAddShaderpackBtn;
        this.offlineAddResourcepackBtn = offlineAddResourcepackBtn;
        this.offlineShaderQuery = offlineShaderQuery;
        this.offlineShaderSearchBtn = offlineShaderSearchBtn;
        this.offlineShaderResultsContainer = offlineShaderResultsContainer;
        this.offlineTextureQuery = offlineTextureQuery;
        this.offlineTextureSearchBtn = offlineTextureSearchBtn;
        this.offlineTextureResultsContainer = offlineTextureResultsContainer;

        this.ramSlider = ramSlider;
        this.ramLabel = ramLabel;
        this.strictVerifyCheck = strictVerifyCheck;
        this.trustDirectCheck = trustDirectCheck;
    }

    public void init() {
        navServers.setOnAction(e -> switchTab(serverListView, navServers));
        navOffline.setOnAction(e -> switchTab(offlineView, navOffline));
        navSkins.setOnAction(e -> switchTab(skinsView, navSkins));
        navSettings.setOnAction(e -> switchTab(settingsView, navSettings));
        switchTab(serverListView, navServers);

        // Login overlay & auth
        loginButton.setOnAction(e -> handleLogin());
        logoutButton.setOnAction(e -> onLogout());
        initSession();

        // Servers
        addServerBtn.setOnAction(e -> promptAddServer());
        populateServerList();
        populateRecommendedServers();
        refreshSavedServerNames();

        // Skins
        refreshPlayerSkins();
        initSkinSelection();
        populateSkinsGallery();
        saveSkinBtn.setOnAction(e -> handleSaveSkin());
        removeSkinBtn.setOnAction(e -> handleRemoveSkin());

        // Offline instances
        populateOfflineInstances();
        newInstanceBtn.setOnAction(e -> promptNewInstance());
        offlinePlayBtn.setOnAction(e -> handleOfflinePlay());
        offlineDeleteBtn.setOnAction(e -> handleOfflineDelete());
        offlineDropZone.setOnMouseClicked(this::browseOfflineMods);
        offlineDropZone.setOnDragOver(this::onOfflineDragOver);
        offlineDropZone.setOnDragDropped(this::onOfflineDrop);
        modrinthSearchBtn.setOnAction(e -> handleModrinthSearch());
        modrinthQuery.setOnAction(e -> handleModrinthSearch());

        // Offline shaders & texture packs
        offlineShaderpackCombo.setOnAction(e -> persistOfflineShaderpack());
        offlineAddShaderpackBtn.setOnAction(e -> handleOfflineAddPack(true));
        offlineAddResourcepackBtn.setOnAction(e -> handleOfflineAddPack(false));
        offlineShaderSearchBtn.setOnAction(e -> handleShaderSearch());
        offlineShaderQuery.setOnAction(e -> handleShaderSearch());
        offlineTextureSearchBtn.setOnAction(e -> handleTextureSearch());
        offlineTextureQuery.setOnAction(e -> handleTextureSearch());

        // Settings
        ramSlider.valueProperty().addListener((obs, oldVal, newVal) ->
                ramLabel.setText("Max Memory Allocation (RAM): " + newVal.intValue() + " GB"));

        // Auto-update check (async; never blocks startup)
        UpdateChecker.checkForUpdatesAsync(manifest -> {
            Platform.runLater(() -> promptUpdateDialog(manifest));
        });
    }

    // ------------------------------------------------------------------
    // Navigation & auth
    // ------------------------------------------------------------------

    private void switchTab(Node targetView, Button activeBtn) {
        serverListView.setVisible(targetView == serverListView);
        offlineView.setVisible(targetView == offlineView);
        skinsView.setVisible(targetView == skinsView);
        settingsView.setVisible(targetView == settingsView);

        for (Button btn : new Button[]{navServers, navOffline, navSkins, navSettings}) {
            btn.setStyle(btn == activeBtn ? NAV_ACTIVE_STYLE : NAV_STYLE);
        }

        if (targetView == serverListView) {
            refreshPlayerSkins();
        } else if (targetView == skinsView) {
            refreshPlayerSkins();
            populateSkinsGallery();
        } else if (targetView == offlineView) {
            populateOfflineInstances();
        }
    }

    private void initSession() {
        session = auth.loadCached();
        if (session != null) {
            onSessionEstablished();
        } else {
            showLoginView(true);
        }
    }

    private void showLoginView(boolean show) {
        loginView.setVisible(show);
    }

    private void handleLogin() {
        if (busy.compareAndSet(false, true)) {
            loginButton.setDisable(true);
            loginStatus.setText("Opening browser for Microsoft login...");
            setBusyUi(true);
            Thread.ofVirtual().name("login").start(() -> {
                try {
                    session = auth.login();
                    Platform.runLater(this::onSessionEstablished);
                } catch (Exception e) {
                    log.error("Microsoft login failed", e);
                    Platform.runLater(() -> {
                        loginStatus.setText("Login failed: " + describeError(e));
                        loginButton.setDisable(false);
                    });
                } finally {
                    Platform.runLater(() -> {
                        busy.set(false);
                        setBusyUi(false);
                    });
                }
            });
        }
    }

    private void onSessionEstablished() {
        userLabel.setText(session.getUsername());
        logoutButton.setVisible(true);
        loginButton.setDisable(false);
        loginStatus.setText("");
        showLoginView(false);
        status("Signed in as " + session.getUsername());
        refreshPlayerSkins();

        // Automatically sync the active Mojang skin in the background so the 3D
        // previews and sidebar always show the player's real skin.
        autoFetchMojangSkin();
    }

    /**
     * Downloads the player's active Mojang skin and applies it across all 3D
     * previews and the sidebar, with an indeterminate progress spinner while
     * fetching. Never blocks the FX thread.
     */
    private void autoFetchMojangSkin() {
        if (session == null || session.getUuid() == null || session.getUuid().isBlank()) {
            return;
        }
        status("Syncing active skin from Mojang...");
        progressBar.setVisible(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        Thread.ofVirtual().name("mojang-auto-skin").start(() -> {
            try {
                MojangSkinService.DownloadedSkin skin = MojangSkinService.download(session.getUuid());
                // Sync straight to the active skin file — never archive into the
                // gallery history, or every launch/remove would add yet another
                // duplicate of the player's current skin.
                Path activeSkin = SkinManager.getActiveSkinPath();
                Files.createDirectories(activeSkin.getParent());
                Files.write(activeSkin, skin.png());
                Platform.runLater(() -> {
                    refreshPlayerSkins();
                    selectedSkinKey = activeSkin.toString();
                    updateSkinActionStates();
                    skinStatus.setText("Active Skin: Mojang (" + skin.variant() + ")");
                    status("Active Mojang skin synced.");
                    progressBar.setVisible(false);
                });
            } catch (Exception e) {
                log.warn("Could not auto-fetch Mojang skin: {}", e.getMessage());
                Platform.runLater(() -> {
                    refreshPlayerSkins();
                    progressBar.setVisible(false);
                });
            }
        });
    }

    public void shutdown() {
        if (gameProcess != null && gameProcess.isAlive()) {
            gameProcess.destroy();
        }
        com.mcmanager.client.render.GlContext.instance().dispose();
    }

    // ------------------------------------------------------------------
    // Server list management
    // ------------------------------------------------------------------

    private void populateServerList() {
        savedServersContainer.getChildren().clear();
        List<SavedServer> saved = SavedServer.load();
        if (saved.isEmpty()) {
            SavedServer.recordPlayed("Localhost Server", "localhost:25565");
            saved = SavedServer.load();
        }
        for (SavedServer s : saved) {
            savedServersContainer.getChildren().add(createSavedServerCard(s));
        }
    }

    /**
     * Re-fetches each saved server's BOM title in the background and updates the
     * recorded name, so "Your Servers" shows the server's real name (the one its
     * owner set in the web app) even when it was saved under a placeholder like
     * "Custom Server". Runs on a virtual thread; unreachable servers keep their
     * current name and the UI is never blocked.
     */
    private void refreshSavedServerNames() {
        List<SavedServer> saved = SavedServer.load();
        if (saved.isEmpty()) {
            return;
        }
        Thread.ofVirtual().name("server-name-refresh").start(() -> {
            boolean changed = false;
            for (SavedServer s : saved) {
                try {
                    String[] hostPort = parseServerAddress(s.getAddress());
                    String title = fetchBomTitle("http://" + hostPort[0] + ":" + hostPort[1]);
                    if (title != null && !title.isBlank() && !title.equals(s.getName())) {
                        s.setName(title.trim());
                        changed = true;
                    }
                } catch (Exception e) {
                    log.debug("Could not refresh name for {}: {}", s.getAddress(), e.getMessage());
                }
            }
            if (changed) {
                SavedServer.save(saved);
                Platform.runLater(this::populateServerList);
            }
        });
    }

    /**
     * Fetches just the title advertised by {@code GET /bom} with a short connect
     * timeout, so a background name refresh never stalls on an unreachable host.
     * Returns {@code null} when the server is unreachable or has no title.
     */
    private String fetchBomTitle(String baseUrl) throws IOException, InterruptedException {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(3))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(baseUrl + "/bom"))
                .GET()
                .build();
        java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            return null;
        }
        BillOfMaterials bom = BomJson.fromJson(response.body());
        return bom == null ? null : bom.getServerTitle();
    }

    private HBox createSavedServerCard(SavedServer server) {
        Label badge = serverBadge(server.getName());
        Label nameLbl = new Label(server.getName());
        nameLbl.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: white;");

        Label addrLbl = new Label(server.getAddress());
        addrLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #8b949e;");

        VBox text = new VBox(2, nameLbl, addrLbl);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button playBtn = new Button("PLAY");
        if (launchingPlay) {
            playBtn.setDisable(true);
            playBtn.setStyle(PLAY_BTN_BUSY_STYLE);
            playBtn.setGraphic(spinner(14));
        } else if (gameRunning) {
            playBtn.setDisable(true);
            playBtn.setStyle(PLAY_BTN_DISABLED_STYLE);
        } else {
            playBtn.setStyle(PLAY_BTN_STYLE);
        }
        playBtn.setOnAction(e -> launchServer(server.getName(), server.getAddress()));

        HBox card = new HBox(12, badge, text, spacer, playBtn);
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
            Label badge = serverBadge(rec[0]);
            Label nameLbl = new Label(rec[0]);
            nameLbl.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: white;");

            Label descLbl = new Label(rec[2] + " (" + rec[1] + ")");
            descLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #8b949e;");

            VBox text = new VBox(2, nameLbl, descLbl);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Button joinBtn = new Button("Add & Play");
            joinBtn.setStyle("-fx-background-color: #21262d; -fx-text-fill: #47d2c9; -fx-padding: 4 12; -fx-font-size: 11px;");
            joinBtn.setOnAction(e -> {
                SavedServer.recordPlayed(rec[0], rec[1]);
                populateServerList();
                launchServer(rec[0], rec[1]);
            });

            HBox card = new HBox(12, badge, text, spacer, joinBtn);
            card.setAlignment(Pos.CENTER_LEFT);
            card.setPadding(new Insets(10, 12, 10, 12));
            card.setStyle("-fx-background-color: #0d1117; -fx-border-color: #21262d; -fx-border-radius: 8; -fx-background-radius: 8;");
            recommendedContainer.getChildren().add(card);
        }
    }

    private static Label serverBadge(String name) {
        String initial = name == null || name.isBlank() ? "?" : name.substring(0, 1).toUpperCase();
        Label badge = new Label(initial);
        badge.setMinSize(30, 30);
        badge.setMaxSize(30, 30);
        badge.setAlignment(Pos.CENTER);
        badge.setStyle("-fx-background-color: #47d2c9; -fx-text-fill: #022c29; -fx-font-weight: bold; "
                + "-fx-background-radius: 15; -fx-font-size: 13px;");
        return badge;
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

    // ------------------------------------------------------------------
    // Skin preview, history & 3D binding
    // ------------------------------------------------------------------

    private void refreshPlayerSkins() {
        Image active = SkinManager.loadActiveSkinImage();
        if (active == null) {
            active = BundledSkins.fallback().map(BundledSkins.Skin::image).orElse(null);
        }
        if (active == null) {
            return; // no custom skin and no bundled defaults — nothing to preview
        }
        serverRenderer.updateSkin(active);
        skinsRenderer.updateSkin(active);
        userAvatar.setImage(SkinManager.extractHeadIconScaled(active, 4));
    }

    private void populateSkinsGallery() {
        skinsGalleryContainer.getChildren().clear();
        skinsGalleryContainer.getChildren().add(skinCardAdd());
        // Bundled defaults: every PNG shipped in the skins/ resources folder.
        for (BundledSkins.Skin skin : BundledSkins.all()) {
            String key = BundledSkins.key(skin.fileName());
            skinsGalleryContainer.getChildren().add(skinCard(skin.image(), skin.label(),
                    key, () -> selectSkin(key)));
        }

        Image activeImage = SkinManager.loadActiveSkinImage();
        boolean activeInHistory = false;
        for (Path path : SkinManager.getSkinHistory()) {
            Image skin = SkinManager.loadImage(path);
            if (skin == null) {
                continue;
            }
            skinsGalleryContainer.getChildren().add(skinCard(skin, path.getFileName().toString(),
                    path.toString(), () -> selectSkinFile(path)));
            if (activeImage != null && sameImage(skin, activeImage)) {
                activeInHistory = true;
            }
        }
        // The active skin is not part of history (e.g. it was synced straight from
        // Mojang), so surface it as its own card — otherwise the equipped skin
        // could never be highlighted in the gallery.
        if (activeImage != null && !activeInHistory) {
            Path active = SkinManager.getActiveSkinPath();
            skinsGalleryContainer.getChildren().add(skinCard(activeImage, "Current Skin",
                    active.toString(), () -> selectSkinFile(active)));
        }

        selectedSkinKey = resolveActiveSkinKey();
        updateSelectionHighlight();
    }

    /**
     * Resolves the gallery key of the currently equipped skin so it gets the
     * selection highlight every time the Skins tab is opened: the matching
     * history card, the active-skin card, or the first bundled default when no
     * custom skin is set.
     */
    private String resolveActiveSkinKey() {
        if (!SkinManager.hasCustomSkin()) {
            return BundledSkins.fallback()
                    .map(s -> BundledSkins.key(s.fileName()))
                    .orElse(null);
        }
        Image active = SkinManager.loadActiveSkinImage();
        if (active != null) {
            for (Path path : SkinManager.getSkinHistory()) {
                Image skin = SkinManager.loadImage(path);
                if (skin != null && sameImage(skin, active)) {
                    return path.toString();
                }
            }
        }
        return SkinManager.getActiveSkinPath().toString();
    }

    /** The "Add Skin" tile: imports a PNG into the gallery history and selects it. */
    private VBox skinCardAdd() {
        Label plus = new Label("+");
        plus.setStyle("-fx-font-size: 34px; -fx-text-fill: white; -fx-font-weight: bold;");
        Label caption = new Label("Add Skin");
        caption.setStyle("-fx-font-size: 11px; -fx-text-fill: #8b949e;");

        VBox card = new VBox(6, plus, caption);
        card.setAlignment(Pos.CENTER);
        card.setPrefSize(100, 116);
        card.setPadding(new Insets(10));
        card.setStyle(cardStyle(false));
        card.setOnMouseClicked(e -> handleAddSkin());
        return card;
    }

    /**
     * A selectable gallery card: head-icon preview, caption, and an emerald border
     * when it is the currently selected skin. Clicking previews only; the SAVE
     * button persists.
     */
    private VBox skinCard(Image skin, String label, String key, Runnable onClick) {
        ImageView view = new ImageView(SkinManager.extractHeadIconScaled(skin, 6));
        view.setFitWidth(64);
        view.setFitHeight(64);
        view.setPreserveRatio(true);
        view.setSmooth(false);

        Label caption = new Label(label);
        caption.setMaxWidth(96);
        caption.setStyle("-fx-font-size: 10px; -fx-text-fill: #8b949e; -fx-text-overrun: ellipsis;");

        VBox card = new VBox(6, view, caption);
        card.setAlignment(Pos.CENTER);
        card.setPrefSize(100, 116);
        card.setPadding(new Insets(10));
        card.setStyle(cardStyle(false));
        card.setUserData(key);
        card.setOnMouseClicked(e -> {
            selectedSkinKey = key;
            onClick.run();
            updateSelectionHighlight();
        });
        return card;
    }

    private static String cardStyle(boolean active) {
        return "-fx-background-color: #0d1117; -fx-border-color: " + (active ? "#47d2c9" : "#21262d")
                + "; -fx-border-width: 2; -fx-border-radius: 10; -fx-background-radius: 10; -fx-cursor: hand;";
    }

    /** Applies the emerald selection border and the Remove button state for the selection. */
    private void updateSelectionHighlight() {
        for (Node card : skinsGalleryContainer.getChildren()) {
            if (card.getUserData() instanceof String key) {
                card.setStyle(cardStyle(key.equals(selectedSkinKey)));
            }
        }
        updateSkinActionStates();
    }

    /**
     * The Remove button is only usable when a non-bundled skin that is not the
     * currently active skin is selected (bundled defaults can't be removed).
     */
    private void updateSkinActionStates() {
        boolean removable = selectedSkinKey != null
                && !BundledSkins.isBundled(selectedSkinKey)
                && !isActiveSkin(selectedSkinKey);
        removeSkinBtn.setDisable(!removable);
    }

    /** @return true when the given skin file is pixel-identical to the active skin. */
    private boolean isActiveSkin(String key) {
        Path file = Path.of(key);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        return sameImage(SkinManager.loadImage(file), SkinManager.loadActiveSkinImage());
    }

    private static boolean sameImage(Image a, Image b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return false;
        }
        PixelReader ra = a.getPixelReader();
        PixelReader rb = b.getPixelReader();
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (ra.getArgb(x, y) != rb.getArgb(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Pre-selects the active skin (or the first bundled default) when the gallery first opens. */
    private void initSkinSelection() {
        selectedSkinKey = SkinManager.hasCustomSkin()
                ? SkinManager.getActiveSkinPath().toString()
                : BundledSkins.fallback().map(s -> BundledSkins.key(s.fileName())).orElse(null);
    }

    /** Previews a bundled default skin in all previews without persisting (SAVE commits). */
    private void selectSkin(String key) {
        BundledSkins.byKey(key).ifPresent(skin -> {
            Image image = skin.image();
            serverRenderer.updateSkin(image);
            skinsRenderer.updateSkin(image);
            skinStatus.setText("Preview: " + skin.label() + " — press SAVE to activate.");
        });
    }

    /** Previews a skin file in all previews without persisting (SAVE commits). */
    private void selectSkinFile(Path path) {
        Image image = SkinManager.loadImage(path);
        if (image == null) {
            return;
        }
        serverRenderer.updateSkin(image);
        skinsRenderer.updateSkin(image);
        skinStatus.setText("Preview: " + path.getFileName() + " — press SAVE to activate.");
    }

    /**
     * Removes the selected skin from the gallery list and reverts the player to
     * their Mojang skin. The currently active skin can never be removed (the
     * button is disabled for it), so the active skin file stays consistent.
     */
    private void handleRemoveSkin() {
        if (selectedSkinKey == null
                || BundledSkins.isBundled(selectedSkinKey)
                || isActiveSkin(selectedSkinKey)) {
            return;
        }
        Path file = Path.of(selectedSkinKey);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            status("Failed to remove skin: " + e.getMessage());
            return;
        }
        status("Removed skin: " + file.getFileName());
        populateSkinsGallery();

        // Revert the player to their Mojang skin; when signed out, just restore
        // the preview to the current active skin.
        if (session != null && session.getUuid() != null && !session.getUuid().isBlank()) {
            autoFetchMojangSkin();
        } else {
            refreshPlayerSkins();
            status("Skin removed — sign in to sync your Mojang skin.");
        }
    }

    /**
     * SAVE: persists the selected skin locally and, when signed in, uploads it to
     * Mojang so it follows the player everywhere. Bundled defaults are real PNGs,
     * so they are copied to the active skin just like any other file skin.
     */
    private void handleSaveSkin() {
        if (selectedSkinKey == null) {
            status("Select a skin first.");
            return;
        }
        if (BundledSkins.isBundled(selectedSkinKey)) {
            BundledSkins.byKey(selectedSkinKey).ifPresent(this::activateBundledSkin);
            updateSkinActionStates();
            return;
        }
        Path file = Path.of(selectedSkinKey);
        if (!Files.isRegularFile(file)) {
            status("Selected skin file is missing: " + file.getFileName());
            return;
        }
        saveAndUploadSkin(file);
        updateSkinActionStates();
    }

    /** Copies a bundled default skin PNG to the active local skin, then uploads to Mojang. */
    private void activateBundledSkin(BundledSkins.Skin skin) {
        try (InputStream in = BundledSkins.open(skin)) {
            if (in == null) {
                status("Bundled skin file missing: " + skin.fileName());
                return;
            }
            Files.createDirectories(SkinManager.getActiveSkinPath().getParent());
            Files.copy(in, SkinManager.getActiveSkinPath(), StandardCopyOption.REPLACE_EXISTING);
            refreshPlayerSkins();
            skinStatus.setText("Active Skin: " + skin.label());
            status("Skin saved locally.");
        } catch (IOException e) {
            log.warn("Failed to activate bundled skin", e);
            status("Failed to save skin: " + e.getMessage());
            return;
        }
        uploadActiveSkin();
    }

    /** Copies the selected skin to the active local skin, then uploads to Mojang. */
    private void saveAndUploadSkin(Path file) {
        try {
            Files.createDirectories(SkinManager.getActiveSkinPath().getParent());
            Files.copy(file, SkinManager.getActiveSkinPath(), StandardCopyOption.REPLACE_EXISTING);
            refreshPlayerSkins();
            skinStatus.setText("Active Skin: " + file.getFileName());
            status("Skin saved locally.");
        } catch (IOException e) {
            log.warn("Failed to save skin", e);
            status("Failed to save skin: " + e.getMessage());
            return;
        }
        uploadActiveSkin();
    }

    /** Uploads the active skin file to Mojang when signed in; no-op otherwise. */
    private void uploadActiveSkin() {
        if (session == null || session.getAccessToken() == null || session.getAccessToken().isBlank()) {
            status("Skin saved locally — sign in to also upload it to Mojang.");
            return;
        }
        Thread.ofVirtual().name("mojang-skin-save").start(() -> {
            try {
                SessionData fresh = ensureFreshSession();
                MojangSkinService.upload(fresh.getAccessToken(), SkinManager.getActiveSkinPath(), "classic");
                Platform.runLater(() -> status("Skin saved & uploaded to Mojang."));
            } catch (Exception e) {
                log.warn("Mojang skin upload failed", e);
                Platform.runLater(() -> status("Saved locally, but Mojang upload failed: " + describeError(e)));
            }
        });
    }

    /** Add Skin card: imports a PNG into the gallery history and selects it. */
    private void handleAddSkin() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Minecraft Skin PNG");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG Images", "*.png"));
        File selected = chooser.showOpenDialog(stage);
        if (selected == null) {
            return;
        }
        try {
            // Import into the gallery history without activating; SAVE commits.
            SkinManager.saveToHistory(selected);
            List<Path> history = SkinManager.getSkinHistory();
            if (!history.isEmpty()) {
                Path archived = history.get(0); // newest entry is the one just added
                selectedSkinKey = archived.toString();
                selectSkinFile(archived);
            }
            populateSkinsGallery();
            status("Skin added to gallery — press SAVE to activate & upload to Mojang.");
        } catch (IOException e) {
            log.warn("Failed to add skin", e);
            status("Failed to add skin: " + e.getMessage());
        }
    }

    /**
     * Returns a valid session, signing in or silently refreshing the Microsoft /
     * Minecraft tokens if the cached one is missing or expired.
     */
    private SessionData ensureFreshSession() throws IOException, InterruptedException {
        if (session == null) {
            status("Opening browser for Microsoft login...");
            session = auth.login();
        } else if (session.isExpired()) {
            status("Renewing session...");
            try {
                session = auth.refresh(session);
            } catch (Exception e) {
                log.info("Silent refresh failed, re-authenticating: {}", e.getMessage());
                session = auth.login();
            }
        }
        return session;
    }

    // ------------------------------------------------------------------
    // Offline instances
    // ------------------------------------------------------------------

    private void populateOfflineInstances() {
        offlineInstancesContainer.getChildren().clear();
        List<OfflineInstance> instances = OfflineInstanceManager.loadAll();
        for (OfflineInstance instance : instances) {
            offlineInstancesContainer.getChildren().add(createOfflineInstanceCard(instance));
        }

        if (instances.isEmpty()) {
            renderOfflineDetail(null);
            return;
        }

        OfflineInstance toSelect = instances.get(0);
        if (selectedOfflineInstance != null) {
            for (OfflineInstance instance : instances) {
                if (instance.getId().equals(selectedOfflineInstance.getId())) {
                    toSelect = instance;
                    break;
                }
            }
        }
        renderOfflineDetail(toSelect);
        updateInstanceCardHighlight();
    }

    private HBox createOfflineInstanceCard(OfflineInstance instance) {
        Label nameLbl = new Label(instance.getName());
        nameLbl.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: white;");

        Label versionBadge = new Label(instance.getMinecraftVersion());
        versionBadge.setStyle("-fx-background-color: #21262d; -fx-text-fill: #47d2c9; "
                + "-fx-font-size: 10px; -fx-padding: 2 6; -fx-background-radius: 6;");

        HBox badges = new HBox(6, versionBadge);
        VBox text = new VBox(4, nameLbl, badges);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button playBtn = new Button("PLAY");
        playBtn.setStyle("-fx-background-color: #47d2c9; -fx-text-fill: #022c29; -fx-font-weight: bold; -fx-padding: 4 12; -fx-font-size: 11px;");
        playBtn.setOnAction(e -> {
            renderOfflineDetail(instance);
            updateInstanceCardHighlight();
            handleOfflinePlay();
        });

        HBox card = new HBox(12, text, spacer, playBtn);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(12));
        card.setStyle(offlineCardStyle(false));
        card.setUserData(instance.getId());
        card.setOnMouseClicked(e -> {
            renderOfflineDetail(instance);
            updateInstanceCardHighlight();
        });
        return card;
    }

    private static String offlineCardStyle(boolean active) {
        return "-fx-background-color: #161b22; -fx-border-color: " + (active ? "#47d2c9" : "#30363d")
                + "; -fx-border-width: 2; -fx-border-radius: 8; -fx-background-radius: 8; -fx-cursor: hand;";
    }

    /** Rings the card of the currently selected offline instance. */
    private void updateInstanceCardHighlight() {
        String selectedId = selectedOfflineInstance == null ? null : selectedOfflineInstance.getId();
        for (Node card : offlineInstancesContainer.getChildren()) {
            if (card.getUserData() instanceof String id) {
                card.setStyle(offlineCardStyle(id.equals(selectedId)));
            }
        }
    }

    private void renderOfflineDetail(OfflineInstance instance) {
        selectedOfflineInstance = instance;
        if (instance == null) {
            offlineDetailTitle.setText("Select an instance");
            offlineVersionLabel.setText("Minecraft: —");
            offlineLoaderLabel.setText("Loader: —");
            offlineLoaderVersionLabel.setText("Loader version: —");
            offlineModsContainer.getChildren().clear();
            offlineShaderpackCombo.getItems().clear();
            offlineShaderpackCombo.setValue(null);
            offlineShaderpackList.getChildren().clear();
            offlineResourcepackContainer.getChildren().clear();
            offlinePlayBtn.setDisable(true);
            offlineDeleteBtn.setDisable(true);
            return;
        }

        offlineDetailTitle.setText(instance.getName());
        offlineVersionLabel.setText("Minecraft: " + instance.getMinecraftVersion());
        offlineLoaderLabel.setText("Loader: " + instance.getModLoader().getType());
        offlineLoaderVersionLabel.setText("Loader version: " + defaultString(instance.getModLoader().getVersion(), ""));
        offlinePlayBtn.setDisable(false);
        offlineDeleteBtn.setDisable(false);
        renderOfflineMods(instance);
        renderOfflinePacks(instance);
    }

    private void renderOfflineMods(OfflineInstance instance) {
        offlineModsContainer.getChildren().clear();
        List<Path> mods = OfflineInstanceManager.listMods(instance);
        if (mods.isEmpty()) {
            offlineModsContainer.getChildren().add(infoLabel("No mods yet — drop files or search Modrinth."));
            return;
        }
        for (Path mod : mods) {
            offlineModsContainer.getChildren().add(createInstalledModRow(mod.getFileName().toString()));
        }
    }

    /** A single installed mod row: filename + delete button. */
    private HBox createInstalledModRow(String filename) {
        Label name = new Label(filename);
        name.setStyle("-fx-font-size: 12px; -fx-text-fill: #c9d1d9;");
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);
        HBox row = new HBox(8, name, createDeleteButton(() -> deleteInstalledMod(filename)));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void deleteInstalledMod(String filename) {
        OfflineInstance instance = selectedOfflineInstance;
        if (instance == null) {
            return;
        }
        try {
            OfflineInstanceManager.deleteMod(instance, filename);
            renderOfflineMods(instance);
            status("Removed " + filename);
        } catch (IOException e) {
            status("Could not remove " + filename + ": " + e.getMessage());
        }
    }

    /**
     * Fills the offline shaderpack combo, the installed shaderpack list and the
     * texture-pack rows (enable checkbox + delete) from the instance's
     * {@code pack-selection.json} and local pack folders.
     */
    private void renderOfflinePacks(OfflineInstance instance) {
        Path gameDir = OfflineInstanceManager.instanceDir(instance.getId());
        PackSelection selection = PackSelection.load(gameDir);

        offlineShaderpackCombo.getItems().clear();
        offlineShaderpackCombo.getItems().add(SHADERPACK_NONE);
        offlineShaderpackCombo.getItems().addAll(listPackFiles(gameDir.resolve("shaderpacks")));
        boolean shadersOn = selection.isShadersEnabled() && selection.getActiveShaderpack() != null;
        offlineShaderpackCombo.setValue(shadersOn ? selection.getActiveShaderpack() : SHADERPACK_NONE);

        offlineShaderpackList.getChildren().clear();
        List<String> shaderpacks = listPackFiles(gameDir.resolve("shaderpacks"));
        if (shaderpacks.isEmpty()) {
            offlineShaderpackList.getChildren().add(infoLabel("No shaderpacks installed."));
        } else {
            for (String filename : shaderpacks) {
                offlineShaderpackList.getChildren().add(createInstalledPackRow(filename, true));
            }
        }

        offlineResourcepackContainer.getChildren().clear();
        List<String> packs = listPackFiles(gameDir.resolve("resourcepacks"));
        if (packs.isEmpty()) {
            offlineResourcepackContainer.getChildren().add(infoLabel("No texture packs installed."));
        } else {
            for (String filename : packs) {
                CheckBox cb = new CheckBox(filename);
                cb.setStyle("-fx-text-fill: #c9d1d9; -fx-font-size: 12px;");
                cb.setSelected(selection.getActiveResourcepacks().contains(filename));
                cb.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(cb, Priority.ALWAYS);
                cb.setOnAction(e -> {
                    if (cb.isSelected()) {
                        if (!selection.getActiveResourcepacks().contains(filename)) {
                            selection.getActiveResourcepacks().add(filename);
                        }
                    } else {
                        selection.getActiveResourcepacks().remove(filename);
                    }
                    selection.save(gameDir);
                });
                HBox row = new HBox(8, cb, createDeleteButton(() -> deleteInstalledPack(filename, false)));
                row.setAlignment(Pos.CENTER_LEFT);
                offlineResourcepackContainer.getChildren().add(row);
            }
        }
    }

    /** A single installed pack row: filename + delete button. */
    private HBox createInstalledPackRow(String filename, boolean shader) {
        Label name = new Label(filename);
        name.setStyle("-fx-font-size: 12px; -fx-text-fill: #c9d1d9;");
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);
        HBox row = new HBox(8, name, createDeleteButton(() -> deleteInstalledPack(filename, shader)));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Small red "✕" button that runs {@code action}. */
    private static Button createDeleteButton(Runnable action) {
        Button btn = new Button("✕");
        btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #f85149; -fx-font-size: 12px; -fx-padding: 0 6; -fx-cursor: hand;");
        btn.setOnAction(e -> action.run());
        return btn;
    }

    private void deleteInstalledPack(String filename, boolean shader) {
        OfflineInstance instance = selectedOfflineInstance;
        if (instance == null) {
            return;
        }
        try {
            Path gameDir = OfflineInstanceManager.instanceDir(instance.getId());
            PackSelection selection = PackSelection.load(gameDir);
            if (shader) {
                ClientPackManager.removeShaderpack(gameDir, filename, selection);
            } else {
                ClientPackManager.removeResourcepack(gameDir, filename, selection);
            }
            renderOfflinePacks(instance);
            status("Removed " + filename);
        } catch (IOException e) {
            status("Could not remove " + filename + ": " + e.getMessage());
        }
    }

    /** Persists the offline shaderpack combo choice to {@code pack-selection.json}. */
    private void persistOfflineShaderpack() {
        OfflineInstance instance = selectedOfflineInstance;
        if (instance == null) {
            return;
        }
        Path gameDir = OfflineInstanceManager.instanceDir(instance.getId());
        PackSelection selection = PackSelection.load(gameDir);
        String value = offlineShaderpackCombo.getValue();
        if (value == null || SHADERPACK_NONE.equals(value)) {
            selection.setShadersEnabled(false);
            selection.setActiveShaderpack(null);
        } else {
            selection.setShadersEnabled(true);
            selection.setActiveShaderpack(value);
        }
        selection.save(gameDir);
    }

    /** Adds local {@code .zip} shaderpacks/resourcepacks to the selected offline instance. */
    private void handleOfflineAddPack(boolean shader) {
        OfflineInstance instance = selectedOfflineInstance;
        if (instance == null) {
            status("Select an instance first.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(shader ? "Select Shaderpack (.zip)" : "Select Texture Pack (.zip)");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP Archives", "*.zip"));
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files == null || files.isEmpty()) {
            return;
        }
        try {
            Path gameDir = OfflineInstanceManager.instanceDir(instance.getId());
            PackSelection selection = PackSelection.load(gameDir);
            for (File file : files) {
                if (shader) {
                    ClientPackManager.addLocalShaderpack(gameDir, file, selection);
                } else {
                    ClientPackManager.addLocalResourcepack(gameDir, file, selection);
                }
            }
            renderOfflinePacks(instance);
            status("Added " + files.size() + " local " + (shader ? "shaderpack(s)." : "texture pack(s)."));
        } catch (IOException e) {
            status("Failed to add local pack: " + e.getMessage());
        }
    }

    /** Loader types the launcher can actually launch, in display order. */
    private static final LinkedHashMap<String, String> SUPPORTED_LOADERS = new LinkedHashMap<>();

    static {
        SUPPORTED_LOADERS.put("vanilla", "Vanilla");
        SUPPORTED_LOADERS.put("fabric", "Fabric");
        SUPPORTED_LOADERS.put("forge", "Forge");
        SUPPORTED_LOADERS.put("neoforge", "NeoForge");
        SUPPORTED_LOADERS.put("quilt", "Quilt");
    }

    private static String loaderValue(String displayName) {
        for (Map.Entry<String, String> entry : SUPPORTED_LOADERS.entrySet()) {
            if (entry.getValue().equals(displayName)) {
                return entry.getKey();
            }
        }
        return displayName == null ? null : displayName.toLowerCase(Locale.ROOT);
    }

    private void promptNewInstance() {
        Dialog<OfflineInstance> dialog = new Dialog<>();
        dialog.setTitle("New Offline Instance");
        dialog.setHeaderText("Create a new offline Minecraft instance");

        ButtonType createType = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        TextField nameField = new TextField("My Instance");
        ComboBox<String> versionCombo = new ComboBox<>();
        versionCombo.setEditable(true);
        versionCombo.setPrefWidth(200);
        versionCombo.setPromptText("Loading versions…");
        ComboBox<String> loaderCombo = new ComboBox<>();
        loaderCombo.setPrefWidth(200);
        loaderCombo.setPromptText("Loading loaders…");
        TextField loaderVersionField = new TextField();
        loaderVersionField.setPrefWidth(200);
        loaderVersionField.setPromptText("e.g. 0.15.11");

        grid.add(new Label("Name:"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label("Minecraft:"), 0, 1);
        grid.add(versionCombo, 1, 1);
        grid.add(new Label("Loader:"), 0, 2);
        grid.add(loaderCombo, 1, 2);
        grid.add(new Label("Loader version:"), 0, 3);
        grid.add(loaderVersionField, 1, 3);
        dialog.getDialogPane().setContent(grid);

        // Minecraft versions (Modrinth tag endpoint, releases only).
        Thread.ofVirtual().name("modrinth-versions").start(() -> {
            try {
                List<String> versions = modrinth.listGameVersions();
                Platform.runLater(() -> {
                    versionCombo.getItems().setAll(versions);
                    if (versions.contains("1.20.4")) {
                        versionCombo.setValue("1.20.4");
                    } else if (!versions.isEmpty()) {
                        versionCombo.setValue(versions.get(0));
                    } else {
                        versionCombo.setPromptText("No versions available");
                    }
                });
            } catch (Exception e) {
                log.warn("Could not fetch Minecraft versions from Modrinth", e);
                // Leave the combo editable so the user can still type a version.
                Platform.runLater(() -> versionCombo.setPromptText("Couldn't load versions — type one"));
            }
        });

        // Loaders (Modrinth tag endpoint, filtered to what this launcher can run).
        Thread.ofVirtual().name("modrinth-loaders").start(() -> {
            try {
                List<String> modrinthLoaders = modrinth.listLoaders();
                Platform.runLater(() -> {
                    List<String> items = new ArrayList<>();
                    for (Map.Entry<String, String> entry : SUPPORTED_LOADERS.entrySet()) {
                        if ("vanilla".equals(entry.getKey()) || modrinthLoaders.contains(entry.getKey())) {
                            items.add(entry.getValue());
                        }
                    }
                    loaderCombo.getItems().setAll(items);
                    if (items.contains("Fabric")) {
                        loaderCombo.setValue("Fabric");
                    } else if (!items.isEmpty()) {
                        loaderCombo.setValue(items.get(0));
                    }
                });
            } catch (Exception e) {
                log.warn("Could not fetch loaders from Modrinth", e);
                Platform.runLater(() -> {
                    loaderCombo.getItems().setAll(SUPPORTED_LOADERS.values());
                    loaderCombo.setValue("Fabric");
                });
            }
        });

        dialog.setResultConverter(btn -> {
            if (btn == createType) {
                OfflineInstance draft = new OfflineInstance();
                draft.setName(nameField.getText());
                draft.setMinecraftVersion(versionCombo.getValue());
                draft.setModLoader(new ModLoaderInfo(
                        loaderValue(loaderCombo.getValue()), loaderVersionField.getText(), ""));
                return draft;
            }
            return null;
        });

        dialog.showAndWait().ifPresent(draft -> {
            try {
                OfflineInstance instance = OfflineInstanceManager.createInstance(
                        draft.getName(), draft.getMinecraftVersion(),
                        draft.getModLoader().getType(), draft.getModLoader().getVersion());
                selectedOfflineInstance = instance;
                populateOfflineInstances();
                status("Created " + instance.getName());
            } catch (IOException e) {
                status("Failed to create instance: " + e.getMessage());
            }
        });
    }

    private void handleOfflinePlay() {
        OfflineInstance instance = selectedOfflineInstance;
        if (instance == null) {
            status("Select an instance first.");
            return;
        }
        if (gameProcess != null && gameProcess.isAlive()) {
            gameProcess.destroy();
            status("Game process stopped.");
            gameProcess = null;
            gameRunning = false;
            populateServerList();
            return;
        }
        if (busy.compareAndSet(false, true)) {
            setBusyUi(true);
            Thread.ofVirtual().name("offline-launch").start(() -> launchOfflineFlow(instance));
        }
    }

    private void launchOfflineFlow(OfflineInstance instance) {
        try {
            status("Resolving Minecraft " + instance.getMinecraftVersion() + " runtime...");
            int requiredJava = JavaRuntimeSelector.getRequiredJavaMajorVersion(instance.getMinecraftVersion());
            MinecraftClasspathBuilder.LaunchData launchData =
                    classpathBuilder.resolve(instance.getMinecraftVersion(), instance.getModLoader(), requiredJava);

            Path gameDir = OfflineInstanceManager.instanceDir(instance.getId());
            Files.createDirectories(gameDir);

            status("Starting offline instance '" + instance.getName() + "'...");
            String playerName = (session != null && session.getUsername() != null && !session.getUsername().isBlank())
                    ? session.getUsername() : "Player";
            gameProcess = runner.launchOffline(launchData, playerName, instance.getJavaArgs(), gameDir, null);
            gameRunning = true;
            Platform.runLater(() -> populateServerList());

            instance.setLastPlayed(System.currentTimeMillis());
            try {
                OfflineInstanceManager.save(instance);
            } catch (IOException ignored) {
                // Best-effort last-played stamp.
            }

            status("Playing " + instance.getName() + " (offline).");
            Thread.ofVirtual().name("game-wait").start(() -> {
                try {
                    int code = gameProcess.waitFor();
                    gameProcess = null;
                    gameRunning = false;
                    Platform.runLater(() -> {
                        status("Game exited (code " + code + ").");
                        populateServerList();
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } catch (Exception e) {
            log.error("Offline launch failed", e);
            status("Error: " + describeError(e));
        } finally {
            Platform.runLater(() -> {
                busy.set(false);
                setBusyUi(false);
            });
        }
    }

    private void handleOfflineDelete() {
        OfflineInstance instance = selectedOfflineInstance;
        if (instance == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete '" + instance.getName() + "' and all of its files?", ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Delete Offline Instance");
        confirm.setHeaderText(null);
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.YES) {
                OfflineInstanceManager.delete(instance);
                selectedOfflineInstance = null;
                populateOfflineInstances();
                status("Deleted " + instance.getName());
            }
        });
    }

    // ------------------------------------------------------------------
    // Offline mods: drag-and-drop + Modrinth
    // ------------------------------------------------------------------

    private void onOfflineDragOver(DragEvent event) {
        if (event.getDragboard().hasFiles()) {
            event.acceptTransferModes(TransferMode.COPY);
        }
        event.consume();
    }

    private void onOfflineDrop(DragEvent event) {
        OfflineInstance instance = selectedOfflineInstance;
        if (instance != null && event.getDragboard().hasFiles()) {
            List<File> jars = event.getDragboard().getFiles().stream()
                    .filter(f -> f.getName().toLowerCase().endsWith(".jar"))
                    .toList();
            copyMods(instance, jars);
            event.setDropCompleted(!jars.isEmpty());
        }
        event.consume();
    }

    private void browseOfflineMods(MouseEvent event) {
        OfflineInstance instance = selectedOfflineInstance;
        if (instance == null) {
            status("Select an instance first.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Minecraft Mods (.jar)");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JAR Files", "*.jar"));
        List<File> selected = chooser.showOpenMultipleDialog(stage);
        if (selected != null) {
            copyMods(instance, selected);
        }
    }

    private void copyMods(OfflineInstance instance, List<File> files) {
        if (instance == null || files == null || files.isEmpty()) {
            return;
        }
        try {
            Path modsDir = OfflineInstanceManager.modsDir(instance);
            Files.createDirectories(modsDir);
            int count = 0;
            for (File file : files) {
                if (!file.getName().toLowerCase().endsWith(".jar")) {
                    continue;
                }
                Files.copy(file.toPath(), modsDir.resolve(file.getName()), StandardCopyOption.REPLACE_EXISTING);
                count++;
            }
            renderOfflineMods(instance);
            status("Added " + count + " mod(s) to " + instance.getName());
        } catch (IOException e) {
            status("Failed to add mods: " + e.getMessage());
        }
    }

    private void handleModrinthSearch() {
        OfflineInstance instance = selectedOfflineInstance;
        if (instance == null) {
            status("Select an instance first.");
            return;
        }
        String query = modrinthQuery.getText();
        if (query == null || query.isBlank()) {
            status("Enter a mod name to search.");
            return;
        }
        int seq = modSearchSeq.incrementAndGet();
        modrinthSearchBtn.setDisable(true);
        modrinthResultsContainer.getChildren().clear();
        modrinthResultsContainer.getChildren().add(searchingLabel("Searching Modrinth..."));
        // Modrinth doesn't tag projects with a "vanilla" loader category.
        String loader = "vanilla".equalsIgnoreCase(instance.getModLoader().getType())
                ? null : instance.getModLoader().getType();
        Thread.ofVirtual().name("modrinth-search").start(() -> {
            try {
                List<ModrinthApiClient.ModrinthSearchHit> hits = modrinth.searchMods(
                        query.trim(), instance.getMinecraftVersion(), loader, "mod");
                if (seq != modSearchSeq.get()) {
                    return; // superseded by a newer search
                }
                Platform.runLater(() -> renderModrinthResults(hits));
            } catch (Exception e) {
                if (seq != modSearchSeq.get()) {
                    return;
                }
                log.warn("Modrinth search failed", e);
                Platform.runLater(() -> {
                    modrinthResultsContainer.getChildren().clear();
                    modrinthResultsContainer.getChildren().add(infoLabel("Search failed: " + describeError(e)));
                    modrinthSearchBtn.setDisable(false);
                });
            }
        });
    }

    private void renderModrinthResults(List<ModrinthApiClient.ModrinthSearchHit> hits) {
        modSearchHits = hits;
        modrinthResultsContainer.getChildren().clear();
        modrinthSearchBtn.setDisable(false);
        if (hits.isEmpty()) {
            modrinthResultsContainer.getChildren().add(infoLabel("No results found."));
            return;
        }
        for (ModrinthApiClient.ModrinthSearchHit hit : hits) {
            modrinthResultsContainer.getChildren().add(createModSearchRow(hit));
        }
    }

    private HBox createModSearchRow(ModrinthApiClient.ModrinthSearchHit hit) {
        return createSearchRow(hit, () -> installModrinthMod(hit));
    }

    private HBox createPackSearchRow(ModrinthApiClient.ModrinthSearchHit hit, boolean shader) {
        return createSearchRow(hit, () -> installModrinthPack(hit, shader));
    }

    /** One Modrinth search result: title/description + an Install button (spinner while downloading). */
    private HBox createSearchRow(ModrinthApiClient.ModrinthSearchHit hit, Runnable installAction) {
        Label title = new Label(hit.title);
        title.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: white;");

        Label desc = new Label(hit.description == null ? "" : hit.description);
        desc.setMaxWidth(280);
        desc.setWrapText(true);
        desc.setStyle("-fx-font-size: 10px; -fx-text-fill: #8b949e;");

        VBox text = new VBox(2, title, desc);
        HBox.setHgrow(text, Priority.ALWAYS);

        Node action;
        if (installingProjects.contains(hit.projectId)) {
            action = spinner(16);
        } else {
            Button installBtn = new Button("Install");
            installBtn.setStyle("-fx-background-color: #21262d; -fx-text-fill: #47d2c9; -fx-padding: 4 10; -fx-font-size: 11px;");
            installBtn.setOnAction(e -> installAction.run());
            action = installBtn;
        }

        HBox row = new HBox(10, text, action);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8));
        row.setStyle("-fx-background-color: #0d1117; -fx-border-color: #21262d; -fx-border-radius: 6; -fx-background-radius: 6;");
        return row;
    }

    /** Row label + spinner shown while a search request is in flight. */
    private HBox searchingLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 12px; -fx-text-fill: #8b949e;");
        HBox box = new HBox(8, spinner(14), label);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private void installModrinthMod(ModrinthApiClient.ModrinthSearchHit hit) {
        OfflineInstance instance = selectedOfflineInstance;
        if (instance == null) {
            status("Select an instance first.");
            return;
        }
        if (!installingProjects.add(hit.projectId)) {
            return; // already downloading
        }
        status("Installing " + hit.title + "...");
        renderModrinthResults(modSearchHits); // swap the row's button for a spinner
        Thread.ofVirtual().name("modrinth-install").start(() -> {
            try {
                List<ModrinthApiClient.ModrinthVersion> versions = modrinth.listProjectVersions(
                        hit.projectId, instance.getMinecraftVersion(), instance.getModLoader().getType());
                if (versions.isEmpty()) {
                    Platform.runLater(() -> status("No compatible version of " + hit.title + " found."));
                    return;
                }
                ModrinthApiClient.ModrinthVersion version = versions.get(0);
                ModrinthApiClient.ModrinthFile file = version.primaryFile();
                if (file == null || file.url == null || file.url.isBlank()) {
                    Platform.runLater(() -> status("No downloadable file for " + hit.title + "."));
                    return;
                }
                String filename = file.filename == null || file.filename.isBlank()
                        ? hit.title + ".jar" : file.filename;
                Path dest = OfflineInstanceManager.modsDir(instance).resolve(filename);
                downloadFile(file.url, dest);
                Platform.runLater(() -> {
                    modSearchHits = modSearchHits.stream()
                            .filter(h -> !h.projectId.equals(hit.projectId)).toList();
                    renderModrinthResults(modSearchHits);
                    renderOfflineMods(instance);
                    status("Installed " + filename);
                });
            } catch (Exception e) {
                log.warn("Modrinth install failed", e);
                Platform.runLater(() -> status("Install failed: " + describeError(e)));
            } finally {
                Platform.runLater(() -> {
                    installingProjects.remove(hit.projectId);
                    renderModrinthResults(modSearchHits); // restore the Install button
                });
            }
        });
    }

    // ------------------------------------------------------------------
    // Offline shaderpacks / texture packs: Modrinth search + install
    // ------------------------------------------------------------------

    private void handleShaderSearch() {
        runPackSearch(offlineShaderQuery, offlineShaderSearchBtn, offlineShaderResultsContainer,
                shaderSearchSeq, "shader", "shaderpack", this::renderShaderResults);
    }

    private void handleTextureSearch() {
        runPackSearch(offlineTextureQuery, offlineTextureSearchBtn, offlineTextureResultsContainer,
                textureSearchSeq, "resourcepack", "texture pack", this::renderTextureResults);
    }

    private void runPackSearch(TextField queryField, Button searchBtn, VBox resultsContainer,
                               AtomicInteger seq, String projectType, String displayName,
                               Consumer<List<ModrinthApiClient.ModrinthSearchHit>> render) {
        OfflineInstance instance = selectedOfflineInstance;
        if (instance == null) {
            status("Select an instance first.");
            return;
        }
        String query = queryField.getText();
        if (query == null || query.isBlank()) {
            status("Enter a " + displayName + " name to search.");
            return;
        }
        int mySeq = seq.incrementAndGet();
        searchBtn.setDisable(true);
        resultsContainer.getChildren().clear();
        resultsContainer.getChildren().add(searchingLabel("Searching Modrinth..."));
        // Packs aren't loader-specific, so no loader facet (it returns zero hits).
        Thread.ofVirtual().name("modrinth-pack-search").start(() -> {
            try {
                List<ModrinthApiClient.ModrinthSearchHit> hits = modrinth.searchMods(
                        query.trim(), instance.getMinecraftVersion(), null, projectType);
                if (mySeq != seq.get()) {
                    return; // superseded by a newer search
                }
                Platform.runLater(() -> render.accept(hits));
            } catch (Exception e) {
                if (mySeq != seq.get()) {
                    return;
                }
                log.warn("Modrinth {} search failed", projectType, e);
                Platform.runLater(() -> {
                    resultsContainer.getChildren().clear();
                    resultsContainer.getChildren().add(infoLabel("Search failed: " + describeError(e)));
                    searchBtn.setDisable(false);
                });
            }
        });
    }

    private void renderShaderResults(List<ModrinthApiClient.ModrinthSearchHit> hits) {
        shaderSearchHits = hits;
        offlineShaderResultsContainer.getChildren().clear();
        offlineShaderSearchBtn.setDisable(false);
        if (hits.isEmpty()) {
            offlineShaderResultsContainer.getChildren().add(infoLabel("No results found."));
            return;
        }
        for (ModrinthApiClient.ModrinthSearchHit hit : hits) {
            offlineShaderResultsContainer.getChildren().add(createPackSearchRow(hit, true));
        }
    }

    private void renderTextureResults(List<ModrinthApiClient.ModrinthSearchHit> hits) {
        textureSearchHits = hits;
        offlineTextureResultsContainer.getChildren().clear();
        offlineTextureSearchBtn.setDisable(false);
        if (hits.isEmpty()) {
            offlineTextureResultsContainer.getChildren().add(infoLabel("No results found."));
            return;
        }
        for (ModrinthApiClient.ModrinthSearchHit hit : hits) {
            offlineTextureResultsContainer.getChildren().add(createPackSearchRow(hit, false));
        }
    }

    private void installModrinthPack(ModrinthApiClient.ModrinthSearchHit hit, boolean shader) {
        OfflineInstance instance = selectedOfflineInstance;
        if (instance == null) {
            return;
        }
        if (!installingProjects.add(hit.projectId)) {
            return; // already downloading
        }
        status("Installing " + hit.title + "...");
        if (shader) {
            renderShaderResults(shaderSearchHits);
        } else {
            renderTextureResults(textureSearchHits);
        }
        Thread.ofVirtual().name("modrinth-pack-install").start(() -> {
            try {
                List<ModrinthApiClient.ModrinthVersion> versions = modrinth.listProjectVersions(
                        hit.projectId, instance.getMinecraftVersion(), null);
                if (versions.isEmpty()) {
                    Platform.runLater(() -> status("No compatible version of " + hit.title + " found."));
                    return;
                }
                ModrinthApiClient.ModrinthFile file = versions.get(0).primaryFile();
                if (file == null || file.url == null || file.url.isBlank()) {
                    Platform.runLater(() -> status("No downloadable file for " + hit.title + "."));
                    return;
                }
                String filename = file.filename == null || file.filename.isBlank()
                        ? hit.title + ".zip" : file.filename;
                Path gameDir = OfflineInstanceManager.instanceDir(instance.getId());
                Path dest = (shader ? gameDir.resolve("shaderpacks") : gameDir.resolve("resourcepacks"))
                        .resolve(filename);
                downloadFile(file.url, dest);
                Platform.runLater(() -> {
                    if (shader) {
                        shaderSearchHits = shaderSearchHits.stream()
                                .filter(h -> !h.projectId.equals(hit.projectId)).toList();
                        renderShaderResults(shaderSearchHits);
                    } else {
                        textureSearchHits = textureSearchHits.stream()
                                .filter(h -> !h.projectId.equals(hit.projectId)).toList();
                        renderTextureResults(textureSearchHits);
                    }
                    renderOfflinePacks(instance);
                    status("Installed " + filename);
                });
            } catch (Exception e) {
                log.warn("Modrinth pack install failed", e);
                Platform.runLater(() -> status("Install failed: " + describeError(e)));
            } finally {
                Platform.runLater(() -> {
                    installingProjects.remove(hit.projectId);
                    if (shader) {
                        renderShaderResults(shaderSearchHits);
                    } else {
                        renderTextureResults(textureSearchHits);
                    }
                });
            }
        });
    }

    private void downloadFile(String url, Path dest) throws IOException, InterruptedException {
        Path tmp = Files.createTempFile("modrinth-", ".jar");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", ModrinthApiClient.DEFAULT_USER_AGENT)
                .GET()
                .build();
        HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
        if (response.statusCode() / 100 != 2) {
            Files.deleteIfExists(tmp);
            throw new IOException("Download failed: HTTP " + response.statusCode());
        }
        Files.createDirectories(dest.getParent());
        Files.copy(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(tmp);
    }

    private List<String> listPackFiles(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.toLowerCase().endsWith(".zip"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // Server launch pipeline
    // ------------------------------------------------------------------

    private void launchServer(String name, String serverAddress) {
        if (gameProcess != null && gameProcess.isAlive()) {
            gameProcess.destroy();
            status("Game process stopped.");
            gameProcess = null;
            gameRunning = false;
            populateServerList();
            return;
        }
        if (busy.compareAndSet(false, true)) {
            SavedServer.recordPlayed(name, serverAddress);
            // Rebuild the list so the (newly recorded) server's PLAY button
            // renders in its busy state while the launch flow runs.
            launchingPlay = true;
            populateServerList();
            setBusyUi(true);
            Thread.ofVirtual().name("launcher-flow").start(() -> runFlow(serverAddress));
        }
    }

    /** @return true when the server's BOM advertises shaderpacks or texture packs. */
    private static boolean bomOffersPacks(BillOfMaterials bom) {
        return (bom.getShaderpacks() != null && !bom.getShaderpacks().isEmpty())
                || (bom.getResourcepacks() != null && !bom.getResourcepacks().isEmpty());
    }

    /**
     * Shows the "Server Recommended Packs" confirmation on the FX thread and
     * blocks the caller until the player answers. The dialog runs in a nested
     * JavaFX event loop, so the UI stays responsive while the caller waits.
     */
    private boolean promptForServerPacksBlocking(BillOfMaterials bom) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Boolean> choice = new AtomicReference<>(false);
        Platform.runLater(() -> {
            try {
                choice.set(promptUserForServerPacks(bom));
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return choice.get();
    }

    private boolean promptUserForServerPacks(BillOfMaterials bom) {
        List<String> packNames = new ArrayList<>();
        for (PackEntry pack : bom.getShaderpacks()) {
            packNames.add(pack.getTitle() != null ? pack.getTitle() : pack.getFilename());
        }
        for (PackEntry pack : bom.getResourcepacks()) {
            packNames.add(pack.getTitle() != null ? pack.getTitle() : pack.getFilename());
        }

        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle("Server Recommended Packs");
        dialog.setHeaderText(null);
        dialog.setContentText("This server recommends the following shader & texture packs "
                + "for optimal gameplay: " + String.join(", ", packNames)
                + ". Would you like to download and enable them?");
        ButtonType enable = new ButtonType("Enable & Sync", ButtonBar.ButtonData.YES);
        ButtonType vanilla = new ButtonType("Play Vanilla/No Packs", ButtonBar.ButtonData.NO);
        dialog.getButtonTypes().setAll(enable, vanilla);
        Optional<ButtonType> result = dialog.showAndWait();
        return result.orElse(vanilla) == enable;
    }

    private void runFlow(String serverAddress) {
        try {
            if (session == null) {
                status("Opening browser for Microsoft login...");
                session = auth.login();
            } else if (session.isExpired()) {
                status("Renewing session...");
                try {
                    session = auth.refresh(session);
                } catch (Exception e) {
                    log.info("Silent refresh failed, re-authenticating: {}", e.getMessage());
                    session = auth.login();
                }
            }

            if (!auth.checkEntitlements(session.getAccessToken())) {
                throw new IOException("Minecraft rejected this session — the account does not own "
                        + "Minecraft (Java Edition) or the session was revoked. "
                        + "Please sign in again with an account that owns the game.");
            }

            String[] hostPort = parseServerAddress(serverAddress);
            String host = hostPort[0];
            int port = Integer.parseInt(hostPort[1]);
            String baseUrl = "http://" + host + ":" + port;
            status("Server: " + baseUrl);

            Path gameDir = instanceGameDir(host, String.valueOf(port));
            Files.createDirectories(gameDir);

            BillOfMaterials bom = fetchBom(baseUrl);
            ModLoaderInfo loader = bom.getModLoader();

            // The BOM's serverTitle is the server's real name (set by its owner
            // in the web app) — use it for the entry shown in "Your Servers" so
            // the list never keeps a placeholder like "Custom Server". recordPlayed
            // matches by address, so a blank title keeps the existing name.
            if (bom.getServerTitle() != null && !bom.getServerTitle().isBlank()) {
                SavedServer.recordPlayed(bom.getServerTitle().trim(), serverAddress);
                Platform.runLater(this::populateServerList);
            }

            // Reconcile pack folders against the BOM on every connect: download
            // packs the server offers and delete server packs it no longer lists
            // (a player's locally added packs are never touched). This keeps the
            // client in sync when the server owner adds or removes shaders.
            PackSelection selection = PackSelection.load(gameDir);
            status("Checking server shaderpacks & texture packs...");
            packSyncEngine.sync(bom, baseUrl, gameDir,
                    selection.getLocallyAddedShaderpacks(),
                    selection.getLocallyAddedResourcepacks(),
                    msg -> status(msg));

            // If the server removed the pack the player had active, drop it from
            // the selection so the game never points at a missing pack.
            if (selection.getActiveShaderpack() != null
                    && !Files.isRegularFile(gameDir.resolve("shaderpacks").resolve(selection.getActiveShaderpack()))) {
                selection.setActiveShaderpack(null);
            }
            List<String> presentResourcepacks = selection.getActiveResourcepacks().stream()
                    .filter(name -> Files.isRegularFile(gameDir.resolve("resourcepacks").resolve(name)))
                    .toList();
            if (presentResourcepacks.size() != selection.getActiveResourcepacks().size()) {
                selection.setActiveResourcepacks(presentResourcepacks);
            }
            selection.save(gameDir);

            // If the server recommends packs, ask the player whether to activate them.
            if (bomOffersPacks(bom)) {
                boolean enablePacks = promptForServerPacksBlocking(bom);
                if (enablePacks) {
                    selection.setShadersEnabled(true);
                    if (!bom.getShaderpacks().isEmpty()) {
                        selection.setActiveShaderpack(bom.getShaderpacks().get(0).getFilename());
                    }
                    selection.setActiveResourcepacks(bom.getResourcepacks().stream()
                            .map(PackEntry::getFilename).toList());
                    selection.save(gameDir);
                }
            }

            status("Resolving Minecraft " + bom.getMinecraftVersion() + " runtime...");
            int requiredJava = JavaRuntimeSelector.getRequiredJavaMajorVersion(bom.getMinecraftVersion());
            MinecraftClasspathBuilder.LaunchData launchData =
                    classpathBuilder.resolve(bom.getMinecraftVersion(), loader, requiredJava);

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

            status("Registering pre-join intent with Zircon server...");
            registerPreJoinIntent(baseUrl, session.getUsername(), session.getUuid());

            status("Starting Minecraft process...");
            gameProcess = runner.launch(launchData, session, gameDir, host, port, null);
            gameRunning = true;
            launchingPlay = false;
            Platform.runLater(() -> populateServerList());
            status("Game running — connected to " + host + ":" + port);
            Thread.ofVirtual().name("game-wait").start(() -> {
                try {
                    int code = gameProcess.waitFor();
                    gameProcess = null;
                    gameRunning = false;
                    Platform.runLater(() -> {
                        status("Game exited (code " + code + ").");
                        populateServerList();
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } catch (Exception e) {
            log.error("Launcher flow failed", e);
            status("Error: " + describeError(e));
        } finally {
            Platform.runLater(() -> {
                busy.set(false);
                launchingPlay = false;
                // Restore the PLAY buttons to their idle state now that the
                // launch flow has finished (success, failure or abort).
                populateServerList();
                setBusyUi(false);
            });
        }
    }

    private void registerPreJoinIntent(String baseUrl, String username, String uuid) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build();
            String json = BomJson.gson().toJson(Map.of(
                    "username", username == null ? "" : username,
                    "uuid", uuid == null ? "" : uuid));
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(baseUrl + "/api/join-intent"))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                    .build();
            client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
            log.debug("Pre-join ticket registered for {}", username);
        } catch (Exception e) {
            log.warn("Could not pre-register join ticket: {}", e.getMessage());
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
        return BomJson.fromJson(response.body());
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
        try {
            auth.clearCache();
        } catch (IOException e) {
            log.warn("Could not clear auth cache", e);
        }
        session = null;
        userLabel.setText("Not signed in");
        userAvatar.setImage(null);
        logoutButton.setVisible(false);
        showLoginView(true);
        status("Signed out.");
    }

    // ------------------------------------------------------------------
    // UI helpers
    // ------------------------------------------------------------------

    private void status(String text) {
        Platform.runLater(() -> statusLabel.setText(text));
    }

    private Label infoLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 12px; -fx-text-fill: #8b949e;");
        return label;
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String describeError(Throwable t) {
        if (t.getMessage() != null && !t.getMessage().isBlank()) {
            return t.getMessage();
        }
        StackTraceElement top = t.getStackTrace().length > 0 ? t.getStackTrace()[0] : null;
        return t.getClass().getSimpleName()
                + (top != null ? " at " + top.getClassName() + ":" + top.getLineNumber() : "");
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

    /** Small white throbber for busy buttons. */
    private static ProgressIndicator spinner(double size) {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(size, size);
        spinner.setMaxSize(size, size);
        spinner.setStyle("-fx-progress-color: white;");
        return spinner;
    }
}
