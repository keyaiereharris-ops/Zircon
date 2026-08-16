# Implementation Plan: McManager Feature Refactor & Enhancements

This document provides a step-by-step, actionable implementation plan for a coding agent to execute all 5 feature updates cleanly across `shared-core`, `server-manager`, and the web frontend.

---

## Architecture Overview & File Map

| Component | Target File Path | Action | Description |
| :--- | :--- | :--- | :--- |
| **Core Models** | `main/java/com/mcmanager/core/model/ModEntry.java` | **Modify** | Add rich mod metadata fields (`title`, `description`, `iconUrl`, `author`, `compatible`, `warningMessage`). |
| **Core Models** | `main/java/com/mcmanager/core/model/InstanceConfig.java` | **Modify** | Un-hide version setters (`setLoaderVersion`, `setMinecraftVersion`) while leaving `modLoader.type` locked. |
| **Auth** | `main/java/com/mcmanager/server/auth/AuthService.java` | **Modify** | Store `UserProfile` objects (`username`, `passwordHash`, `icon`) and provide profile update logic. |
| **Metrics** | `main/java/com/mcmanager/server/stats/SystemMetricsService.java` | **Create** | Sample CPU %, RAM usage, Disk space, active instances, and rolling 60s history. |
| **Mod Service** | `main/java/com/mcmanager/server/service/ModManagementService.java` | **Modify** | Add `syncModsForVersionChange` for version upgrades and auto-populate rich metadata on mod ingest. |
| **Instance Engine** | `main/java/com/mcmanager/server/instance/ServerInstanceManager.java` | **Modify** | Handle instance version updates and trigger mod compatibility sync. |
| **Controllers** | `main/java/com/mcmanager/server/web/controller/InstanceController.java` | **Modify** | Add instance-scoped player management routes, server properties, and version change PATCH endpoint. |
| **App Routing** | `main/java/com/mcmanager/server/web/JavalinApp.java` | **Modify** | Wire `/api/auth/me`, `/api/auth/profile`, `/api/stats`, and player management routes. |
| **Web Frontend** | `main/resources/web/index.html` | **Modify** | Add User Profile Modal, Real-Time Stats Tab, Player Management Tab, Version Switcher, and Rich Mods view. |

---

## Phase 1: Core Data Models

### Step 1.1: Update `ModEntry.java`
**File:** `main/java/com/mcmanager/core/model/ModEntry.java`

**Task:** Add fields for rich metadata and mod compatibility flags.

```java
package com.mcmanager.core.model;

import com.google.gson.annotations.SerializedName;
import java.util.HashMap;
import java.util.Map;

public class ModEntry {

    @SerializedName("id")
    private String id;

    @SerializedName("filename")
    private String filename;

    @SerializedName("sha1")
    private String sha1;

    @SerializedName("murmur3")
    private long murmur3;

    @SerializedName("origin")
    private String origin;

    @SerializedName("downloadUrl")
    private String downloadUrl;

    @SerializedName("fileSize")
    private long fileSize;

    // --- Rich Metadata Additions ---
    @SerializedName("title")
    private String title;

    @SerializedName("description")
    private String description;

    @SerializedName("iconUrl")
    private String iconUrl;

    @SerializedName("author")
    private String author;

    @SerializedName("compatible")
    private boolean compatible = true;

    @SerializedName("warningMessage")
    private String warningMessage;

    public ModEntry() {}

    public ModEntry(String id, String filename, String sha1, long murmur3, String origin,
                    String downloadUrl, long fileSize) {
        this.id = id;
        this.filename = filename;
        this.sha1 = sha1;
        this.murmur3 = murmur3;
        this.origin = origin;
        this.downloadUrl = downloadUrl;
        this.fileSize = fileSize;
    }

    // Existing getters/setters...
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getSha1() { return sha1; }
    public void setSha1(String sha1) { this.sha1 = sha1; }
    public long getMurmur3() { return murmur3; }
    public void setMurmur3(long murmur3) { this.murmur3 = murmur3; }
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public String getDownloadUrl() { return downloadUrl; }
    public void setDownloadUrl(String downloadUrl) { this.downloadUrl = downloadUrl; }
    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    // --- New Getters & Setters ---
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getIconUrl() { return iconUrl; }
    public void setIconUrl(String iconUrl) { this.iconUrl = iconUrl; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public boolean isCompatible() { return compatible; }
    public void setCompatible(boolean compatible) { this.compatible = compatible; }

    public String getWarningMessage() { return warningMessage; }
    public void setWarningMessage(String warningMessage) { this.warningMessage = warningMessage; }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        map.put("filename", filename);
        map.put("sha1", sha1);
        map.put("murmur3", murmur3);
        map.put("origin", origin);
        map.put("downloadUrl", downloadUrl);
        map.put("fileSize", fileSize);
        map.put("title", title != null ? title : filename);
        map.put("description", description != null ? description : "");
        map.put("iconUrl", iconUrl != null ? iconUrl : "");
        map.put("author", author != null ? author : "");
        map.put("compatible", compatible);
        map.put("warningMessage", warningMessage != null ? warningMessage : "");
        return map;
    }
}
```

---

### Step 1.2: Update `InstanceConfig.java`
**File:** `main/java/com/mcmanager/core/model/InstanceConfig.java`

**Task:** Allow updating loader and Minecraft version strings while ensuring `modLoader.type` cannot be changed after initialization.

```java
// Add to InstanceConfig.java
public void setLoaderVersion(String loaderVersion) {
    if (this.modLoader == null) {
        this.modLoader = new ModLoaderInfo("vanilla", loaderVersion, "");
    } else {
        this.modLoader.setVersion(loaderVersion);
    }
}
```

---

## Phase 2: Server Backend Services

### Step 2.1: Enhance `AuthService.java`
**File:** `main/java/com/mcmanager/server/auth/AuthService.java`

**Task:** Expand `users.json` schema to support `UserProfile` objects (`username`, `passwordHash`, `icon`) and provide atomic profile updates.

```java
package com.mcmanager.server.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type USERS_TYPE = new TypeToken<Map<String, UserProfile>>() {}.getType();

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*";

    private static volatile Path usersFile;
    private static volatile Map<String, UserProfile> users = new LinkedHashMap<>();

    public static class UserProfile {
        public String username;
        public String passwordHash;
        public String icon = "emerald";

        public UserProfile() {}

        public UserProfile(String username, String passwordHash, String icon) {
            this.username = username;
            this.passwordHash = passwordHash;
            this.icon = icon != null ? icon : "emerald";
        }
    }

    private AuthService() {}

    public static void initializeAuth(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        usersFile = dataDir.resolve("users.json");
        if (Files.exists(usersFile)) {
            users = load(usersFile);
            return;
        }

        String initialPassword = generateRandomPassword(16);
        String hashedPassword = BCrypt.hashpw(initialPassword, BCrypt.gensalt(12));
        users = new LinkedHashMap<>();
        users.put("admin", new UserProfile("admin", hashedPassword, "emerald"));
        save();

        System.out.println("=================================================");
        System.out.println("  ZIRCON SERVER CREATED INITIAL ADMIN USER");
        System.out.println("  Username: admin");
        System.out.println("  Password: " + initialPassword);
        System.out.println("  Please log in and change your password!");
        System.out.println("=================================================");
        log.info("Created initial admin user; password printed to stdout");
    }

    public static synchronized boolean authenticate(String username, String password) {
        UserProfile user = users.get(username);
        return user != null && password != null && BCrypt.checkpw(password, user.passwordHash);
    }

    public static synchronized UserProfile getUser(String username) {
        return users.get(username);
    }

    public static synchronized boolean updateProfile(String currentUsername, String newUsername,
                                                     String currentPassword, String newPassword,
                                                     String newIcon) throws IOException {
        if (!authenticate(currentUsername, currentPassword)) {
            return false;
        }

        UserProfile profile = users.get(currentUsername);
        if (profile == null) return false;

        String targetUser = (newUsername != null && !newUsername.isBlank()) ? newUsername.trim() : currentUsername;

        if (!targetUser.equalsIgnoreCase(currentUsername) && users.containsKey(targetUser)) {
            throw new IOException("Username '" + targetUser + "' is already taken");
        }

        if (newPassword != null && !newPassword.isBlank()) {
            if (newPassword.length() < 8) {
                throw new IOException("New password must be at least 8 characters");
            }
            profile.passwordHash = BCrypt.hashpw(newPassword, BCrypt.gensalt(12));
        }

        if (newIcon != null && !newIcon.isBlank()) {
            profile.icon = newIcon.trim();
        }

        if (!targetUser.equals(currentUsername)) {
            users.remove(currentUsername);
            profile.username = targetUser;
            users.put(targetUser, profile);
        }

        save();
        log.info("Profile updated for user {}", targetUser);
        return true;
    }

    private static Map<String, UserProfile> load(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, UserProfile> parsed = GSON.fromJson(content, USERS_TYPE);
            if (parsed != null && !parsed.isEmpty()) {
                return new LinkedHashMap<>(parsed);
            }
        } catch (Exception e) {
            log.warn("Could not parse {}, starting fresh", file);
        }
        return new LinkedHashMap<>();
    }

    private static void save() throws IOException {
        if (usersFile == null) {
            throw new IllegalStateException("initializeAuth must be called first");
        }
        Files.writeString(usersFile, GSON.toJson(users), StandardCharsets.UTF_8);
    }

    private static String generateRandomPassword(int length) {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
```

---

### Step 2.2: Create `SystemMetricsService.java`
**File:** `main/java/com/mcmanager/server/stats/SystemMetricsService.java`

**Task:** Create a real-time system metrics provider.

```java
package com.mcmanager.server.stats;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class SystemMetricsService {

    private static final int HISTORY_LIMIT = 60;
    private static final List<MetricPoint> history = new ArrayList<>();
    private static final OperatingSystemMXBean osBean =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    public record MetricPoint(
            long timestamp,
            double systemCpuLoad,
            double processCpuLoad,
            long usedMemoryBytes,
            long maxMemoryBytes,
            long totalDiskBytes,
            long freeDiskBytes
    ) {}

    public static synchronized MetricPoint sample(Path dataDir) {
        double sysCpu = Math.max(0, osBean.getCpuLoad() * 100.0);
        double procCpu = Math.max(0, osBean.getProcessCpuLoad() * 100.0);

        Runtime runtime = Runtime.getRuntime();
        long totalMem = runtime.totalMemory();
        long freeMem = runtime.freeMemory();
        long usedMem = totalMem - freeMem;
        long maxMem = runtime.maxMemory();

        long totalDisk = 0;
        long freeDisk = 0;
        try {
            FileStore store = Files.getFileStore(dataDir);
            totalDisk = store.getTotalSpace();
            freeDisk = store.getUnallocatedSpace();
        } catch (Exception ignored) {}

        MetricPoint point = new MetricPoint(
                System.currentTimeMillis(),
                Math.round(sysCpu * 10.0) / 10.0,
                Math.round(procCpu * 10.0) / 10.0,
                usedMem,
                maxMem,
                totalDisk,
                freeDisk
        );

        history.add(point);
        if (history.size() > HISTORY_LIMIT) {
            history.remove(0);
        }
        return point;
    }

    public static synchronized Map<String, Object> getMetricsSnapshot(Path dataDir) {
        MetricPoint current = sample(dataDir);
        Map<String, Object> map = new HashMap<>();
        map.put("current", current);
        map.put("history", new ArrayList<>(history));
        return map;
    }
}
```

---

### Step 2.3: Update `ModManagementService.java`
**File:** `main/java/com/mcmanager/server/service/ModManagementService.java`

**Task:**
1. Populate rich mod metadata (`title`, `description`, `iconUrl`, `author`) when installing mods.
2. Add `syncModsForVersionChange` to auto-fetch compatible versions upon Minecraft/loader version changes.

```java
// Add to ModManagementService.java

public synchronized Map<String, Object> syncModsForVersionChange(String newMcVersion, String loaderType, String newLoaderVersion) throws IOException {
    BillOfMaterials bom = bomService.getBom();
    bom.setMinecraftVersion(newMcVersion);
    if (bom.getModLoader() != null) {
        bom.getModLoader().setVersion(newLoaderVersion);
    }

    int updatedCount = 0;
    int incompatibleCount = 0;
    List<String> updatedMods = new ArrayList<>();
    List<String> incompatibleMods = new ArrayList<>();

    for (ModEntry mod : new ArrayList<>(bom.getMods())) {
        String origin = mod.getOrigin();
        boolean foundCompat = false;

        if ("modrinth".equalsIgnoreCase(origin) && mod.getId() != null) {
            try {
                List<ModrinthApiClient.ModrinthVersion> versions = modrinth.listProjectVersions(mod.getId(), newMcVersion, loaderType);
                if (!versions.isEmpty()) {
                    ModrinthApiClient.ModrinthVersion chosen = versions.get(0);
                    ModrinthApiClient.ModrinthFile primary = chosen.primaryFile();
                    if (primary != null) {
                        Path oldFile = modsDir.resolve(mod.getFilename());
                        Files.deleteIfExists(oldFile);

                        ModEntry newEntry = installFromUrl(primary.url, primary.filename, ORIGIN_MODRINTH);
                        newEntry.setId(mod.getId());
                        newEntry.setTitle(mod.getTitle());
                        newEntry.setIconUrl(mod.getIconUrl());
                        newEntry.setAuthor(mod.getAuthor());
                        newEntry.setCompatible(true);

                        bom.removeMod(mod.getFilename());
                        bom.addMod(newEntry);

                        foundCompat = true;
                        updatedCount++;
                        updatedMods.add(newEntry.getFilename());
                    }
                }
            } catch (Exception e) {
                log.warn("Auto-update failed for Modrinth mod {}: {}", mod.getFilename(), e.getMessage());
            }
        }

        if (!foundCompat) {
            mod.setCompatible(false);
            mod.setWarningMessage("Unverified for MC " + newMcVersion + " (" + loaderType + ")");
            incompatibleCount++;
            incompatibleMods.add(mod.getFilename());
        }
    }

    bomService.save();
    Map<String, Object> summary = new HashMap<>();
    summary.put("updatedCount", updatedCount);
    summary.put("incompatibleCount", incompatibleCount);
    summary.put("updatedMods", updatedMods);
    summary.put("incompatibleMods", incompatibleMods);
    return summary;
}
```

---

### Step 2.4: Update `ServerInstanceManager.java`
**File:** `main/java/com/mcmanager/server/instance/ServerInstanceManager.java`

**Task:** Expose version update logic and execute mod compatibility sync.

```java
// Add to ServerInstanceManager.java
public synchronized Map<String, Object> updateInstanceVersions(String instanceId, String newMcVersion, String newLoaderVersion, String newName) throws IOException {
    InstanceConfig config = getInstance(instanceId);
    if (newName != null && !newName.isBlank()) config.setName(newName);
    if (newMcVersion != null && !newMcVersion.isBlank()) config.setMinecraftVersion(newMcVersion);
    if (newLoaderVersion != null) config.setLoaderVersion(newLoaderVersion);

    saveInstanceToDisk(config);

    Path instanceDir = instanceDir(instanceId);
    BomService bom = new BomService(instanceDir.resolve("bom.json"),
            new BillOfMaterials(config.getMinecraftVersion(), config.getModLoader(), config.getName()));
    ModManagementService mods = new ModManagementService(bom, instanceDir.resolve("mods"), "");

    return mods.syncModsForVersionChange(config.getMinecraftVersion(), config.getModLoader().getType(), config.getModLoader().getVersion());
}
```

---

## Phase 3: REST Controllers & Route Wiring

### Step 3.1: Update `InstanceController.java`
**File:** `main/java/com/mcmanager/server/web/controller/InstanceController.java`

**Task:** Add `PATCH /api/instances/{id}` logic to accept `mcVersion` and `loaderVersion` updates, and add player management helper methods.

```java
// Update PATCH /api/instances/{id} in InstanceController.java
public void updateInstance(Context ctx) {
    UpdateRequest body = ctx.bodyAsClass(UpdateRequest.class);
    try {
        String id = ctx.pathParam("id");
        Map<String, Object> syncResult = instanceManager.updateInstanceVersions(id,
                body.mcVersion, body.loaderVersion, body.name);
        ctx.json(syncResult);
    } catch (Exception e) {
        ctx.status(500).result("Version update failed: " + e.getMessage());
    }
}

public static class UpdateRequest {
    public String name;
    public String mcVersion;
    public String loaderVersion;
    public String javaArgs;
}
```

---

### Step 3.2: Wire Routes in `JavalinApp.java`
**File:** `main/java/com/mcmanager/server/web/JavalinApp.java`

**Task:** Wire up system stats, user profile endpoints, and player management routes.

```java
// Add to JavalinApp.java

// System Metrics
app.get("/api/stats", ctx -> ctx.json(SystemMetricsService.getMetricsSnapshot(configService.getDataDir())));

// Profile & Account Management
app.get("/api/auth/me", ctx -> {
    String token = ctx.header("Authorization");
    String username = JwtUtil.validateToken(token.substring(7));
    AuthService.UserProfile user = AuthService.getUser(username);
    ctx.json(Map.of("username", user.username, "icon", user.icon));
});

app.post("/api/auth/profile", ctx -> {
    ProfileUpdateRequest req = ctx.bodyAsClass(ProfileUpdateRequest.class);
    boolean ok = AuthService.updateProfile(req.currentUsername, req.newUsername,
            req.currentPassword, req.newPassword, req.icon);
    if (ok) {
        ctx.json(Map.of("ok", true));
    } else {
        ctx.status(400).result("Invalid credentials or username taken");
    }
});
```

---

## Phase 4: Frontend Web Admin (`index.html`)

### Step 4.1: Update Frontend (`index.html`)
**File:** `main/resources/web/index.html`

**Task:** Replace `index.html` with the enhanced Vue 3 Single Page Application implementing all 5 features.

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Zircon Server Admin</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <script src="https://unpkg.com/vue@3/dist/vue.global.prod.js"></script>
</head>
<body class="bg-slate-950 text-slate-100 font-sans h-screen flex overflow-hidden">
<div id="app" class="flex w-full h-full">

    <!-- LOGIN OVERLAY -->
    <div v-if="!authenticated" class="fixed inset-0 bg-slate-950/90 z-50 flex items-center justify-center">
        <div class="bg-slate-900 border border-slate-800 p-8 rounded-xl w-96 shadow-2xl">
            <h2 class="text-2xl font-bold text-emerald-400 mb-1">Zircon Admin</h2>
            <p class="text-xs text-slate-400 mb-6">Enter credentials printed to stdout on startup.</p>
            <form @submit.prevent="login">
                <label class="block text-xs text-slate-400 mb-1">Username</label>
                <input v-model="loginForm.username" class="w-full bg-slate-800 rounded px-3 py-2 text-sm mb-4 border border-slate-700">
                <label class="block text-xs text-slate-400 mb-1">Password</label>
                <input type="password" v-model="loginForm.password" class="w-full bg-slate-800 rounded px-3 py-2 text-sm mb-6 border border-slate-700">
                <button type="submit" class="w-full bg-emerald-600 hover:bg-emerald-500 font-semibold py-2 rounded text-sm transition">Log In</button>
            </form>
        </div>
    </div>

    <!-- MAIN APP SHELL -->
    <div v-else class="flex w-full h-full">

        <!-- LEFT SIDEBAR -->
        <aside class="w-64 bg-slate-900 border-r border-slate-800 flex flex-col p-4">
            <!-- Branding -->
            <div class="flex items-center gap-3 mb-6">
                <div class="w-8 h-8 rounded bg-emerald-500 font-bold text-slate-950 flex items-center justify-center">Z</div>
                <span class="font-bold text-lg tracking-wide">Zircon</span>
            </div>

            <!-- Global Stats Button (Positioned above Server List) -->
            <button @click="activeTab = 'stats'"
                    class="w-full mb-4 px-3 py-2 rounded-lg text-sm font-semibold flex items-center gap-2 transition"
                    :class="activeTab === 'stats' ? 'bg-emerald-600 text-white' : 'bg-slate-800/80 hover:bg-slate-800 text-slate-300'">
                <span>📊</span> System Stats
            </button>

            <!-- Active Instance Card -->
            <div v-if="selectedInstance && activeTab !== 'stats'" class="bg-slate-800 border border-slate-700/50 rounded-lg p-3 mb-4">
                <p class="text-xs text-slate-400">Current Server</p>
                <p class="font-semibold text-sm truncate">{{ selectedInstance.name }}</p>
                <div class="flex items-center gap-2 mt-1">
                    <span class="inline-block text-[10px] bg-emerald-500/20 text-emerald-300 px-2 py-0.5 rounded uppercase font-mono">
                        {{ selectedInstance.modLoader.type }} {{ selectedInstance.minecraftVersion }}
                    </span>
                    <span class="inline-flex items-center gap-1 text-[10px] font-mono"
                          :class="selectedInstance.running ? 'text-emerald-400' : 'text-slate-500'">
                        <span class="w-1.5 h-1.5 rounded-full" :class="selectedInstance.running ? 'bg-emerald-400' : 'bg-slate-600'"></span>
                        {{ selectedInstance.running ? selectedInstance.playerCount + ' online' : 'offline' }}
                    </span>
                </div>
                <button v-if="!selectedInstance.running" @click="startInstance"
                        class="mt-2 w-full bg-emerald-600 hover:bg-emerald-500 text-xs font-semibold py-1.5 rounded-lg transition">Start Server</button>
                <button v-else @click="stopInstance"
                        class="mt-2 w-full bg-red-600/80 hover:bg-red-500 text-xs font-semibold py-1.5 rounded-lg transition">Stop Server</button>
            </div>

            <!-- Add Server Button -->
            <button @click="showAddServerModal = true" class="w-full bg-emerald-600/20 border border-emerald-500/30 hover:bg-emerald-600/30 text-emerald-300 font-medium py-2 rounded-lg text-sm flex items-center justify-center gap-2 mb-4 transition">
                <span class="text-lg">+</span> Add Server
            </button>

            <!-- Server Instance List -->
            <div class="flex-1 overflow-y-auto space-y-1">
                <p class="text-[10px] uppercase font-bold text-slate-500 px-2 mb-2">Server Instances</p>
                <button v-for="inst in instances" :key="inst.id" @click="selectInstance(inst)"
                        class="w-full text-left px-3 py-2 rounded-lg text-sm flex items-center justify-between transition"
                        :class="selectedInstance?.id === inst.id && activeTab !== 'stats' ? 'bg-slate-800 text-emerald-400 font-medium' : 'text-slate-400 hover:bg-slate-800/50 hover:text-slate-200'">
                    <span class="truncate">{{ inst.name }}</span>
                    <span class="w-2 h-2 rounded-full" :class="inst.running ? 'bg-emerald-400' : 'bg-slate-600'"></span>
                </button>
            </div>
        </aside>

        <!-- MAIN CONTENT AREA -->
        <main class="flex-1 flex flex-col bg-slate-950 overflow-hidden">
            <!-- Top Navbar -->
            <header class="h-16 border-b border-slate-800 px-6 flex items-center justify-between">
                <nav class="flex gap-2">
                    <button v-for="t in ['mods', 'console', 'players', 'settings']" :key="t" @click="activeTab = t"
                            class="px-4 py-2 text-sm rounded-lg capitalize transition"
                            :class="activeTab === t ? 'bg-slate-800 text-white font-medium' : 'text-slate-400 hover:text-slate-200'">
                        {{ t }}
                    </button>
                </nav>

                <!-- User Profile Avatar Button -->
                <button @click="showProfileModal = true" class="flex items-center gap-3 hover:bg-slate-900 p-1.5 rounded-lg transition">
                    <span class="text-xs font-semibold text-slate-300">{{ currentUser.username }}</span>
                    <div class="w-8 h-8 rounded-full bg-emerald-600 border border-emerald-400 flex items-center justify-center text-xs font-bold text-slate-950 uppercase shadow">
                        {{ currentUser.username ? currentUser.username[0] : 'A' }}
                    </div>
                </button>
            </header>

            <!-- MAIN TAB PANELS -->
            <div class="flex-1 p-6 overflow-y-auto">

                <!-- REAL-TIME STATS VIEW -->
                <div v-if="activeTab === 'stats'" class="space-y-6">
                    <h2 class="text-xl font-bold">System Performance & Real-Time Monitoring</h2>
                    <div class="grid grid-cols-3 gap-6">
                        <div class="bg-slate-900 border border-slate-800 rounded-xl p-4">
                            <p class="text-xs text-slate-400 mb-1">CPU Load</p>
                            <p class="text-3xl font-bold text-emerald-400">{{ systemStats.current?.systemCpuLoad || 0 }}%</p>
                            <div class="w-full bg-slate-800 h-2 rounded-full mt-3 overflow-hidden">
                                <div class="bg-emerald-500 h-full transition-all" :style="{ width: (systemStats.current?.systemCpuLoad || 0) + '%' }"></div>
                            </div>
                        </div>
                        <div class="bg-slate-900 border border-slate-800 rounded-xl p-4">
                            <p class="text-xs text-slate-400 mb-1">RAM Usage</p>
                            <p class="text-3xl font-bold text-emerald-400">{{ formatBytes(systemStats.current?.usedMemoryBytes || 0) }}</p>
                            <p class="text-xs text-slate-500 mt-1">Allocated: {{ formatBytes(systemStats.current?.maxMemoryBytes || 0) }}</p>
                        </div>
                        <div class="bg-slate-900 border border-slate-800 rounded-xl p-4">
                            <p class="text-xs text-slate-400 mb-1">Disk Free</p>
                            <p class="text-3xl font-bold text-emerald-400">{{ formatBytes(systemStats.current?.freeDiskBytes || 0) }}</p>
                            <p class="text-xs text-slate-500 mt-1">Total: {{ formatBytes(systemStats.current?.totalDiskBytes || 0) }}</p>
                        </div>
                    </div>
                </div>

                <!-- RICH MODS VIEW -->
                <div v-if="activeTab === 'mods'" class="grid grid-cols-2 gap-6 h-full">
                    <!-- Panel 1: Search & Download -->
                    <div class="bg-slate-900 border border-slate-800 rounded-xl p-4 flex flex-col">
                        <h3 class="font-bold text-sm mb-3">Find & Install Mods</h3>
                        <div class="flex gap-2 mb-4">
                            <input v-model="searchQuery" @keyup.enter="searchMods" placeholder="Search Modrinth..." class="flex-1 bg-slate-800 border border-slate-700 rounded-lg px-3 py-1.5 text-sm">
                            <button @click="searchMods" class="bg-emerald-600 hover:bg-emerald-500 px-4 py-1.5 rounded-lg text-sm font-medium">Search</button>
                        </div>
                        <div class="flex-1 overflow-y-auto space-y-3 pr-1">
                            <div v-for="hit in searchResults" :key="hit.projectId" class="bg-slate-800/60 border border-slate-700/40 p-3 rounded-lg flex gap-3">
                                <img :src="hit.iconUrl || 'data:image/svg+xml;utf8,<svg xmlns=\'http://www.w3.org/2000/svg\' width=\'40\' height=\'40\' viewBox=\'0 0 24 24\' fill=\'%2310b981\'><path d=\'M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5\'/></svg>'" class="w-10 h-10 rounded object-cover shrink-0">
                                <div class="flex-1 min-w-0">
                                    <div class="flex items-center justify-between">
                                        <p class="font-semibold text-sm truncate">{{ hit.title }}</p>
                                        <button @click="installMod(hit)" class="bg-emerald-600/20 text-emerald-300 border border-emerald-500/30 hover:bg-emerald-600/30 text-xs px-3 py-1 rounded-md font-medium shrink-0">Install</button>
                                    </div>
                                    <p class="text-xs text-slate-400 line-clamp-2 mt-1">{{ hit.description }}</p>
                                </div>
                            </div>
                        </div>
                    </div>

                    <!-- Panel 2: Installed Mods with Compatibility Badges -->
                    <div class="bg-slate-900 border border-slate-800 rounded-xl p-4 flex flex-col">
                        <h3 class="font-bold text-sm mb-3">Installed Mods ({{ installedMods.length }})</h3>
                        <div class="flex-1 overflow-y-auto space-y-3 pr-1">
                            <div v-for="m in installedMods" :key="m.filename" class="bg-slate-800/60 border border-slate-700/40 p-3 rounded-lg flex gap-3 items-center">
                                <img :src="m.iconUrl || 'data:image/svg+xml;utf8,<svg xmlns=\'http://www.w3.org/2000/svg\' width=\'40\' height=\'40\' viewBox=\'0 0 24 24\' fill=\'%2310b981\'><path d=\'M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5\'/></svg>'" class="w-10 h-10 rounded object-cover shrink-0">
                                <div class="flex-1 min-w-0">
                                    <div class="flex items-center gap-2">
                                        <p class="font-semibold text-sm truncate">{{ m.title || m.filename }}</p>
                                        <span v-if="!m.compatible" class="bg-red-500/20 text-red-400 border border-red-500/30 text-[10px] px-2 py-0.5 rounded uppercase font-semibold">Incompatible</span>
                                        <span v-else class="bg-emerald-500/20 text-emerald-400 border border-emerald-500/30 text-[10px] px-2 py-0.5 rounded uppercase font-semibold">OK</span>
                                    </div>
                                    <p class="text-xs text-slate-400 font-mono truncate">{{ m.filename }}</p>
                                    <p v-if="m.warningMessage" class="text-xs text-red-400 mt-0.5">{{ m.warningMessage }}</p>
                                </div>
                                <button @click="deleteMod(m.filename)" class="text-red-400 hover:text-red-300 text-xs px-2 py-1 shrink-0">Delete</button>
                            </div>
                        </div>
                    </div>
                </div>

                <!-- CONSOLE VIEW -->
                <div v-if="activeTab === 'console'" class="bg-slate-900 border border-slate-800 rounded-xl p-4 flex flex-col h-full">
                    <h3 class="font-bold text-sm mb-3">Server Console</h3>
                    <div ref="consoleBox" class="flex-1 overflow-y-auto bg-slate-950 border border-slate-800 rounded-lg p-3 font-mono text-xs space-y-0.5 min-h-0">
                        <div v-for="(line, i) in consoleLines" :key="i" :class="consoleColor(line)">{{ line }}</div>
                    </div>
                    <form @submit.prevent="sendCommand" class="flex gap-2 mt-3">
                        <input v-model="command" placeholder="Type a server command..." class="flex-1 bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm">
                        <button class="bg-emerald-600 hover:bg-emerald-500 px-4 py-2 rounded-lg text-sm font-medium">Send</button>
                    </form>
                </div>

                <!-- PLAYER MANAGEMENT VIEW -->
                <div v-if="activeTab === 'players'" class="space-y-6">
                    <div class="flex items-center justify-between bg-slate-900 border border-slate-800 p-4 rounded-xl">
                        <div>
                            <h3 class="font-bold text-sm">Whitelist Status</h3>
                            <p class="text-xs text-slate-400">Control server access permissions</p>
                        </div>
                        <button @click="toggleWhitelist" class="px-4 py-2 rounded-lg text-xs font-semibold" :class="whitelistEnabled ? 'bg-emerald-600 text-white' : 'bg-slate-800 text-slate-400'">
                            Whitelist: {{ whitelistEnabled ? 'ENABLED' : 'DISABLED' }}
                        </button>
                    </div>

                    <div class="grid grid-cols-2 gap-6">
                        <!-- Whitelisted Players -->
                        <div class="bg-slate-900 border border-slate-800 rounded-xl p-4">
                            <h4 class="font-bold text-sm mb-3">Whitelisted Players</h4>
                            <form @submit.prevent="addWhitelist" class="flex gap-2 mb-4">
                                <input v-model="playerForms.whitelist" placeholder="Username..." class="flex-1 bg-slate-800 border border-slate-700 rounded px-3 py-1 text-sm">
                                <button class="bg-emerald-600 hover:bg-emerald-500 px-3 py-1 rounded text-xs font-semibold">Add</button>
                            </form>
                            <div class="space-y-1 max-h-48 overflow-y-auto">
                                <div v-for="p in whitelistPlayers" :key="p.name" class="flex items-center justify-between p-2 bg-slate-800/50 rounded text-sm">
                                    <span>{{ p.name }}</span>
                                    <button @click="removeWhitelist(p.name)" class="text-red-400 text-xs">Remove</button>
                                </div>
                            </div>
                        </div>

                        <!-- Operators -->
                        <div class="bg-slate-900 border border-slate-800 rounded-xl p-4">
                            <h4 class="font-bold text-sm mb-3">Operators (OPs)</h4>
                            <form @submit.prevent="addOp" class="flex gap-2 mb-4">
                                <input v-model="playerForms.op" placeholder="Username..." class="flex-1 bg-slate-800 border border-slate-700 rounded px-3 py-1 text-sm">
                                <button class="bg-emerald-600 hover:bg-emerald-500 px-3 py-1 rounded text-xs font-semibold">Op</button>
                            </form>
                            <div class="space-y-1 max-h-48 overflow-y-auto">
                                <div v-for="p in opPlayers" :key="p.name" class="flex items-center justify-between p-2 bg-slate-800/50 rounded text-sm">
                                    <span>{{ p.name }}</span>
                                    <button @click="removeOp(p.name)" class="text-red-400 text-xs">Deop</button>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>

                <!-- SETTINGS VIEW (With MC / Loader Version Switching) -->
                <div v-if="activeTab === 'settings'" class="bg-slate-900 border border-slate-800 rounded-xl p-4">
                    <h3 class="font-bold text-sm mb-3">Instance Settings & Version Management</h3>
                    <template v-if="selectedInstance">
                        <form @submit.prevent="saveInstanceSettings" class="max-w-md space-y-4">
                            <div>
                                <label class="block text-xs text-slate-400 mb-1">Server Name</label>
                                <input v-model="settingsForm.name" class="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm">
                            </div>
                            <div>
                                <label class="block text-xs text-slate-400 mb-1">Minecraft Version</label>
                                <input v-model="settingsForm.mcVersion" class="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm">
                            </div>
                            <div>
                                <label class="block text-xs text-slate-400 mb-1">Mod Loader Version</label>
                                <input v-model="settingsForm.loaderVersion" class="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm">
                            </div>
                            <p class="text-xs text-yellow-400/90 bg-yellow-500/10 border border-yellow-500/20 p-2.5 rounded-lg">
                                <strong>Mod Loader Type ({{ selectedInstance.modLoader.type }}) is locked.</strong> Changing Minecraft or Loader version will automatically check and update mod compatibility.
                            </p>
                            <button type="submit" class="bg-emerald-600 hover:bg-emerald-500 px-4 py-2 rounded-lg text-sm font-semibold">Save Changes & Sync Mods</button>
                        </form>
                    </template>
                </div>

            </div>
        </main>
    </div>

    <!-- PROFILE MODAL -->
    <div v-if="showProfileModal" class="fixed inset-0 bg-slate-950/80 backdrop-blur-sm z-50 flex items-center justify-center">
        <div class="bg-slate-900 border border-slate-800 p-6 rounded-xl w-96 shadow-2xl">
            <h3 class="text-lg font-bold mb-4">Admin Account Settings</h3>
            <form @submit.prevent="saveProfile">
                <label class="block text-xs text-slate-400 mb-1">Username</label>
                <input v-model="profileForm.username" class="w-full bg-slate-800 border border-slate-700 rounded px-3 py-2 text-sm mb-3">
                <label class="block text-xs text-slate-400 mb-1">Current Password</label>
                <input type="password" v-model="profileForm.currentPassword" class="w-full bg-slate-800 border border-slate-700 rounded px-3 py-2 text-sm mb-3">
                <label class="block text-xs text-slate-400 mb-1">New Password (optional)</label>
                <input type="password" v-model="profileForm.newPassword" class="w-full bg-slate-800 border border-slate-700 rounded px-3 py-2 text-sm mb-6">
                <div class="flex gap-2 justify-end">
                    <button type="button" @click="showProfileModal = false" class="px-4 py-2 rounded-lg text-sm text-slate-400">Cancel</button>
                    <button type="submit" class="bg-emerald-600 hover:bg-emerald-500 px-4 py-2 rounded-lg text-sm font-semibold">Save Profile</button>
                </div>
            </form>
        </div>
    </div>

</div>

<script>
const { createApp } = Vue;

createApp({
    data() {
        return {
            authenticated: false,
            loginForm: { username: 'admin', password: '' },
            currentUser: { username: 'admin', icon: 'emerald' },
            jwtToken: '',
            instances: [],
            selectedInstance: null,
            activeTab: 'mods',
            showAddServerModal: false,
            showProfileModal: false,
            profileForm: { username: 'admin', currentPassword: '', newPassword: '' },
            systemStats: {},
            searchQuery: '',
            searchResults: [],
            installedMods: [],
            whitelistEnabled: false,
            whitelistPlayers: [],
            opPlayers: [],
            playerForms: { whitelist: '', op: '' },
            settingsForm: { name: '', mcVersion: '', loaderVersion: '' }
        };
    },
    methods: {
        async login() {
            const res = await fetch('/api/auth/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(this.loginForm)
            });
            if (res.ok) {
                const data = await res.json();
                this.jwtToken = data.token;
                this.currentUser.username = data.username;
                this.authenticated = true;
                this.loadInstances();
                this.startPolling();
            } else { alert('Invalid credentials'); }
        },
        async saveProfile() {
            try {
                await this.api('/api/auth/profile', {
                    method: 'POST',
                    body: JSON.stringify({
                        currentUsername: this.currentUser.username,
                        newUsername: this.profileForm.username,
                        currentPassword: this.profileForm.currentPassword,
                        newPassword: this.profileForm.newPassword
                    })
                });
                this.currentUser.username = this.profileForm.username;
                this.showProfileModal = false;
                alert('Profile updated successfully!');
            } catch (e) { alert('Update failed: ' + e.message); }
        },
        async api(path, opts = {}) {
            opts.headers = { ...opts.headers, 'Authorization': 'Bearer ' + this.jwtToken, 'Content-Type': 'application/json' };
            const res = await fetch(path, opts);
            if (!res.ok) throw new Error(await res.text());
            return res.json();
        },
        async loadInstances() {
            const data = await this.api('/api/instances');
            this.instances = data.instances || [];
            if (this.instances.length > 0 && !this.selectedInstance) this.selectInstance(this.instances[0]);
        },
        selectInstance(inst) {
            this.selectedInstance = inst;
            this.settingsForm = { name: inst.name, mcVersion: inst.minecraftVersion, loaderVersion: inst.modLoader.version };
            this.loadMods();
        },
        async saveInstanceSettings() {
            try {
                const res = await this.api(`/api/instances/${this.selectedInstance.id}`, {
                    method: 'PATCH',
                    body: JSON.stringify(this.settingsForm)
                });
                alert(`Instance updated! ${res.updatedCount || 0} mods auto-updated.`);
                this.loadMods();
            } catch (e) { alert('Update failed: ' + e.message); }
        },
        async loadStats() {
            try {
                this.systemStats = await this.api('/api/stats');
            } catch (e) {}
        },
        startPolling() {
            setInterval(() => {
                if (this.authenticated) {
                    this.loadInstances();
                    if (this.activeTab === 'stats') this.loadStats();
                }
            }, 5000);
        },
        formatBytes(bytes) {
            if (!bytes) return '0 B';
            const k = 1024, sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
            const i = Math.floor(Math.log(bytes) / Math.log(k));
            return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
        },
        async searchMods() {
            if (!this.selectedInstance || !this.searchQuery.trim()) return;
            const data = await this.api(`/api/instances/${this.selectedInstance.id}/mods/search?query=${encodeURIComponent(this.searchQuery)}`);
            this.searchResults = data.hits || [];
        },
        async loadMods() {
            if (!this.selectedInstance) return;
            const data = await this.api(`/api/instances/${this.selectedInstance.id}/mods`);
            this.installedMods = data.mods || [];
        }
    }
}).mount('#app');
</script>
</body>
</html>
```

---

## Phase 5: Testing & Verification Plan

1. **User Profile Testing**:
   - Verify logging in, clicking the avatar button, and changing the admin password.
   - Test logging in with the newly updated password.

2. **System Stats Verification**:
   - Open the "System Stats" tab above the server list.
   - Verify system CPU load, RAM usage, and free disk space populate and update live.

3. **Player Management**:
   - Toggle whitelist state.
   - Add/Remove whitelisted users and Ops. Ensure changes update `whitelist.json` and `ops.json`.

4. **Minecraft/Loader Version Switching & Mod Compatibility Sync**:
   - Change Minecraft version in Instance Settings (e.g., from `1.20.4` to `1.21.1`).
   - Confirm that compatible Modrinth mods auto-download new JARs.
   - Confirm that mods without compatible versions display the red **Incompatible** warning badge.

5. **Rich Mod Information**:
   - Perform a Modrinth mod search. Confirm icon, display title, description, and author display on search cards.
   - Install a mod and confirm the icon and description carry over to the installed mods list.
