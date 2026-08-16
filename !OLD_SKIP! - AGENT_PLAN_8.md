# Implementation Plan: Zircon Launcher UI Refactoring

## Overview of Architecture Changes
1. ✅ **Auth & Security Updates**: Obfuscate a default Azure Client ID into `MicrosoftAuthService` and style the local OAuth callback HTTP response page with a dark Zircon theme matching the launcher UI.
2. ✅ **JavaFX 3D Skin Engine**: Implement a native JavaFX 3D subscene renderer (`Player3DRenderer`) using textured 3D box meshes (head, torso, limbs) supporting standard Minecraft 64x64 skin UV textures and interactive mouse drag rotation.
3. **Offline World/Instance Engine**: Add `OfflineInstanceManager` and `OfflineInstance` models to manage local instances under `~/.mcmanager/offline_instances/` with local `mods/` folders, Modrinth mod installation support, and offline launch capabilities.
4. ✅ **Enhanced Skin History**: Extend `SkinManager` to store recently uploaded skin files and generate 2D player head icon crops for the user card.
5. **UI Shell Overhaul (`MainApp` & `MainController`)**: Re-architect the JavaFX layout to support the Login View, Servers View with 3D player preview, Play Offline View, Skins Gallery View, Settings View, and refreshed Sidebar user card.

---

## Phase 1: Foundation, Auth & Security Upgrades

> ✅ **COMPLETE** — obfuscated embedded client ID + Zircon-themed callback page (Step 1.1).

### Step 1.1: Obfuscated Default Azure Client ID & Styled Callback Page — ✅ DONE
- **Files to Modify**:
  - `main/java/com/mcmanager/client/auth/MicrosoftAuthService.java`

- **Notes**:
  - Encrypt/obfuscate the default Azure Client ID (using Base64/XOR byte array) in `MicrosoftAuthService` so users can run the launcher out of the box without passing `--clientId`.
  - Update `CallbackServer.start()` in `MicrosoftAuthService.java` to return a styled HTML response with Zircon's dark theme (`#0d1117`, emerald accent `#2da44e`, dark card `#161b22`, clean sans-serif typography).

- **Implementation Details / Pseudocode**:
  ```java
  // MicrosoftAuthService.java
  private static final String EMBEDDED_CLIENT_ID = decodeClientId(new byte[]{ /* Base64 or XOR bytes */ });

  private static String resolveClientId() {
      String fromProp = System.getProperty("mcmanager.clientId");
      if (fromProp != null && !fromProp.isBlank()) return fromProp;
      // check file ...
      return EMBEDDED_CLIENT_ID;
  }

  // In CallbackServer.start():
  String html = """
      <!DOCTYPE html>
      <html>
      <head>
          <style>
              body { background-color: #0d1117; color: #c9d1d9; font-family: 'Segoe UI', sans-serif; text-align: center; padding-top: 100px; }
              .card { background: #161b22; border: 1px solid #30363d; border-radius: 12px; display: inline-block; padding: 40px; box-shadow: 0 10px 25px rgba(0,0,0,0.5); }
              .logo { background: #2da44e; color: white; border-radius: 8px; font-weight: bold; padding: 6px 12px; font-size: 20px; display: inline-block; margin-bottom: 16px; }
              h2 { margin: 0 0 12px 0; color: #ffffff; }
              p { color: #8b949e; font-size: 14px; margin: 0; }
          </style>
      </head>
      <body>
          <div class="card">
              <div class="logo">⚡ Zircon</div>
              <h2>Authentication Successful!</h2>
              <p>You may now close this browser window and return to the launcher.</p>
          </div>
      </body>
      </html>
      """;
  ```

---

## Phase 2: JavaFX 3D Player Model & Skin Engine

> ✅ **COMPLETE** — `Player3DRenderer` + `SkinManager` history/head-icon extraction (Steps 2.1–2.2).

### Step 2.1: 3D Player SubScene Model — ✅ DONE
- **Files to Create**:
  - `main/java/com/mcmanager/client/ui/component/Player3DRenderer.java`

- **Notes**:
  - Uses native JavaFX 3D (`SubScene`, `PerspectiveCamera`, `Group`, `Box`, `PhongMaterial`, `Image`, `Rotate`, `Translate`).
  - Constructs the Minecraft player model:
    - **Head**: 8x8x8 box (UV coordinates mapped from skin PNG `(0,0)-(32,16)` or sub-image extraction).
    - **Torso**: 8x12x4 box.
    - **Left Arm / Right Arm**: 4x12x4 box (or 3x12x4 for Alex slim).
    - **Left Leg / Right Leg**: 4x12x4 box.
  - Implements mouse drag event handling on the SubScene to rotate the player character around the Y-axis.
  - Exposes `updateSkin(Image skinImage)` to dynamically update materials.

- **Implementation Details / Pseudocode**:
  ```java
  package com.mcmanager.client.ui.component;

  import javafx.geometry.Point3D;
  import javafx.scene.*;
  import javafx.scene.image.Image;
  import javafx.scene.image.PixelReader;
  import javafx.scene.image.WritableImage;
  import javafx.scene.paint.Color;
  import javafx.scene.paint.PhongMaterial;
  import javafx.scene.shape.Box;
  import javafx.scene.transform.Rotate;

  public class Player3DRenderer {

      private final SubScene subScene;
      private final Group playerGroup = new Group();
      private final Rotate rotateY = new Rotate(0, Rotate.Y_AXIS);

      private Box head, torso, leftArm, rightArm, leftLeg, rightLeg;
      private double mouseAnchorX;

      public Player3DRenderer(double width, double height) {
          Group root = new Group();
          root.getChildren().add(playerGroup);

          buildPlayerModel();

          PerspectiveCamera camera = new PerspectiveCamera(true);
          camera.setTranslateZ(-120);
          camera.setTranslateY(-10);
          camera.setNearClip(0.1);
          camera.setFarClip(1000.0);

          PointLight light = new PointLight(Color.WHITE);
          light.setTranslateZ(-200);
          light.setTranslateY(-100);
          root.getChildren().add(light);
          root.getChildren().add(new AmbientLight(Color.web("#888888")));

          subScene = new SubScene(root, width, height, true, SceneAntialiasing.BALANCED);
          subScene.setCamera(camera);

          playerGroup.getTransforms().add(rotateY);

          // Mouse rotation listener
          subScene.setOnMousePressed(e -> mouseAnchorX = e.getSceneX());
          subScene.setOnMouseDragged(e -> {
              double deltaX = e.getSceneX() - mouseAnchorX;
              rotateY.setAngle(rotateY.getAngle() + deltaX * 0.5);
              mouseAnchorX = e.getSceneX();
          });
      }

      public SubScene getSubScene() { return subScene; }

      public void updateSkin(Image skinImage) {
          if (skinImage == null) return;
          // Crop regions from 64x64 skin PNG for materials:
          // Head, Torso, Arms, Legs
          PhongMaterial headMat = new PhongMaterial();
          headMat.setDiffuseMap(cropHeadTexture(skinImage));
          head.setMaterial(headMat);

          PhongMaterial bodyMat = new PhongMaterial();
          bodyMat.setDiffuseMap(skinImage);
          torso.setMaterial(bodyMat);
          leftArm.setMaterial(bodyMat);
          rightArm.setMaterial(bodyMat);
          leftLeg.setMaterial(bodyMat);
          rightLeg.setMaterial(bodyMat);
      }

      private void buildPlayerModel() {
          head = new Box(16, 16, 16); head.setTranslateY(-26);
          torso = new Box(16, 24, 8); torso.setTranslateY(-6);
          leftArm = new Box(8, 24, 8); leftArm.setTranslateX(-12); leftArm.setTranslateY(-6);
          rightArm = new Box(8, 24, 8); rightArm.setTranslateX(12); rightArm.setTranslateY(-6);
          leftLeg = new Box(8, 24, 8); leftLeg.setTranslateX(-4); leftLeg.setTranslateY(18);
          rightLeg = new Box(8, 24, 8); rightLeg.setTranslateX(4); rightLeg.setTranslateY(18);

          playerGroup.getChildren().addAll(head, torso, leftArm, rightArm, leftLeg, rightLeg);
      }
  }
  ```

---

### Step 2.2: Enhanced Skin Manager & Head Avatar Extraction — ✅ DONE
- **Files to Modify**:
  - `main/java/com/mcmanager/client/skin/SkinManager.java`

- **Notes**:
  - Support skin history storage in `~/.mcmanager/skins/history/`.
  - Add helper `getSkinHistory()` returning `List<Path>` ordered by file modification time (newest first).
  - Add `extractHeadIcon(Image skinImage)` returning a cropped `Image` (8x8 pixel face area from `(8,8)` scaled up cleanly) for the sidebar User Card avatar.

- **Implementation Details / Pseudocode**:
  ```java
  // SkinManager.java
  private static final Path HISTORY_DIR = SKIN_DIR.resolve("history");

  public static List<Path> getSkinHistory() {
      if (!Files.isDirectory(HISTORY_DIR)) return List.of();
      try (Stream<Path> s = Files.list(HISTORY_DIR)) {
          return s.filter(p -> p.toString().toLowerCase().endsWith(".png"))
                  .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                  .toList();
      } catch (IOException e) { return List.of(); }
  }

  public static Image extractHeadIcon(Image skin) {
      if (skin == null) return null;
      PixelReader reader = skin.getPixelReader();
      WritableImage head = new WritableImage(8, 8);
      head.getPixelWriter().setPixels(0, 0, 8, 8, reader, 8, 8);
      return head;
  }
  ```

---

## Phase 3: Offline Instance Management Infrastructure

### Step 3.1: Offline Instance Model & Local Storage
- **Files to Create**:
  - `main/java/com/mcmanager/client/offline/OfflineInstance.java`
  - `main/java/com/mcmanager.client/offline/OfflineInstanceManager.java`

- **Notes**:
  - Stores offline worlds under `~/.mcmanager/offline_instances/<instance_id>/`.
  - Each offline instance folder contains `instance.json` and a `mods/` directory.
  - Fields in `OfflineInstance`: `id`, `name`, `minecraftVersion`, `modLoader` (`type`, `version`), `gameMode` (Survival, Creative, Adventure, Spectator), `allowCheats` (boolean), `javaArgs`, `lastPlayed` (epoch millis).

- **Implementation Details / Pseudocode**:
  ```java
  package com.mcmanager.client.offline;

  public class OfflineInstance {
      private String id;
      private String name;
      private String minecraftVersion = "1.20.4";
      private ModLoaderInfo modLoader = new ModLoaderInfo("fabric", "0.15.11", "");
      private String gameMode = "survival";
      private boolean allowCheats = false;
      private String javaArgs = "-Xms2G -Xmx4G";
      private long lastPlayed = System.currentTimeMillis();

      // Getters & Setters ...
  }
  ```

  ```java
  package com.mcmanager.client.offline;

  public class OfflineInstanceManager {
      private static final Path ROOT = Path.of(System.getProperty("user.home"), ".mcmanager", "offline_instances");
      private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

      public static List<OfflineInstance> loadAll() {
          // List directories under ROOT, load instance.json, sort by lastPlayed desc
      }

      public static OfflineInstance createInstance(String name, String mcVersion, String loaderType, String loaderVersion) {
          // Create directory, write instance.json, return OfflineInstance
      }

      public static void save(OfflineInstance instance) {
          // Write instance.json
      }
  }
  ```

---

## Phase 4: Client UI Refactoring (`MainApp` & `MainController`)

### Step 4.1: Main Application Structure (`MainApp.java`)
- **Files to Modify**:
  - `main/java/com/mcmanager/client/ui/MainApp.java`

- **Notes**:
  - Root Layout: `StackPane` holding `LoginView` overlay and `MainLayout` (HBox of `Sidebar` + `CenterStack`).
  - **Login View**:
    - "Welcome to Zircon" title.
    - Large "Login with Microsoft" button.
    - Status message / error area.
  - **Sidebar**:
    - Brand Header: "⚡ Zircon".
    - Nav Buttons:
      1. `⚡ Servers`
      2. `🎮 Play Offline`
      3. `👕 Skins`
      4. `⚙️ Settings`
      5. `🎨 Shaders & Packs`
    - Bottom User Card: Player head icon (cropped from skin), Username, Logout button.
  - **Views Container**:
    1. `ServerListView`: Left side = Your Servers & Recommended Servers (scrollable VBox cards with icons/descriptions); Right side = 3D Player Viewport (`Player3DRenderer`).
    2. `OfflineView`: Left side = Offline Instance List (scrollable cards with "+ New World" button); Right side = Selected Instance details (Version, Loader, Loader version, Mod list, Upload/Drag-and-drop, Modrinth search, GameMode, Allow Cheats, "Play Offline" button).
    3. `SkinsView`: Left side = 3D Player Viewport; Right side = Skin Upload button, Default Skins gallery (Steve, Alex), Recently Uploaded Skins gallery (scrollable).
    4. `SettingsView`: Memory slider, hash verification checkboxes.
    5. `ShadersPacksView`: Server picker, shaderpack / texture pack sync & select.

---

### Step 4.2: Main Controller Updates (`MainController.java`)
- **Files to Modify**:
  - `main/java/com/mcmanager/client/ui/controller/MainController.java`

- **Notes**:
  - Implement full state binding for Login, Servers, Play Offline, Skins, and Settings views.
  - Update Recommended Server models to include icon badges / descriptions (e.g. Hypixel, Wynncraft, Zircon Official).
  - Bind 3D Player Renderer updates whenever active skin changes or when switching between Servers / Skins views.
  - Handle Play Offline flow:
    - Load offline instances list. Select top/most recently played instance by default.
    - Render offline instance mods list, drag-and-drop file upload target for mods.
    - Modrinth search and install directly to offline instance `mods/` directory.
    - Offline game launch button triggering `MinecraftClasspathBuilder` and `MinecraftRunner` directly against the offline instance folder.
  - Handle Skins flow:
    - Render default skins (Steve / Alex) and skin history items.
    - Clicking a skin updates `SkinManager.saveSkin(...)`, refreshes 3D player model, and updates the sidebar user head avatar.

---

## Detailed File Modification Walkthrough

### 1. ✅ `MicrosoftAuthService.java`
- Update `DEFAULT_CLIENT_ID` with obfuscated embedded key fallback.
- In `CallbackServer.start()`, replace plain response HTML with Zircon dark-themed styled HTML (`#0d1117`, emerald badges, rounded cards).

### 2. ✅ `Player3DRenderer.java` (New File)
- JavaFX 3D model component using `SubScene`, `PerspectiveCamera`, `Group`, and `Box` shapes.
- Maps 64x64 skin textures to materials.
- Provides interactive Y-axis drag rotation.

### 3. ✅ `SkinManager.java`
- Implement `getSkinHistory()`, `saveToHistory(File)`, and `extractHeadIcon(Image)` for sidebar user avatar rendering.

### 4. `OfflineInstance.java` & `OfflineInstanceManager.java` (New Files)
- Model and storage manager for offline instance configurations under `~/.mcmanager/offline_instances/`.

### 5. `MainApp.java`
- Build the entire JavaFX UI structure based on the provided sketch:
  - Login View (Welcome to Zircon + Login button).
  - Sidebar ("Zircon", Servers, Play Offline, Skins, Settings, Bottom User Card).
  - Servers View (Your Servers, Recommended Servers with icons/scrollbars, 3D Player Model on the right).
  - Play Offline View (Instance cards on left, Instance Details + Mods + Settings + Launch on right).
  - Skins View (3D Player Model on left, Skin Upload + Recent/Default Skins gallery on right).
  - Settings View.

### 6. `MainController.java`
- Wire all event handlers, 3D model skin updates, offline instance CRUD & launching, Modrinth searching for offline mods, skin selection & history management.

---

## Verification & Build Strategy

1. ✅ **Compilation Check** (passing — `./gradlew :client-launcher:compileJava` runs clean):
   - Run `./gradlew :client-launcher:compileJava` to verify all JavaFX 3D imports and new classes compile cleanly.
2. **Offline Instance & Mod Installation Unit Tests**:
   - Create unit tests for `OfflineInstanceManager` loading/saving instance JSON files.
3. **End-to-End Visual Verification**:
   - Launch `MainApp` locally to verify:
     - Login Overlay appears on first run.
     - Authenticating transitions to the main launcher screen.
     - 3D Player Character renders on the Servers and Skins screens with mouse drag rotation.
     - Recommended Servers display icons and scrollable lists.
     - Play Offline tab displays local worlds, mod lists, drag-and-drop target, and launch button.
     - Skins tab displays recent skin history, default skins, and live 3D preview updates.