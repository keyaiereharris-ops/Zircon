# AGENT_PLAN.md: Automated Minecraft Server Manager & 1-Click Client Launcher

## 1. System Overview & Tech Stack
- **Target Java Version**: Java 21 LTS (utilizing virtual threads `Thread.ofVirtual()` where applicable).
- **Build Tool**: Gradle Multi-Module (`:shared-core`, `:server-manager`, `:client-launcher`).
- **Architecture Pattern**: Shared Core DTO/Utils + Asynchronous Event-Driven Backend + JavaFX MVC Frontend.

### Key Libraries
- **Shared**: `com.google.code.gson:gson:2.10.1`, `org.slf4j:slf4j-api:2.0.12`, `ch.qos.logback:logback-classic:1.5.3`.
- **Server Manager**: `io.javalin:javalin:6.1.3`, `io.netty:netty-all:4.1.108.Final`.
- **Client Launcher**: `org.openjfx:javafx-controls:21.0.2`, `io.github.mkpaz:atlantafx-base:2.0.1`, `net.hycrafthd:minecraft-authenticator:4.0.0` (or native OAuth2/Xbox HTTP requests).

---

## 2. Directory & Module Blueprint

```
root/
├── build.gradle
├── settings.gradle
├── AGENT_PLAN.md
│
├── shared-core/
│   └── src/main/java/com/mcmanager/core/
│       ├── model/
│       │   ├── BillOfMaterials.java
│       │   ├── ModLoaderInfo.java
│       │   └── ModEntry.java
│       ├── crypto/
│       │   ├── HashUtil.java
│       │   └── MurmurHash3.java
│       └── api/
│           ├── ModrinthApiClient.java
│           └── CurseForgeApiClient.java
│
├── server-manager/
│   └── src/main/java/com/mcmanager/server/
│       ├── Main.java
│       ├── multiplexer/
│       │   ├── TcpMultiplexer.java
│       │   ├── ProtocolDetector.java
│       │   └── ProxyHandler.java
│       ├── process/
│       │   ├── MinecraftProcessManager.java
│       │   └── ConsoleStreamHandler.java
│       ├── service/
│       │   ├── BomService.java
│       │   ├── ModManagementService.java
│       │   └── ConfigService.java
│       └── web/
│           ├── JavalinApp.java
│           └── controller/
│               ├── BomController.java
│               ├── ModController.java
│               ├── ConsoleController.java
│               ├── PlayerController.java
│               └── ConfigController.java
│
└── client-launcher/
    └── src/main/java/com/mcmanager/client/
        ├── Main.java
        ├── auth/
        │   ├── MicrosoftAuthService.java
        │   └── SessionData.java
        ├── sync/
        │   ├── ModSyncEngine.java
        │   └── HashVerifier.java
        ├── launch/
        │   ├── MinecraftClasspathBuilder.java
        │   └── MinecraftRunner.java
        └── ui/
            ├── MainApp.java
            ├── view/
            │   └── MainView.fxml (or Pure JavaFX Layouts)
            └── controller/
                └── MainController.java
```

---

## 3. Detailed Phase Breakdown & Agent Implementation Notes

---

### PHASE 1: `:shared-core` (Shared Models, Crypto, and API Clients)

#### Task 1.1: Implement Data Transfer Objects (DTOs)
- **Path**: `shared-core/src/main/java/com/mcmanager/core/model/`
- **Classes to Create**: `BillOfMaterials.java`, `ModLoaderInfo.java`, `ModEntry.java`.
- **Implementation Notes**:
  - `BillOfMaterials`: Holds `minecraftVersion` (String), `modLoader` (`ModLoaderInfo`), `mods` (`List<ModEntry>`), `serverTitle` (String), `schemaVersion` (int, start at `1`).
  - `ModLoaderInfo`: Holds `type` (`"fabric"`, `"neoforge"`, `"forge"`, `"quilt"`), `version` (String), `loaderJarUrl` (String).
  - `ModEntry`: Holds `id` (String), `filename` (String), `sha1` (String), `murmur3` (long), `origin` (`"modrinth"`, `"curseforge"`, `"direct"`), `downloadUrl` (String), `fileSize` (long).
  - Use `@SerializedName` annotations on JSON fields to prevent breaking changes during refactoring.

#### Task 1.2: Implement Cryptographic Hash Utilities (`HashUtil.java`)
- **Path**: `shared-core/src/main/java/com/mcmanager/core/crypto/HashUtil.java`
- **Implementation Notes**:
  - Implement `public static String getSha1(Path filePath)` and `public static String getSha256(Path filePath)`.
  - Use `java.security.MessageDigest` streaming over `InputStream` with an 8192-byte buffer so large `.jar` files don't cause `OutOfMemoryError`.
  - Format output as lower-case hexadecimal string.

#### Task 1.3: Implement CurseForge MurmurHash3 (`MurmurHash3.java`)
- **Path**: `shared-core/src/main/java/com/mcmanager/core/crypto/MurmurHash3.java`
- **Implementation Notes**:
  - CurseForge uses a custom 32-bit MurmurHash3 algorithm that **strips whitespace bytes** before computing the hash.
  - Ignored byte values: `0x09` (Tab), `0x0A` (LF), `0x0D` (CR), `0x20` (Space).
  - Algorithm Steps:
    1. Read `.jar` bytes into a byte array.
    2. Filter out the 4 whitespace byte values.
    3. Run standard 32-bit MurmurHash3 algorithm on the remaining bytes using seed `1`.
    4. Return the result as an unsigned `long` (masking with `0xFFFFFFFFL`).

#### Task 1.4: Implement Modrinth API Client (`ModrinthApiClient.java`)
- **Path**: `shared-core/src/main/java/com/mcmanager/core/api/ModrinthApiClient.java`
- **Implementation Notes**:
  - Use native `java.net.http.HttpClient` with virtual threads.
  - Set `User-Agent` header to `YourAppName/1.0.0 (contact@yourdomain.com)` (Modrinth requires a custom User-Agent).
  - **Method `verifyHashes(List<String> sha1List)`**:
    - Endpoint: `POST https://api.modrinth.com/v2/version_files`
    - Body: `{"hashes": ["hash1", "hash2"], "algorithm": "sha1"}`
    - Returns: Map of `<String (hash), ModrinthVersionObject>`. If a hash is present in the response map, it is verified as safe on Modrinth.
  - **Method `searchMods(String query, String mcVersion, String loaderType)`**:
    - Endpoint: `GET https://api.modrinth.com/v2/search?query={query}&facets=[["versions:{mcVersion}"],["categories:{loaderType}"]]`
    - Parse search results for the Server Admin GUI.

#### Task 1.5: Implement CurseForge API Client (`CurseForgeApiClient.java`)
- **Path**: `shared-core/src/main/java/com/mcmanager/core/api/CurseForgeApiClient.java`
- **Implementation Notes**:
  - Requires header `x-api-key: $YOUR_API_KEY`.
  - **Method `verifyFingerprints(List<Long> murmur3List)`**:
    - Endpoint: `POST https://api.curseforge.com/v1/fingerprints`
    - Body: `{"fingerprints": [12345678, 87654321]}`
    - Parse response payload `data.exactMatches`. Any matching fingerprint is verified.
  - **Method `searchMods(String query, String mcVersion)`**:
    - Endpoint: `GET https://api.curseforge.com/v1/mods/search?gameId=432&searchFilter={query}&gameVersion={mcVersion}`

---

### PHASE 2: `:server-manager` (Port Multiplexing, Server Process, Javalin Web API)

#### Task 2.1: Netty TCP Protocol Multiplexer (`TcpMultiplexer.java`)
- **Path**: `server-manager/src/main/java/com/mcmanager/server/multiplexer/`
- **Classes**: `TcpMultiplexer.java`, `ProtocolDetector.java`, `ProxyHandler.java`.
- **Implementation Notes**:
  - Server binds to public port `25565`.
  - **Detector Logic (`ProtocolDetector.java`)**:
    - Extends Netty's `ByteToMessageDecoder`.
    - Reads the first 4 bytes of incoming connection:
    - Check if the bytes start with ASCII HTTP methods: `GET ` (`0x47, 0x45, 0x54, 0x20`), `POST` (`0x50, 0x4F, 0x53, 0x54`), `HEAD`, `OPTI`.
    - **IF HTTP**: Remove `ProtocolDetector` from pipeline and insert an outbound Netty proxy channel to local Javalin HTTP port `127.0.0.1:25564`.
    - **IF BINARY (MC Handshake)**: Remove `ProtocolDetector` and insert outbound proxy channel to local internal Minecraft Server port `127.0.0.1:25566`.
  - **Proxy Handler (`ProxyHandler.java`)**:
    - Standard bidirectionally piped Netty channel proxy (`inboundChannel.read()` $\leftrightarrow$ `outboundChannel.writeAndFlush()`).

#### Task 2.2: Javalin Application & Web Server Setup (`JavalinApp.java`)
- **Path**: `server-manager/src/main/java/com/mcmanager/server/web/JavalinApp.java`
- **Implementation Notes**:
  - Configure Javalin to listen on `127.0.0.1:25564`.
  - Enable static file serving from `src/main/resources/web` to serve the Admin SPA GUI.
  - Enable WebSockets for live console streaming.
  - Register route controllers:
    - `GET /bom` $\rightarrow$ `BomController::getBom`
    - `GET /files/mods/{filename}` $\rightarrow$ `ModController::downloadMod`
    - `POST /api/mods/upload` $\rightarrow$ `ModController::uploadMod`
    - `GET /api/mods/search` $\rightarrow$ `ModController::searchMods`
    - `WS /api/console` $\rightarrow$ `ConsoleController` (WebSocket)
    - `GET/POST /api/players/whitelist` $\rightarrow$ `PlayerController::whitelist`
    - `GET/POST /api/players/bans` $\rightarrow$ `PlayerController::bans`
    - `GET/POST /api/config` $\rightarrow$ `ConfigController::properties`

#### Task 2.3: Minecraft Subprocess Launcher (`MinecraftProcessManager.java`)
- **Path**: `server-manager/src/main/java/com/mcmanager/server/process/MinecraftProcessManager.java`
- **Implementation Notes**:
  - Uses `java.lang.ProcessBuilder` to launch the server JAR.
  - Command: `java -Xms2G -Xmx4G -jar server.jar nogui --port 25566`.
  - Note: Minecraft server must be configured to bind to internal port `25566` so the Netty multiplexer on `25565` can proxy to it.
  - Capture process `stdout` and `stderr` streams via asynchronous thread or virtual thread.
  - Pass all output lines to `ConsoleStreamHandler`, which broadcasts lines to active WebSocket sessions (`WS /api/console`).
  - Provide a method `sendCommand(String command)` that writes to `Process.getOutputStream()` followed by `\n` and `flush()`.

#### Task 2.4: Admin Web UI (HTML/JS/Tailwind Dashboard)
- **Path**: `server-manager/src/main/resources/web/`
- **Implementation Notes**:
  - Single HTML file using Tailwind CSS CDN and Vue 3 (or Alpine.js) for quick reactive binding.
  - **Tab 1: Mod & Modloader Manager**:
    - Dropdown: Select Modloader (Fabric, NeoForge, Forge, Quilt) + Loader Version.
    - Search Bar: Query Modrinth/CurseForge APIs via backend `GET /api/mods/search`.
    - Modal: Select mod from search results $\rightarrow$ Upload matching `.jar` file to server wrapper $\rightarrow$ Auto-rebuild `bom.json`.
    - Mod List: Shows installed mods, origins (Modrinth/CurseForge/Direct), file sizes, and delete buttons.
  - **Tab 2: Server Console**:
    - Dark-mode terminal emulator (Xterm.js or pre-styled `<div>`).
    - Connects to `ws://<host>:25565/api/console`.
    - Auto-scroll checkbox + command input box at bottom.
  - **Tab 3: Player Management**:
    - Tables for Online Players, Whitelist, Ban List, OP List.
    - Quick actions: "Kick", "Ban", "Op", "Add to Whitelist". Sends commands directly to stdin via `ConsoleController`.
  - **Tab 4: Server Settings**:
    - GUI form parsing `server.properties`. Allows editing `motd`, `max-players`, `pvp`, `difficulty`, etc.

---

### PHASE 3: `:client-launcher` (JavaFX + AtlantaFX Client Launcher)

#### Task 3.1: JavaFX App Shell & AtlantaFX Theme (`MainApp.java`)
- **Path**: `client-launcher/src/main/java/com/mcmanager/client/ui/MainApp.java`
- **Implementation Notes**:
  - Initialize JavaFX Stage (Resolution: `900x550`, non-resizable or clean responsive layout).
  - Apply **AtlantaFX PrimerDark** theme:
    ```java
    Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
    ```
  - Create Window Controls (custom title bar or native OS title bar).

#### Task 3.2: Microsoft OAuth2 Authenticator (`MicrosoftAuthService.java`)
- **Path**: `client-launcher/src/main/java/com/mcmanager/client/auth/MicrosoftAuthService.java`
- **Implementation Notes**:
  - Authentication flow for Minecraft MSA:
    1. Start local temporary HTTP listener on `http://localhost:8080/callback`.
    2. Open user's default system browser (`Desktop.getDesktop().browse(...)`) to Microsoft OAuth Authorization URL:
       `https://login.live.com/oauth20_authorize.srf?client_id=<AZURE_CLIENT_ID>&response_type=code&redirect_uri=http://localhost:8080/callback&scope=XboxLive.signin%20offline_access`
    3. Capture auth `code` from HTTP redirect query parameters.
    4. Exchange `code` for Microsoft Access Token (`POST https://login.live.com/oauth20_token.srf`).
    5. Exchange Microsoft Access Token for Xbox Live (XBL) token (`POST https://user.auth.xboxlive.com/user/authenticate`).
    6. Exchange XBL token for XSTS token (`POST https://xsts.auth.xboxlive.com/xsts/authorize`).
    7. Exchange XSTS token for Minecraft Access Token (`POST https://api.minecraftservices.com/authentication/login_with_xbox`).
    8. Check entitlements (`GET https://api.minecraftservices.com/entitlements/mcstore`).
    9. Fetch Minecraft Profile (`GET https://api.minecraftservices.com/minecraft/profile`).
  - Store token, username, and UUID in encrypted local config file (`~/.mcmanager/auth_cache.json`).

#### Task 3.3: BOM Sync Engine (`ModSyncEngine.java`)
- **Path**: `client-launcher/src/main/java/com/mcmanager/client/sync/ModSyncEngine.java`
- **Implementation Notes**:
  - **Step 1**: GET `http://<server_address>/bom`. Parse JSON into `BillOfMaterials` instance.
  - **Step 2**: Extract SHA-1 hashes (for Modrinth mods) and MurmurHash3 fingerprints (for CurseForge mods).
  - **Step 3**: Execute batch verification request against Modrinth (`POST /v2/version_files`) and CurseForge (`POST /v1/fingerprints`).
  - **Step 4**: If any mod fails hash verification and is marked as `direct`, verify if user has accepted "Trust Custom Mods" setting. If not verified and strict mode is on, abort launch with error.
  - **Step 5**: Compare BOM mods with local `.minecraft/mods/`:
    - Delete any local `.jar` file whose hash does not exist in the BOM.
    - If local `.jar` is missing or hash doesn't match BOM, download `.jar` from `http://<server_address>/files/mods/<filename>`.
    - Report download progress (bytes downloaded / total bytes) to UI via JavaFX `Task<Void>` progress updates.

#### Task 3.4: Minecraft Process Execution (`MinecraftRunner.java`)
- **Path**: `client-launcher/src/main/java/com/mcmanager/client/launch/MinecraftRunner.java`
- **Implementation Notes**:
  - Downloads target Java runtime (Java 17 or Java 21 depending on MC version) using Adoptium/Azul API if missing locally.
  - Constructs launch classpath including:
    - Minecraft Client `.jar`
    - Modloader main `.jar` (Fabric Knot / NeoForge FML)
    - All library `.jar`s
  - Constructs execution command:
    ```bash
    java -Xmx4G -Djava.library.path=<natives_dir> \
      -cp <classpath> \
      <main_class> \
      --username <username> \
      --version <version> \
      --gameDir <game_dir> \
      --assetsDir <assets_dir> \
      --assetIndex <asset_index> \
      --uuid <uuid> \
      --accessToken <mc_access_token> \
      --userType msa \
      --versionType release \
      --server <server_ip> \
      --port <server_port>
    ```
  - `ProcessBuilder` launches game process; automatically connects player straight into the server!

#### Task 3.5: Launcher UI MVC Implementation (`MainView.fxml` & `MainController.java`)
- **Path**: `client-launcher/src/main/java/com/mcmanager/client/ui/`
- **UI Structure**:
  - **Top Navigation Bar**:
    - Left: Application Logo / Name.
    - Right: Avatar image + Username label + "Logout" button.
  - **Center Panel**:
    - Large Input Box: `Server Address` (e.g. `mc.example.com:25565`).
    - Status Label: e.g., *"Checking Mod Hashes..."*, *"Downloading Sodium (4/12)..."*.
    - Progress Bar (`ProgressBar`): Shows sync progress percentage.
  - **Bottom Action Area**:
    - Giant Action Button (`Button.accent` style from AtlantaFX):
      - Text switches dynamically based on state: `"SIGN IN WITH MICROSOFT"`, `"PLAY"`, `"SYNCING..."`, `"GAME RUNNING"`.

---

### PHASE 4: Integration, Testing, & Edge Case Handling

#### Task 4.1: Edge Case - Handling Mods with Disabled 3rd-Party Distribution
- **Notes**: If a mod on CurseForge has 3rd-party distribution disabled, CurseForge API hash lookup succeeds (confirming mod safety), but direct CDN download fails.
- **Handling**: The Server Manager will host the `.jar` directly on the server host (`http://server:25565/files/mods/...`). The Client verifies the hash against CurseForge API to confirm safety, then downloads the file from the Server Manager URL.

#### Task 4.2: Edge Case - Connection Lost During Play
- **Notes**: Netty proxy on port 25565 must handle abrupt socket disconnects cleanly without throwing uncaught exceptions or leaking open TCP channels.
- **Handling**: Implement `exceptionCaught` handlers in Netty `ProxyHandler` that close both client and backend channels on socket reset (`ECONNRESET`).

#### Task 4.3: Packaging & Shadow Jars
- **Notes**: Build executable fat-jars for both `:server-manager` and `:client-launcher`.
- **Handling**: Configure Gradle `com.github.johnrengelman.shadow` plugin for both subprojects. Ensure JavaFX native platform libraries (Windows, macOS, Linux) are properly bundled or targeted.

---

## 6. Execution Checklist for the Agent

1. [x] **Build Setup**: Initialize root `build.gradle` and subprojects `:shared-core`, `:server-manager`, `:client-launcher`.
2. [x] **Shared Core**: Create `BillOfMaterials`, `ModEntry`, `ModLoaderInfo`. Write `HashUtil` and `MurmurHash3`.
3. [x] **API Clients**: Write `ModrinthApiClient` and `CurseForgeApiClient` integration classes.
4. [x] **Multiplexer**: Implement Netty `TcpMultiplexer` with HTTP vs MC protocol detection.
5. [x] **Javalin Server**: Build REST endpoints for BOM, file downloads, mod search, and settings.
6. [x] **Process Manager**: Build `MinecraftProcessManager` with stdout WebSocket streaming.
7. [x] **Admin Web UI**: Build Vue 3 / HTML dashboard in `server-manager/src/main/resources/web`.
8. [x] **Client UI**: Set up JavaFX + AtlantaFX UI layout with Microsoft login & server address input.
9. [x] **Microsoft Auth**: Build OAuth2 PKCE login flow and token storage.
10. [x] **Client Sync**: Build `ModSyncEngine` to fetch BOM, verify hashes, download missing `.jar`s, and launch Minecraft.
11. [x] **Integration Test**: Verify end-to-end sync and automatic server connection on port 25565.
