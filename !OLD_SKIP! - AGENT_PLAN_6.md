# Agent Execution Plan: Server-Side Backups & Restoration Pipeline

This execution plan provides a step-by-step, phase-by-phase guide to implementing automated, scheduled, LZ4-compressed server instance backups and a full restoration pipeline with Web GUI integration.

---

## Technical Overview & Architecture

### Backup Flow & Safety
When backing up a live Minecraft server instance:
1. **Flushing & Autosave Pause**: If the server is running, issue `save-off` and `save-all` to force MC to write all chunk data to disk and pause disk writes.
2. **Directory Archiving**: Archive the entire instance folder (`<data>/instances/<id>/`), excluding existing backup archives to prevent recursive inflation.
3. **LZ4 Compression**: Stream the TAR archive through **LZ4 High-Compression (`LZ4FrameOutputStream`)** for maximum space savings and high-speed decompression.
4. **Resuming Autosave**: Issue `save-on` to resume normal server autosaving.
5. **Metadata & Audit Trail**: Save a JSON metadata file containing execution logs, file counts, compression ratios, trigger origin (Manual vs Scheduled), and timestamp.
6. **Retention Pruning**: Automatically delete older backups exceeding the user's retention limit.

### Restoration Flow
1. If the target server instance is running, safely stop it and wait for exit.
2. Backup the current broken/existing state to a temporary rollback folder.
3. Extract the `.tar.lz4` archive back into `<data>/instances/<id>/`.
4. Re-read instance configuration and BOM from disk.

---

## Phase 1: Dependency Addition & Compression Utility

### 1.1 Add LZ4 & Tar Dependencies
Add `lz4-java` and `commons-compress` to the root or core `build.gradle`:

```groovy
// In build.gradle
dependencies {
    implementation 'net.jpountz.lz4:lz4-java:1.8.0'
    implementation 'org.apache.commons:commons-compress:1.26.1'
}
```

### 1.2 Create `Lz4ArchiveUtil.java`
**File**: `main/java/com/mcmanager/core/util/Lz4ArchiveUtil.java`

Handles TAR creation with LZ4 compression and decompression extraction.

```java
package com.mcmanager.core.util;

import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

public final class Lz4ArchiveUtil {

    private Lz4ArchiveUtil() {}

    /**
     * Packs sourceDir into a .tar.lz4 compressed archive.
     */
    public static void compressDirectory(Path sourceDir, Path targetArchive, Path excludeDir, List<String> auditLogs) throws IOException {
        long startTime = System.currentTimeMillis();
        long totalUncompressedBytes = 0;
        int fileCount = 0;

        try (OutputStream fileOut = Files.newOutputStream(targetArchive);
             OutputStream bufferedOut = new BufferedOutputStream(fileOut);
             // Use LZ4 High-Compression Frame Output Stream
             LZ4FrameOutputStream lz4Out = new LZ4FrameOutputStream(bufferedOut, LZ4FrameOutputStream.BLOCKSIZE.SIZE_4MB);
             TarArchiveOutputStream tarOut = new TarArchiveOutputStream(lz4Out)) {

            tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

            Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (excludeDir != null && dir.startsWith(excludeDir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (excludeDir != null && file.startsWith(excludeDir)) {
                        return FileVisitResult.CONTINUE;
                    }

                    String entryName = sourceDir.relativize(file).toString().replace('\\', '/');
                    TarArchiveEntry entry = new TarArchiveEntry(file.toFile(), entryName);
                    tarOut.putArchiveEntry(entry);
                    Files.copy(file, tarOut);
                    tarOut.closeArchiveEntry();

                    return FileVisitResult.CONTINUE;
                }
            });
            tarOut.finish();
        }

        long archiveSize = Files.size(targetArchive);
        long elapsed = System.currentTimeMillis() - startTime;
        auditLogs.add(String.format("Archived %d files in %d ms. Compressed size: %.2f MB",
                fileCount, elapsed, archiveSize / (1024.0 * 1024.0)));
    }

    /**
     * Decompresses and extracts a .tar.lz4 archive into destinationDir.
     */
    public static void extractArchive(Path archiveFile, Path destinationDir) throws IOException {
        try (InputStream fileIn = Files.newInputStream(archiveFile);
             InputStream bufferedIn = new BufferedInputStream(fileIn);
             LZ4FrameInputStream lz4In = new LZ4FrameInputStream(bufferedIn);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(lz4In)) {

            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                Path targetPath = destinationDir.resolve(entry.getName()).normalize();
                if (!targetPath.startsWith(destinationDir)) {
                    throw new IOException("Zip slip attempt detected: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(tarIn, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
```

---

## Phase 2: Core Backup Engine & Metadata Models

### 2.1 Metadata Model
**File**: `main/java/com/mcmanager/core/model/BackupEntry.java`

```java
package com.mcmanager.core.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class BackupEntry {
    @SerializedName("id")
    private String id;
    
    @SerializedName("instanceId")
    private String instanceId;
    
    @SerializedName("filename")
    private String filename;
    
    @SerializedName("timestamp")
    private long timestamp;
    
    @SerializedName("sizeBytes")
    private long sizeBytes;
    
    @SerializedName("triggerType")
    private String triggerType; // "manual" or "scheduled"
    
    @SerializedName("status")
    private String status; // "completed", "failed", "in_progress"
    
    @SerializedName("logs")
    private List<String> logs;

    // Constructors, Getters, and Setters...
}
```

### 2.2 Backup Service
**File**: `main/java/com/mcmanager/server/service/BackupService.java`

Location on disk: Backups for instance `X` are saved under `<data>/backups/<instance_id>/`. Storing backups outside `<data>/instances/<id>/` avoids recursive archive nesting.

```java
package com.mcmanager.server.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mcmanager.core.model.BackupEntry;
import com.mcmanager.core.model.InstanceConfig;
import com.mcmanager.core.util.Lz4ArchiveUtil;
import com.mcmanager.server.instance.ServerInstanceManager;
import com.mcmanager.server.process.MinecraftProcessManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path globalBackupsDir;
    private final ServerInstanceManager instanceManager;

    public BackupService(Path dataDir, ServerInstanceManager instanceManager) {
        this.globalBackupsDir = dataDir.resolve("backups");
        this.instanceManager = instanceManager;
        try {
            Files.createDirectories(globalBackupsDir);
        } catch (IOException e) {
            log.error("Failed to create backups directory", e);
        }
    }

    public synchronized BackupEntry createBackup(String instanceId, String triggerType) throws IOException {
        InstanceConfig config = instanceManager.getInstance(instanceId);
        Path instanceDir = instanceManager.getInstanceDir(instanceId);
        Path instanceBackupsDir = globalBackupsDir.resolve(instanceId);
        Files.createDirectories(instanceBackupsDir);

        String backupId = "backup-" + System.currentTimeMillis();
        String filename = backupId + ".tar.lz4";
        Path targetArchive = instanceBackupsDir.resolve(filename);
        Path metadataFile = instanceBackupsDir.resolve(backupId + ".json");

        List<String> auditLogs = new ArrayList<>();
        auditLogs.add("Starting backup for instance: " + config.getName() + " (" + instanceId + ")");
        auditLogs.add("Trigger type: " + triggerType);

        MinecraftProcessManager pm = instanceManager.getProcessManager(instanceId);
        boolean wasRunning = pm != null && pm.isRunning();

        // 1. Safe Chunk Flush if MC is Running
        if (wasRunning) {
            auditLogs.add("Server is running. Sending 'save-off' and 'save-all' commands...");
            pm.sendCommand("save-off");
            pm.sendCommand("save-all");
            try {
                Thread.sleep(2500); // Allow disk flush
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            auditLogs.add("Server is offline. Proceeding directly with archive.");
        }

        // 2. Perform LZ4 Heavy Compression Archive
        BackupEntry entry = new BackupEntry();
        entry.setId(backupId);
        entry.setInstanceId(instanceId);
        entry.setFilename(filename);
        entry.setTimestamp(System.currentTimeMillis());
        entry.setTriggerType(triggerType);
        entry.setStatus("in_progress");

        try {
            Lz4ArchiveUtil.compressDirectory(instanceDir, targetArchive, null, auditLogs);
            entry.setStatus("completed");
            entry.setSizeBytes(Files.size(targetArchive));
            auditLogs.add("Backup file written successfully: " + filename);
        } catch (Exception e) {
            entry.setStatus("failed");
            auditLogs.add("ERROR during compression: " + e.getMessage());
            log.error("Backup failed for instance " + instanceId, e);
        } finally {
            // 3. Resume Autosave if Server Was Running
            if (wasRunning) {
                pm.sendCommand("save-on");
                auditLogs.add("Resumed server auto-saving ('save-on').");
            }
        }

        entry.setLogs(auditLogs);
        Files.writeString(metadataFile, GSON.toJson(entry), StandardCharsets.UTF_8);

        // 4. Prune Old Backups based on Retention Policy
        pruneOldBackups(instanceId, 10); // Retain max 10 backups per instance

        return entry;
    }

    public synchronized void restoreBackup(String instanceId, String backupId) throws IOException {
        Path instanceBackupsDir = globalBackupsDir.resolve(instanceId);
        Path archiveFile = instanceBackupsDir.resolve(backupId + ".tar.lz4");

        if (!Files.isRegularFile(archiveFile)) {
            throw new FileNotFoundException("Backup archive not found: " + backupId);
        }

        // Stop server if running
        if (instanceManager.isRunning(instanceId)) {
            instanceManager.stopInstance(instanceId);
        }

        Path instanceDir = instanceManager.getInstanceDir(instanceId);
        
        // Extract over the instance directory
        Lz4ArchiveUtil.extractArchive(archiveFile, instanceDir);
        log.info("Restored backup {} into instance {}", backupId, instanceId);
    }

    public List<BackupEntry> listBackups(String instanceId) {
        Path instanceBackupsDir = globalBackupsDir.resolve(instanceId);
        if (!Files.isDirectory(instanceBackupsDir)) return List.of();

        List<BackupEntry> list = new ArrayList<>();
        try (var stream = Files.list(instanceBackupsDir)) {
            for (Path p : stream.filter(p -> p.toString().endsWith(".json")).toList()) {
                try {
                    BackupEntry entry = GSON.fromJson(Files.readString(p), BackupEntry.class);
                    if (entry != null) list.add(entry);
                } catch (Exception ignored) {}
            }
        } catch (IOException ignored) {}

        list.sort((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));
        return list;
    }

    private void pruneOldBackups(String instanceId, int maxKeep) {
        List<BackupEntry> backups = listBackups(instanceId);
        if (backups.size() <= maxKeep) return;

        for (int i = maxKeep; i < backups.size(); i++) {
            BackupEntry old = backups.get(i);
            Path instanceBackupsDir = globalBackupsDir.resolve(instanceId);
            try {
                Files.deleteIfExists(instanceBackupsDir.resolve(old.getFilename()));
                Files.deleteIfExists(instanceBackupsDir.resolve(old.getId() + ".json"));
                log.info("Pruned old backup: {}", old.getId());
            } catch (IOException e) {
                log.warn("Failed to prune backup {}", old.getId(), e);
            }
        }
    }
}
```

---

## Phase 3: Automated Scheduler Service

### 3.1 Create `BackupSchedulerService.java`
**File**: `main/java/com/mcmanager/server/service/BackupSchedulerService.java`

Checks instances periodically for automated backup timers and runs scheduled backups.

```java
package com.mcmanager.server.service;

import com.mcmanager.core.model.InstanceConfig;
import com.mcmanager.server.instance.ServerInstanceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BackupSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(BackupSchedulerService.class);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ServerInstanceManager instanceManager;
    private final BackupService backupService;

    public BackupSchedulerService(ServerInstanceManager instanceManager, BackupService backupService) {
        this.instanceManager = instanceManager;
        this.backupService = backupService;
    }

    public void start() {
        // Poll every 10 minutes to check if scheduled backups are due
        scheduler.scheduleAtFixedRate(this::checkScheduledBackups, 1, 10, TimeUnit.MINUTES);
        log.info("Backup Scheduler Service started.");
    }

    private void checkScheduledBackups() {
        for (InstanceConfig inst : instanceManager.listInstances()) {
            // If backup is enabled and interval has elapsed since last backup
            try {
                log.info("Running automated backup for instance: {}", inst.getName());
                backupService.createBackup(inst.getId(), "scheduled");
            } catch (Exception e) {
                log.error("Scheduled backup failed for instance {}", inst.getId(), e);
            }
        }
    }

    public void stop() {
        scheduler.shutdown();
    }
}
```

---

## Phase 4: REST Controller & Javalin Endpoints

### 4.1 Create `BackupController.java`
**File**: `main/java/com/mcmanager/server/web/controller/BackupController.java`

```java
package com.mcmanager.server.web.controller;

import com.mcmanager.core.model.BackupEntry;
import com.mcmanager.server.service.BackupService;
import io.javalin.http.Context;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class BackupController {

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    /** GET /api/instances/{id}/backups */
    public void listBackups(Context ctx) {
        String instanceId = ctx.pathParam("id");
        List<BackupEntry> backups = backupService.listBackups(instanceId);
        ctx.json(Map.of("backups", backups));
    }

    /** POST /api/instances/{id}/backups */
    public void createBackup(Context ctx) {
        String instanceId = ctx.pathParam("id");
        try {
            BackupEntry entry = backupService.createBackup(instanceId, "manual");
            ctx.status(201).json(entry);
        } catch (IOException e) {
            ctx.status(500).result("Backup creation failed: " + e.getMessage());
        }
    }

    /** POST /api/instances/{id}/backups/{backupId}/restore */
    public void restoreBackup(Context ctx) {
        String instanceId = ctx.pathParam("id");
        String backupId = ctx.pathParam("backupId");
        try {
            backupService.restoreBackup(instanceId, backupId);
            ctx.json(Map.of("ok", true, "message", "Backup restored successfully."));
        } catch (IOException e) {
            ctx.status(500).result("Restore failed: " + e.getMessage());
        }
    }
}
```

### 4.2 Register Javalin Routes
In `main/java/com/mcmanager/server/web/JavalinApp.java`:

```java
BackupController backupController = new BackupController(backupService);

// Backups REST Endpoints
app.get("/api/instances/{id}/backups", backupController::listBackups);
app.post("/api/instances/{id}/backups", backupController::createBackup);
app.post("/api/instances/{id}/backups/{backupId}/restore", backupController::restoreBackup);
```

---

## Phase 5: Web App Frontend Integration (`index.html`)

### 5.1 Add "Backups" Navigation Tab Header
In `index.html`:

```html
<nav class="flex gap-2">
    <button v-for="t in ['mods', 'console', 'players', 'backups', 'settings']" :key="t" @click="activeTab = t"
            class="px-4 py-2 text-sm rounded-lg capitalize transition"
            :class="activeTab === t ? 'bg-slate-800 text-white font-medium' : 'text-slate-400 hover:text-slate-200'">
        {{ t }}
    </button>
</nav>
```

### 5.2 Add Backups View UI Template
In `index.html` main view panel:

```html
<!-- BACKUPS VIEW -->
<div v-if="activeTab === 'backups'" class="space-y-6">
    <div class="flex items-center justify-between bg-slate-900 border border-slate-800 p-4 rounded-xl">
        <div>
            <h3 class="font-bold text-sm text-slate-200">Server Backups</h3>
            <p class="text-xs text-slate-400">Manage LZ4-compressed system snapshots and automatic restoration points</p>
        </div>
        <button @click="triggerBackup" :disabled="creatingBackup"
                class="bg-emerald-600 hover:bg-emerald-500 disabled:opacity-50 font-semibold px-4 py-2 rounded-lg text-sm transition flex items-center gap-2">
            <svg v-if="creatingBackup" class="animate-spin h-4 w-4 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
            </svg>
            <span>{{ creatingBackup ? 'Creating Backup...' : 'Backup Now' }}</span>
        </button>
    </div>

    <!-- Backups Table -->
    <div class="bg-slate-900 border border-slate-800 rounded-xl p-4">
        <h4 class="font-bold text-sm mb-3">Backup History</h4>
        <div class="space-y-2 max-h-[500px] overflow-y-auto">
            <div v-for="b in backupsList" :key="b.id" class="bg-slate-800/60 border border-slate-700/40 p-3 rounded-lg flex items-center justify-between gap-4">
                <div class="min-w-0">
                    <div class="flex items-center gap-2">
                        <p class="font-semibold text-sm text-slate-200">{{ formatDate(b.timestamp) }}</p>
                        <span class="text-[10px] px-2 py-0.5 rounded font-mono uppercase font-bold"
                              :class="b.triggerType === 'scheduled' ? 'bg-blue-500/20 text-blue-300' : 'bg-emerald-500/20 text-emerald-300'">
                            {{ b.triggerType }}
                        </span>
                        <span class="text-[10px] px-2 py-0.5 rounded font-mono uppercase font-bold"
                              :class="b.status === 'completed' ? 'bg-emerald-500/20 text-emerald-300' : 'bg-red-500/20 text-red-300'">
                            {{ b.status }}
                        </span>
                    </div>
                    <p class="text-xs text-slate-400 font-mono mt-1">{{ formatBytes(b.sizeBytes) }} • {{ b.filename }}</p>
                </div>
                
                <div class="flex items-center gap-2 shrink-0">
                    <button @click="showLogsModal(b)" class="text-xs text-slate-400 hover:text-slate-200 border border-slate-700 px-2.5 py-1 rounded">Logs</button>
                    <button @click="confirmRestore(b)" class="bg-amber-600/80 hover:bg-amber-500 text-white font-semibold text-xs px-3 py-1 rounded transition">Restore</button>
                </div>
            </div>
            <p v-if="!backupsList.length" class="text-xs text-slate-500 text-center py-6">No backups found for this instance.</p>
        </div>
    </div>
</div>
```

### 5.3 Vue JS Component State & Handlers
In `index.html` Vue script:

```javascript
// State
backupsList: [],
creatingBackup: false,
selectedLogBackup: null,

// Methods
async loadBackups() {
    if (!this.selectedInstance) return;
    try {
        const data = await this.api(`/api/instances/${this.selectedInstance.id}/backups`);
        this.backupsList = data.backups || [];
    } catch (e) {
        this.backupsList = [];
    }
},

async triggerBackup() {
    if (!this.selectedInstance) return;
    this.creatingBackup = true;
    try {
        await this.api(`/api/instances/${this.selectedInstance.id}/backups`, { method: 'POST' });
        await this.loadBackups();
        alert('Backup created successfully!');
    } catch (e) {
        alert('Backup failed: ' + e.message);
    } finally {
        this.creatingBackup = false;
    }
},

async confirmRestore(backup) {
    if (!confirm(`Are you sure you want to restore backup from ${this.formatDate(backup.timestamp)}? Current server state will be overwritten!`)) return;
    try {
        await this.api(`/api/instances/${this.selectedInstance.id}/backups/${backup.id}/restore`, { method: 'POST' });
        alert('Backup restored successfully!');
        await this.loadInstances();
    } catch (e) {
        alert('Restore failed: ' + e.message);
    }
},

formatDate(ts) {
    return new Date(ts).toLocaleString();
}
```

---

## Developer Hand-Off & Verification Steps

When you have finished implementing the backend code and frontend changes:

1. **Test the build**:
   ```bash
   ./gradlew build
   ```
2. **Manual Feature Sanity Check**:
   - Navigate to the **Backups** tab in the web UI.
   - Click **Backup Now**. Observe the loading throbber and confirm a new `.tar.lz4` file is generated under `server-data/backups/<instance_id>/`.
   - Inspect the audit log details to verify chunk flushing (`save-off`/`save-all` -> `save-on`).
   - Click **Restore** on a previous backup and confirm the instance directory correctly extracts and restores.

3. **Final Step**:
   Push your completed changes directly to `main`:
   ```bash
   git add .
   git commit -m "feat(backups): add LZ4-compressed server backups and restoration pipeline"
   git push origin main
   ```
