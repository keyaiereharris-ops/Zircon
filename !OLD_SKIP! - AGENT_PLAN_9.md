# Comprehensive Coding Agent Plan: Zircon Minecraft Launcher & Companion Enhancements

This document outlines a detailed, step-by-step implementation plan for enhancing the **Zircon Minecraft Launcher** (`com.mcmanager.client`). The plan addresses 3D model rendering improvements (top-layer overlay, viewport expansion, pitch rotation), automatic Mojang skin synchronization and gallery redesign, offline mode UI containerization, local shader integration, and server-driven shader/pack prompting.

---

## Architecture Overview & Key Files

| Component | Key Files | Responsibility |
| :--- | :--- | :--- |
| **3D Render Engine** | `PlayerModel.java`<br>`PlayerRenderer.java`<br>`Player3DRenderer.java`<br>`GlViewport.java` | LWJGL/OpenGL offscreen framebuffer rendering, 3D player mesh generation, UV texture mapping, shader programs, FX node integration. |
| **Skin System** | `SkinManager.java`<br>`MojangSkinService.java`<br>`DefaultSkinFactory.java` | Mojang API skin fetch/upload, skin history gallery, 2D/3D skin thumbnail generation, active skin persistence. |
| **Client UI Shell** | `MainApp.java`<br>`MainController.java` | JavaFX layout, navigation sidebar, view switching, modal dialogs, styling/theme definitions. |
| **Offline Worlds** | `OfflineInstance.java`<br>`OfflineInstanceManager.java`<br>`MinecraftRunner.java` | Singleplayer instance storage, local mod management, local shader/resourcepack selection, offline game execution. |
| **Packs & Shaders** | `PackSelection.java`<br>`PackOptionsWriter.java`<br>`PackSyncEngine.java`<br>`ClientPackManager.java` | Server BOM pack downloads, local Iris/Options configuration, user prompt on server connect. |

---

## Phase 1: 3D Model Rendering Enhancements

### 1.1 Dynamic Viewport Resizing & Scaling
**Goal:** Expand the 3D player model to dynamically scale and fill its parent JavaFX container bounds (`StackPane`) without fixed pixel constraints or distortion.

1. **Modify `GlViewport.java`**:
   - Add dynamic width/height resize handling.
   - Bind `ImageView` fit width and fit height to parent container bounds or add a `resize(int newWidth, int newHeight)` method that re-allocates/re-initializes the OpenGL Framebuffer Object (`fbo`), color texture (`colorTexture`), depth renderbuffer (`depthRbo`), and LWJGL `pixels` buffer when the container dimensions change.
   - Ensure bottom-up OpenGL coordinate flipping (`imageView.setScaleY(-1.0)`) remains intact.

2. **Update Camera & Projection in `PlayerRenderer.java`**:
   - In `render(int width, int height)`:
     ```java
     float aspect = (float) width / (float) height;
     if (aspect != lastAspect) {
         // Dynamically compute FOV or adjust near/far planes based on aspect ratio
         proj.setPerspective(FOV_Y, aspect, NEAR, FAR);
         lastAspect = aspect;
     }
     ```
   - Adjust the view matrix camera distance (`view.setLookAt(new Vector3f(0f, 30f, 100f), new Vector3f(0f, 32f, 0f), new Vector3f(0f, 1f, 0f));`) so the player model is centered vertically and fills ~80% of the viewport height.

---

### 1.2 Dual-Layer (Outer Overlay) 64x64 Skin Geometry
**Goal:** Render modern Minecraft 64x64 dual-layer skins (base layer + outer overlay for Head/Hat, Torso/Jacket, Arms/Sleeves, Legs/Pants) with proper transparency.

1. **Update `PlayerModel.java`**:
   - Expand vertex array size to accommodate **12 boxes** (6 inner body parts + 6 outer overlay parts).
   - Outer overlay boxes are slightly scaled outward (inflated by `+0.5` or `+0.25` coordinate units) to prevent Z-fighting with the base layer.
   - Map standard Minecraft 64x64 skin UV coordinates:

   ```
   ====================================================================================
   BODY PART           INNER LAYER UV (X, Y, W, H)      OUTER OVERLAY UV (X, Y, W, H)
   ====================================================================================
   Head / Hat          (0, 0, 32, 16)                   (32, 0, 32, 16)
   Torso / Jacket      (16, 16, 24, 16)                 (16, 32, 24, 16)
   Right Arm / Sleeve  (40, 16, 16, 16)                 (40, 32, 16, 16)
   Left Arm / Sleeve   (32, 48, 16, 16)                 (48, 48, 16, 16)
   Right Leg / Pants   (0, 16, 16, 16)                  (0, 32, 16, 16)
   Left Leg / Pants    (16, 48, 16, 16)                 (0, 48, 16, 16)
   ====================================================================================
   ```

2. **Detailed UV Face Bounds for Outer Layer (`PlayerModel.java`)**:
   ```java
   // Outer Overlay UV rectangles (64x64 layout):
   private static final float[][] HAT_UVS = {
       r(40, 8, 8, 8), r(56, 8, 8, 8), r(40, 0, 8, 8), r(48, 0, 8, 8), r(48, 8, 8, 8), r(32, 8, 8, 8)
   };
   private static final float[][] JACKET_UVS = {
       r(20, 36, 8, 12), r(32, 36, 8, 12), r(20, 32, 8, 4), r(28, 32, 8, 4), r(28, 36, 4, 12), r(16, 36, 4, 12)
   };
   private static final float[][] RIGHT_SLEEVE_UVS = {
       r(44, 36, 4, 12), r(52, 36, 4, 12), r(44, 32, 4, 4), r(48, 32, 4, 4), r(48, 36, 4, 12), r(40, 36, 4, 12)
   };
   private static final float[][] LEFT_SLEEVE_UVS = {
       r(52, 52, 4, 12), r(60, 52, 4, 12), r(52, 48, 4, 4), r(56, 48, 4, 4), r(56, 52, 4, 12), r(48, 52, 4, 12)
   };
   private static final float[][] RIGHT_PANTS_UVS = {
       r(4, 36, 4, 12), r(12, 36, 4, 12), r(4, 32, 4, 4), r(8, 32, 4, 4), r(8, 36, 4, 12), r(0, 36, 4, 12)
   };
   private static final float[][] LEFT_PANTS_UVS = {
       r(4, 52, 4, 12), r(12, 52, 4, 12), r(4, 48, 4, 4), r(8, 48, 4, 4), r(8, 52, 4, 12), r(0, 52, 4, 12)
   };
   ```

3. **Enable Alpha Blending in `PlayerRenderer.java`**:
   - In `init()` / `render()`:
     ```java
     GL11.glEnable(GL11.GL_BLEND);
     GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
     ```
   - Update `FRAGMENT_SRC` GLSL shader to ensure alpha transparency is retained:
     ```glsl
     #version 330 core
     in vec3 vNormal;
     in vec2 vUV;
     uniform sampler2D uTexture;
     uniform vec3 uLightDir;
     uniform vec3 uLightColor;
     uniform vec3 uAmbient;
     out vec4 fragColor;

     void main() {
         vec4 texColor = texture(uTexture, vUV);
         if (texColor.a < 0.1) discard; // Discard fully transparent pixels on overlay
         vec3 n = normalize(vNormal);
         float diffuse = max(dot(n, uLightDir), 0.0);
         fragColor = vec4(texColor.rgb * (uAmbient + uLightColor * diffuse), texColor.a);
     }
     ```

---

### 1.3 Pitch Rotation (Vertical Mouse Drag Look Up/Down ±45°)
**Goal:** Allow mouse dragging vertically to tilt the player model up/down by up to ±45 degrees (±0.785 radians).

1. **Update `PlayerRenderer.java`**:
   - Add `private volatile float pitchRadians = 0f;` and `public void setPitch(float radians)`.
   - Update model transformation in `render()`:
     ```java
     model.identity()
          .rotateX(pitchRadians)
          .rotateY(rotationRadians);
     ```

2. **Update `Player3DRenderer.java` Mouse Handlers**:
   - Track `mouseAnchorY` alongside `mouseAnchorX`.
   - In `setOnMousePressed`:
     `mouseAnchorX = e.getSceneX(); mouseAnchorY = e.getSceneY();`
   - In `setOnMouseDragged`:
     ```java
     double deltaX = e.getSceneX() - mouseAnchorX;
     double deltaY = e.getSceneY() - mouseAnchorY;

     rotationDegrees += deltaX * 0.5;
     pitchDegrees = Math.max(-45.0, Math.min(45.0, pitchDegrees - deltaY * 0.5));

     player.setRotation((float) Math.toRadians(rotationDegrees));
     player.setPitch((float) Math.toRadians(pitchDegrees));

     mouseAnchorX = e.getSceneX();
     mouseAnchorY = e.getSceneY();
     viewport.requestRender();
     ```

---

## Phase 2: Automatic Mojang Skin Fetch & Redesigned Skin Screen

### 2.1 Startup Mojang Skin Fetch
**Goal:** Automatically download the player's active Mojang skin upon application launch / sign-in, apply it across all 3D previews and sidebars, and show a status spinner while fetching.

1. **Update `MainController.onSessionEstablished()`**:
   ```java
   private void onSessionEstablished() {
       userLabel.setText(session.getUsername());
       logoutButton.setVisible(true);
       loginButton.setDisable(false);
       loginStatus.setText("");
       showLoginView(false);
       status("Signed in as " + session.getUsername());
       
       // Automatically fetch active Mojang skin in background thread
       autoFetchMojangSkin();
   }

   private void autoFetchMojangSkin() {
       if (session == null || session.getUuid() == null) return;
       status("Syncing active skin from Mojang...");
       Thread.ofVirtual().name("mojang-auto-skin").start(() -> {
           try {
               MojangSkinService.DownloadedSkin skin = MojangSkinService.download(session.getUuid());
               Path tmp = Files.createTempFile("mojang-active-", ".png");
               Files.write(tmp, skin.png());
               SkinManager.saveSkin(tmp.toFile());
               Files.deleteIfExists(tmp);
               Platform.runLater(() -> {
                   refreshPlayerSkins();
                   populateRecentSkins();
                   status("Active Mojang skin synced.");
               });
           } catch (Exception e) {
               log.warn("Could not auto-fetch Mojang skin: {}", e.getMessage());
               Platform.runLater(() -> refreshPlayerSkins());
           }
       });
   }
   ```

---

### 2.2 Redesigned Skins Gallery Layout & Interactions
**Goal:** Implement a clean, card-based layout featuring a large 3D preview mannequin on the left with a prominent **SAVE** button, and a responsive grid of selectable skin cards on the right.

```
+-----------------------------------+---------------------------------------------------+
|  [ 3D PLAYER PREVIEW ]            |  SKIN GALLERY GRID                                |
|                                   |  +---------+  +---------+  +---------+  +---------+ |
|                                   |  |    +    |  |  (3D)   |  |  (3D)   |  |  (3D)   | |
|                                   |  |  Upload |  |  Steve  |  |  Alex   |  |  Custom | |
|                                   |  +---------+  +---------+  +---------+  +---------+ |
|                                   |  +---------+  +---------+  +---------+            |
|                                   |  |  (3D)   |  |  (3D)   |  |  (3D)   |            |
|                                   |  | Skin #1 |  | Skin #2 |  | Skin #3 |            |
|                                   |  +---------+  +---------+  +---------+            |
|  +-----------------------------+  |                                                   |
|  |           SAVE              |  |                                                   |
|  +-----------------------------+  |                                                   |
+-----------------------------------+---------------------------------------------------+
```

1. **Refactor `skinsView` in `MainApp.java`**:
   - **Left Panel (Preview & Action)**:
     - Expand `skinsPlayerBox` (`Player3DRenderer` node) inside a dark card container (`#161b22`, border `#30363d`, radius `12px`).
     - Place a prominent **SAVE** button directly under the 3D viewport:
       ```java
       Button saveSkinBtn = new Button("SAVE");
       saveSkinBtn.setMaxWidth(Double.MAX_VALUE);
       saveSkinBtn.setStyle("-fx-background-color: #2da44e; -fx-text-fill: white; "
               + "-fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 10 20; "
               + "-fx-background-radius: 8; -fx-cursor: hand;");
       ```
   - **Right Panel (Gallery Grid)**:
     - Use a `TilePane` or wrapped `FlowPane` inside a `ScrollPane` with dark styling (`hgap: 12`, `vgap: 12`).
     - **Card 1 (Upload/Add)**:
       - Styled tile with a large white `+` icon and text `"Add Skin"`. Clicking triggers `FileChooser` to add a new PNG skin.
     - **Card Entries (Defaults & History)**:
       - Each skin card contains a 2D or 3D mini preview icon, label, and active selection border (`#2da44e`).
       - Clicking a card updates the main 3D player preview on the left to preview the skin.
       - Clicking the **SAVE** button persists the selected skin locally and uploads it to Mojang via `MojangSkinService.upload(session.getAccessToken(), skinPath, "classic")`.

---

## Phase 3: Play Offline Screen Polish & Shader Integration

### 3.1 Offline View Layout Containerization
**Goal:** Provide clear visual hierarchy and modern styling for singleplayer / offline instances.

1. **Refactor `offlineView` Containers in `MainApp.java`**:
   - **Left Column (Offline Instance List)**:
     - Wrap in a dedicated container (`#161b22`, border `#30363d`, radius `12px`, padding `16px`).
     - World cards feature game mode badges, MC version tags, and an active highlight ring.
   - **Right Column (World Detail & Options Panel)**:
     - Wrap in a matching card container.
     - Group controls into clean sub-cards:
       1. **Instance Metadata**: Name, MC Version, Mod Loader, Loader Version.
       2. **Game Settings**: Gamemode selector (`ComboBox`), Allow Cheats (`CheckBox`).
       3. **Mods Management**: Drag-and-drop `.jar` dropzone, Installed Mods list, Modrinth search bar.
       4. **Shaders & Texture Packs** *(Integrated from standalone view)*.

---

### 3.2 Integrated Shaders & Texture Packs in Offline View
**Goal:** Allow singleplayer offline instances to manage and enable local shaderpacks and texture packs directly within the Offline World view.

1. **Add Offline Shader & Pack Card to Offline Detail View**:
   - Include a Shaderpack selector (`ComboBox` or `RadioButton` list) and Texture Pack checkboxes in the offline instance settings panel.
   - Include a dropzone / file picker for local `.zip` shaderpacks and resourcepacks via `ClientPackManager`.

2. **Apply Options at Launch (`MinecraftRunner.java`)**:
   - When `launchOffline()` is invoked, load the instance's `PackSelection` from `<instanceDir>/pack-selection.json`.
   - Write shader and resourcepack settings using `PackOptionsWriter.apply(gameDir)` right before spawning the Java process.

---

## Phase 4: Removal of Standalone Shaders Tab & Server-Driven Shader Sync

### 4.1 Remove Standalone Navigation Tab
**Goal:** Eliminate the redundant standalone "Shaders & Packs" tab from the main sidebar.

1. **Update `MainApp.java`**:
   - Remove `Button navShadersPacks = navButton("🎨  Shaders & Packs");` from `navBox`.
   - Remove `shadersPacksView` from `centerContainer`.

2. **Update `MainController.java`**:
   - Remove navigation handling for `navShadersPacks` and `shadersPacksView`.

---

### 4.2 Server-Driven Shader & Pack Prompting
**Goal:** When connecting to a mod-synced Zircon server, inspect the server's `BillOfMaterials`. If shaderpacks or resourcepacks are offered, prompt the player with a confirmation dialog.

1. **Update `MainController.runFlow(serverAddress)`**:
   ```java
   BillOfMaterials bom = fetchBom(baseUrl);

   // Check if server offers shaderpacks or resourcepacks
   boolean hasPacks = (bom.getShaderpacks() != null && !bom.getShaderpacks().isEmpty())
                   || (bom.getResourcepacks() != null && !bom.getResourcepacks().isEmpty());

   if (hasPacks) {
       Platform.runLater(() -> {
           boolean enablePacks = promptUserForServerPacks(bom);
           if (enablePacks) {
               status("Syncing server shaderpacks & texture packs...");
               PackSelection selection = PackSelection.load(gameDir);
               packSyncEngine.sync(bom, baseUrl, gameDir, 
                       selection.getLocallyAddedShaderpacks(), 
                       selection.getLocallyAddedResourcepacks(), 
                       msg -> status(msg));
                       
               // Enable packs in selection and write options.txt / optionsiris.txt
               selection.setShadersEnabled(true);
               if (!bom.getShaderpacks().isEmpty()) {
                   selection.setActiveShaderpack(bom.getShaderpacks().get(0).getFilename());
               }
               selection.setActiveResourcepacks(bom.getResourcepacks().stream()
                       .map(PackEntry::getFilename).toList());
               selection.save(gameDir);
           }
       });
   }
   ```

2. **Prompt Dialog Specification**:
   - Dialog Title: `"Server Recommended Packs"`
   - Message: *"This server recommends the following shader & texture packs for optimal gameplay: [Pack Names]. Would you like to download and enable them?"*
   - Options: `[ Enable & Sync ]` / `[ Play Vanilla/No Packs ]`

---

## Phase 5: Testing, Verification & Integration Steps

### 5.1 Unit & Integration Testing
1. **3D Render Tests (`PlayerModelTest.java`, `PlayerRendererTest.java`)**:
   - Verify vertex counts for 12-box dual-layer model geometry (`12 * 36 * 8 = 3456` floats).
   - Test pitch clamping (-45° to +45°) in `PlayerRenderer`.
   - Test alpha blending for transparent overlay pixels in GL framebuffer readback.

2. **Skin System Tests (`SkinManagerTest.java`, `MojangSkinServiceTest.java`)**:
   - Verify asynchronous skin fetch and active skin file persistence.
   - Verify custom skin upload payload construction.

3. **Offline & Pack Option Tests (`PackSelectionTest.java`, `PackOptionsWriterTest.java`)**:
   - Verify `optionsiris.txt` and `options.txt` generation before launch.

### 5.2 Build Verification
- Execute `./gradlew test` to ensure all core, server, and client tests pass.
- Run launcher and verify smooth 60 FPS 3D skin rotation and pitch controls.
