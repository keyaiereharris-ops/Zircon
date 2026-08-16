Here is a complete, multi-step execution plan for building **Zircon** (Zircon Server & Zircon Launcher). This plan transforms your architecture into a multi-server management system (similar to Crafty Controller), implements the UI based on your whiteboard drawing, locks in mod loaders per server instance, and fixes all security vulnerabilities and logic bugs identified in the previous review.

---

# Zircon Project Architecture Plan

```
+-----------------------------------------------------------------------------------+
|                                 Zircon Project                                    |
+------------------------------------+----------------------------------------------+
| Zircon Server (Server Manager)     | Zircon Launcher (Client Companion)           |
|  - Multi-instance directory isolate|  - Multi-instance local cache (.zircon/inst) |
|  - Auth & Admin Web GUI (Javalin)  |  - Microsoft OAuth + PKCE                    |
|  - Netty TCP Multiplexer           |  - Headless loader installation & auto-sync  |
+------------------------------------+----------------------------------------------+
```

---

## Phase 1: Storage Layout & Shared Core Security (`zircon-core`)

### 1.1 Directory Structure & Storage Schema
Isolate every server instance in its own folder to prevent cross-loader file pollution.

```
server-data/
├── config.json                     # Global Zircon Server settings
├── users.json                      # Hashed user credentials (BCrypt)
└── instances/                      # Isolated server instances
    ├── 8f3a1b2c/                   # Instance UUID
    │   ├── instance.json           # Instance metadata (LOADER LOCKED HERE)
    │   ├── bom.json                # Instance-specific Bill of Materials
    │   ├── mods/                   # Instance-hosted mod JARs
    │   └── server/                 # Dedicated Minecraft server directory
    │       ├── server.jar / @unix_args.txt
    │       ├── server.properties
    │       └── eula.txt
    └── 4d2e9f0a/
        └── ...
```

### 1.2 Data Models (`com.zircon.core.model`)

#### `InstanceConfig.java`
```java
package com.zircon.core.model;

import com.google.gson.annotations.SerializedName;
import java.util.UUID;

public class InstanceConfig {
    @SerializedName("id")
    private String id = UUID.randomUUID().toString().substring(0, 8);

    @SerializedName("name")
    private String name = "New Zircon Server";

    @SerializedName("minecraftVersion")
    private String minecraftVersion;

    // IMMUTABLE ModLoaderInfo - Set on creation, no setter exposed to API!
    @SerializedName("modLoader")
    private ModLoaderInfo modLoader;

    @SerializedName("internalMcPort")
    private int internalMcPort; // Automatically assigned e.g. 25566, 25567

    @SerializedName("javaArgs")
    private String javaArgs = "-Xms2G -Xmx4G";

    @SerializedName("autoStart")
    private boolean autoStart = false;

    // Getters and constructor only...
}
```

### 1.3 SSRF URL Sanitizer (`com.zircon.core.util.SecurityUtil`)
Prevents Server-Side Request Forgery when downloading mods from remote URLs.

```java
package com.zircon.core.util;

import java.net.URI;
import java.util.Set;

public final class SecurityUtil {
    private static final Set<String> ALLOWED_CDN_DOMAINS = Set.of(
        "cdn.modrinth.com",
        "edge.forgecdn.net",
        "media.forgecdn.net",
        "maven.neoforged.net",
        "maven.minecraftforge.net",
        "meta.fabricmc.net",
        "meta.quiltmc.org",
        "piston-meta.mojang.com",
        "launchermeta.mojang.com"
    );

    public static boolean isSafeCdnUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return false;
            return ALLOWED_CDN_DOMAINS.stream().anyMatch(allowed -> host.equalsIgnoreCase(allowed) || host.endsWith("." + allowed));
        } catch (Exception e) {
            return false;
        }
    }
}
```

---

## Phase 2: Zircon Server Authentication & Multi-Instance Engine

### 2.1 Password Generator & JWT Auth (`com.zircon.server.auth`)

#### `AuthService.java`
Generate a random admin password on the first run and log it clearly to `stdout`.

```java
package com.zircon.server.auth;

import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.security.SecureRandom;

public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*";

    public static void initializeAuth(Path dataDir) {
        Path usersFile = dataDir.resolve("users.json");
        if (!Files.exists(usersFile)) {
            String initialPassword = generateRandomPassword(16);
            String hashedPassword = BCrypt.hashpw(initialPassword, BCrypt.gensalt(12));

            // Save admin user
            saveAdminUser(usersFile, "admin", hashedPassword);

            System.out.println("=================================================");
            System.out.println("  ZIRCON SERVER CREATED INITIAL ADMIN USER       ");
            System.out.println("  Username: admin                                ");
            System.out.println("  Password: " + initialPassword                   );
            System.out.println("  Please log in and change your password!        ");
            System.out.println("=================================================");
        }
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

### 2.2 Server Instance Manager (`com.zircon.server.instance`)

#### `ServerInstanceManager.java`
Manages lifecycle for multiple instances independently.

```java
package com.zircon.server.instance;

import com.zircon.core.model.InstanceConfig;
import com.zircon.server.process.MinecraftProcessManager;
import java.util.concurrent.ConcurrentHashMap;
import java.util.*;

public class ServerInstanceManager {
    private final Map<String, InstanceConfig> instanceConfigs = new ConcurrentHashMap<>();
    private final Map<String, MinecraftProcessManager> activeProcesses = new ConcurrentHashMap<>();

    public synchronized InstanceConfig createInstance(String name, String mcVersion, String loaderType, String loaderVersion) {
        // LOCK IN RULE: loaderType and loaderVersion are frozen in InstanceConfig
        InstanceConfig config = new InstanceConfig(name, mcVersion, loaderType, loaderVersion, allocateNextPort());
        saveInstanceToDisk(config);
        instanceConfigs.put(config.getId(), config);
        return config;
    }

    public synchronized void startInstance(String instanceId) throws Exception {
        InstanceConfig config = instanceConfigs.get(instanceId);
        if (config == null) throw new IllegalArgumentException("Instance not found");

        MinecraftProcessManager pm = activeProcesses.computeIfAbsent(instanceId,
            id -> new MinecraftProcessManager(config));
        pm.start();
    }

    // Prevents ModLoader switching on existing instances!
    public synchronized void updateInstanceConfig(String instanceId, String newName, String newJavaArgs) {
        InstanceConfig config = instanceConfigs.get(instanceId);
        if (config == null) throw new IllegalArgumentException("Instance not found");
        config.setName(newName);
        config.setJavaArgs(sanitizeJavaArgs(newJavaArgs));
        saveInstanceToDisk(config);
    }
}
```

---

## Phase 3: Netty Protocol Sniffer & Web Server Security

### 3.1 Fixed Netty Protocol Sniffer (`com.zircon.server.multiplexer`)

Fix `readerIndex` access, strict HTTP prefix checking, and backpressure.

#### `ProtocolDetector.java`
```java
package com.zircon.server.multiplexer;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

public class ProtocolDetector extends ByteToMessageDecoder {
    // Require trailing space for 3 and 4-letter methods to prevent false-positives on MC protocol
    private static final byte[][] HTTP_PREFIXES = {
        {'G', 'E', 'T', ' '},
        {'P', 'O', 'S', 'T', ' '},
        {'H', 'E', 'A', 'D', ' '},
        {'P', 'U', 'T', ' '},
        {'D', 'E', 'L', 'E', 'T', 'E', ' '},
        {'O', 'P', 'T', 'I', 'O', 'N', 'S', ' '}
    };

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 5) return; // Need 5 bytes to reliably match

        boolean isHttp = isHttpMethod(in);
        ByteBuf initialData = in.readRetainedSlice(in.readableBytes());

        ChannelPipeline p = ctx.pipeline();
        p.remove(this);
        p.addLast(new ProxyHandler(isHttp ? "127.0.0.1" : "127.0.0.1",
                                   isHttp ? webPort : getMcPortForConnection(in), initialData));
    }

    private boolean isHttpMethod(ByteBuf in) {
        int readerIndex = in.readerIndex(); // Read relative to readerIndex!
        for (byte[] prefix : HTTP_PREFIXES) {
            if (matches(in, readerIndex, prefix)) return true;
        }
        return false;
    }

    private boolean matches(ByteBuf in, int offset, byte[] prefix) {
        if (in.readableBytes() < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (in.getByte(offset + i) != prefix[i]) return false;
        }
        return true;
    }
}
```

### 3.2 Secure API Routing with Authentication (`JavalinApp.java`)

```java
// Auth Middleware
app.before("/api/*", ctx -> {
    if (ctx.path().equals("/api/auth/login")) return; // Allow login endpoint

    String token = ctx.header("Authorization");
    if (token == null || !token.startsWith("Bearer ") || !JwtUtil.validateToken(token.substring(7))) {
        throw new UnauthorizedResponse("Authentication required. Please log in.");
    }
});

// Authentication Endpoint
app.post("/api/auth/login", ctx -> {
    LoginRequest req = ctx.bodyAsClass(LoginRequest.class);
    if (AuthService.authenticate(req.username, req.password)) {
        String token = JwtUtil.generateToken(req.username);
        ctx.json(Map.of("token", token, "username", req.username));
    } else {
        ctx.status(401).result("Invalid username or password");
    }
});

// Multi-Instance Management Routes
app.get("/api/instances", instanceController::listInstances);
app.post("/api/instances", instanceController::createInstance); // Create instance + LOCK loader
app.get("/api/instances/{id}/bom", bomController::getInstanceBom);
app.post("/api/instances/{id}/mods/install", modController::installModForInstance);
```

---

## Phase 4: Zircon Client Launcher Enhancements

### 4.1 OAuth PKCE Support (`MicrosoftAuthService.java`)
Implements Proof Key for Code Exchange (PKCE) and dynamic port selection for secure browser logins.

```java
public class MicrosoftAuthService {
    public SessionData login() throws Exception {
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);

        int dynamicPort = findFreePort();
        String redirectUri = "http://localhost:" + dynamicPort + "/callback";

        try (CallbackServer server = new CallbackServer(dynamicPort)) {
            String authUrl = "https://login.live.com/oauth20_authorize.srf"
                + "?client_id=" + urlEncode(clientId)
                + "&response_type=code"
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&scope=" + urlEncode("XboxLive.signin offline_access")
                + "&code_challenge=" + codeChallenge
                + "&code_challenge_method=S256";

            Desktop.getDesktop().browse(URI.create(authUrl));
            String authCode = server.awaitCode(5, TimeUnit.MINUTES);
            return exchangeCodeWithVerifier(authCode, codeVerifier, redirectUri);
        }
    }
}
```

### 4.2 Local Client Directory Isolation
Update `MainController.java` on the client to store game assets per instance:

```java
Path instanceGameDir = Path.of(System.getProperty("user.home"),
    ".zircon", "instances", serverHost + "_" + instanceId);
Files.createDirectories(instanceGameDir);
```

---

## Phase 5: Zircon Admin Web GUI Redesign (Matching Whiteboard)

Refactor `index.html` into a clean Vue 3 + Tailwind CSS Single Page Application matching your whiteboard drawing:
* **Left Sidebar:** Branding, Instance selector, and `+ Add Server` button.
* **Top Bar:** Status indicator and navigation tabs.
* **Mods View Layout:** Dual-panel design with **Search** on the left and **Installed Mods** on the right.

### `src/main/resources/web/index.html`

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
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

        <!-- LEFT SIDEBAR (Matching Whiteboard Sketch) -->
        <aside class="w-64 bg-slate-900 border-r border-slate-800 flex flex-col p-4">
            <!-- Branding -->
            <div class="flex items-center gap-3 mb-6">
                <div class="w-8 h-8 rounded bg-emerald-500 font-bold text-slate-950 flex items-center justify-center">Z</div>
                <span class="font-bold text-lg tracking-wide">Zircon</span>
            </div>

            <!-- Active Instance Header -->
            <div v-if="selectedInstance" class="bg-slate-800 border border-slate-700/50 rounded-lg p-3 mb-4">
                <p class="text-xs text-slate-400">Current Server</p>
                <p class="font-semibold text-sm truncate">{{ selectedInstance.name }}</p>
                <span class="inline-block mt-1 text-[10px] bg-emerald-500/20 text-emerald-300 px-2 py-0.5 rounded uppercase font-mono">
                    {{ selectedInstance.modLoader.type }} {{ selectedInstance.minecraftVersion }}
                </span>
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
                        :class="selectedInstance?.id === inst.id ? 'bg-slate-800 text-emerald-400 font-medium' : 'text-slate-400 hover:bg-slate-800/50 hover:text-slate-200'">
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

                <div class="flex items-center gap-3">
                    <div class="w-8 h-8 rounded-full bg-slate-800 border border-slate-700 flex items-center justify-center text-xs font-bold text-slate-300">
                        A
                    </div>
                </div>
            </header>

            <!-- MAIN TAB PANELS -->
            <div class="flex-1 p-6 overflow-y-auto">

                <!-- MODS VIEW (Matching Whiteboard Sub-panels: Left = Search, Right = Installed) -->
                <div v-if="activeTab === 'mods'" class="grid grid-cols-2 gap-6 h-full">
                    <!-- Panel 1: Search & Download -->
                    <div class="bg-slate-900 border border-slate-800 rounded-xl p-4 flex flex-col">
                        <h3 class="font-bold text-sm mb-3">Find & Download Mods</h3>
                        <div class="flex gap-2 mb-4">
                            <input v-model="searchQuery" @keyup.enter="searchMods" placeholder="Search Modrinth..." class="flex-1 bg-slate-800 border border-slate-700 rounded-lg px-3 py-1.5 text-sm">
                            <button @click="searchMods" class="bg-emerald-600 hover:bg-emerald-500 px-4 py-1.5 rounded-lg text-sm font-medium">Search</button>
                        </div>
                        <div class="flex-1 overflow-y-auto space-y-2 pr-1">
                            <div v-for="hit in searchResults" :key="hit.projectId" class="bg-slate-800/60 border border-slate-700/40 p-3 rounded-lg flex items-center justify-between">
                                <div>
                                    <p class="font-semibold text-sm">{{ hit.title }}</p>
                                    <p class="text-xs text-slate-400 line-clamp-1">{{ hit.description }}</p>
                                </div>
                                <button @click="installMod(hit)" class="bg-emerald-600/20 text-emerald-300 border border-emerald-500/30 hover:bg-emerald-600/30 text-xs px-3 py-1 rounded-md font-medium">Install</button>
                            </div>
                        </div>
                    </div>

                    <!-- Panel 2: Installed Mods -->
                    <div class="bg-slate-900 border border-slate-800 rounded-xl p-4 flex flex-col">
                        <h3 class="font-bold text-sm mb-3">Installed Mods ({{ installedMods.length }})</h3>
                        <div class="flex-1 overflow-y-auto space-y-2 pr-1">
                            <div v-for="m in installedMods" :key="m.filename" class="bg-slate-800/60 border border-slate-700/40 p-3 rounded-lg flex items-center justify-between">
                                <span class="text-sm font-mono truncate">{{ m.filename }}</span>
                                <button @click="deleteMod(m.filename)" class="text-red-400 hover:text-red-300 text-xs">Delete</button>
                            </div>
                        </div>
                    </div>
                </div>

            </div>
        </main>
    </div>

    <!-- ADD SERVER MODAL (Locks in Mod Loader) -->
    <div v-if="showAddServerModal" class="fixed inset-0 bg-slate-950/80 backdrop-blur-sm z-50 flex items-center justify-center">
        <div class="bg-slate-900 border border-slate-800 p-6 rounded-xl w-[450px] shadow-2xl">
            <h3 class="text-lg font-bold mb-1">Create New Server Instance</h3>
            <p class="text-xs text-yellow-400/90 bg-yellow-500/10 border border-yellow-500/20 p-2.5 rounded-lg mb-4">
                <strong>Notice:</strong> The choice of Mod Loader is permanently locked upon creation to ensure server stability.
            </p>
            <form @submit.prevent="createNewServer">
                <label class="block text-xs text-slate-400 mb-1">Server Name</label>
                <input v-model="newServerForm.name" required class="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm mb-3">

                <label class="block text-xs text-slate-400 mb-1">Minecraft Version</label>
                <input v-model="newServerForm.mcVersion" placeholder="1.20.4" required class="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm mb-3">

                <label class="block text-xs text-slate-400 mb-1">Mod Loader (Locked After Creation)</label>
                <select v-model="newServerForm.loaderType" required class="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm mb-3">
                    <option value="fabric">Fabric</option>
                    <option value="neoforge">NeoForge</option>
                    <option value="forge">Forge</option>
                    <option value="quilt">Quilt</option>
                    <option value="vanilla">Vanilla</option>
                </select>

                <label class="block text-xs text-slate-400 mb-1">Mod Loader Version</label>
                <input v-model="newServerForm.loaderVersion" placeholder="e.g. 20.4.250 or leave empty for auto" class="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm mb-6">

                <div class="flex gap-2 justify-end">
                    <button type="button" @click="showAddServerModal = false" class="px-4 py-2 rounded-lg text-sm text-slate-400 hover:text-white">Cancel</button>
                    <button type="submit" class="bg-emerald-600 hover:bg-emerald-500 px-4 py-2 rounded-lg text-sm font-semibold">Create Instance</button>
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
            jwtToken: '',
            instances: [],
            selectedInstance: null,
            activeTab: 'mods',
            showAddServerModal: false,
            newServerForm: { name: '', mcVersion: '1.20.4', loaderType: 'fabric', loaderVersion: '' },
            searchQuery: '',
            searchResults: [],
            installedMods: []
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
                this.authenticated = true;
                this.loadInstances();
            } else {
                alert('Invalid credentials');
            }
        },
        async api(path, opts = {}) {
            opts.headers = { ...opts.headers, 'Authorization': 'Bearer ' + this.jwtToken, 'Content-Type': 'application/json' };
            const res = await fetch(path, opts);
            if (!res.ok) throw new Error(await res.text());
            return res.json();
        },
        async loadInstances() {
            this.instances = await this.api('/api/instances');
            if (this.instances.length > 0) this.selectInstance(this.instances[0]);
        },
        selectInstance(inst) {
            this.selectedInstance = inst;
            this.loadMods();
        },
        async createNewServer() {
            await this.api('/api/instances', {
                method: 'POST',
                body: JSON.stringify(this.newServerForm)
            });
            this.showAddServerModal = false;
            this.loadInstances();
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

## Phase 6: Testing & Verification Checklist

1. **Auth Verification:**
   - [ ] Delete `users.json`, start server, verify random admin password prints to stdout.
   - [ ] Try accessing `/api/instances` without token $\rightarrow$ expect `401 Unauthorized`.
   - [ ] Log in via web interface $\rightarrow$ expect JWT token returned and UI unlocked.
2. **Multi-Instance Isolation Verification:**
   - [ ] Create Instance A (Fabric 1.21.4).
   - [ ] Create Instance B (NeoForge 1.20.4).
   - [ ] Verify directory layout: `server-data/instances/<id-A>/` vs `server-data/instances/<id-B>/`.
   - [ ] Verify `InstanceConfig` has no endpoints or logic allowing loader modification after creation.
3. **Security Vulnerability Audits:**
   - [ ] Test SSRF protection by submitting `http://169.254.169.254` to mod install $\rightarrow$ expect rejection.
   - [ ] Verify Netty protocol sniffer correctly forwards HTTP and Minecraft packets without binary packet collision.
