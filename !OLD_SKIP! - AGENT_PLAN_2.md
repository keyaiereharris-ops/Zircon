# Agent Execution Plan: Forge and NeoForge Loader Integration

**Target Project:** Minecraft Server Companion Client / Wrapper  
**Primary Goal:** Refactor the existing launcher engine (currently Fabric-only) to support **Forge** and **NeoForge** using the **Headless Installer Execution Approach (Option 1)**, TOML metadata parsing, dynamic version profile resolution, and Java version mapping.

---

## Technical Overview & Context

* **Existing Capability:** The client synchronizes Fabric mods and launches Minecraft with Fabric Loader (`KnotClient`).
* **Target Capability:** Support Fabric, Forge, and NeoForge seamlessly.
* **Installer Execution Strategy:**
  1. Download official loader installer JAR from Maven.
  2. Run headless installer via `ProcessBuilder` using the target MC Java runtime.
  3. Parse the generated `.minecraft/versions/<profile-id>/<profile-id>.json`.
  4. Dynamically construct JVM arguments (including `@args.txt` argument files), classpath, and main class.
  5. Inspect `.jar` files using `fabric.mod.json`, `META-INF/mods.toml`, or `META-INF/neoforge.mods.toml`.

---

## Plan Structure Overview

```
Phase 1: Dependencies & Core Data Model Refactoring
Phase 2: Mod Metadata Extraction Service (TOML & JSON)
Phase 3: Headless Mod Loader Installer System
Phase 4: Version Profile JSON Parser & Argument Resolver
Phase 5: Launch Command Builder & Java Runtime Selection
Phase 6: Integration, Testing & Validation
```

---

## Phase 1: Dependencies & Core Data Model Refactoring

### Step 1.1: Project Dependencies
**Target File:** `build.gradle` or `pom.xml`

Add a TOML parsing library to read Forge/NeoForge mod metadata files.

**Actions:**
Add `org.tomlj:tomlj` (or `com.fasterxml.jackson.dataformat:jackson-dataformat-toml` if Jackson is already used).

```groovy
// build.gradle snippet
dependencies {
    // Existing Javalin / Jackson / Logback dependencies...
    
    // TOML Parsing for Forge / NeoForge mods
    implementation 'org.tomlj:tomlj:1.1.1'
}
```

---

### Step 1.2: Loader & Manifest Enums / Models
**Target File:** `src/main/java/com/companion/model/ModLoaderType.java`

Create/Update the enum representing supported loaders.

```java
package com.companion.model;

public enum ModLoaderType {
    FABRIC("fabric"),
    FORGE("forge"),
    NEOFORGE("neoforge");

    private final String id;

    ModLoaderType(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public static ModLoaderType fromString(String text) {
        for (ModLoaderType type : ModLoaderType.values()) {
            if (type.id.equalsIgnoreCase(text)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown mod loader type: " + text);
    }
}
```

**Target File:** `src/main/java/com/companion/model/ServerManifest.java`

Ensure the server configuration sent by the server wrapper includesloader details:

```java
package com.companion.model;

import java.util.List;

public class ServerManifest {
    private String serverName;
    private String minecraftVersion; // e.g. "1.20.4"
    private ModLoaderType loaderType; // FABRIC, FORGE, NEOFORGE
    private String loaderVersion;    // e.g. "0.15.7" for Fabric, "47.2.0" for Forge, "20.4.80" for NeoForge
    private List<ModFileSpec> requiredMods;

    // Getters, Setters, Constructors...
}
```

---

## Phase 2: Mod Metadata Extraction Service

Build a unified mod metadata reader capable of parsing `fabric.mod.json`, `META-INF/mods.toml`, and `META-INF/neoforge.mods.toml`.

### Step 2.1: Unified Metadata Data Model
**Target File:** `src/main/java/com/companion/model/ModMetadata.java`

```java
package com.companion.model;

import java.util.List;

public class ModMetadata {
    private final String id;
    private final String name;
    private final String version;
    private final String description;
    private final ModLoaderType loaderType;
    private final String environment; // "client", "server", "both"

    public ModMetadata(String id, String name, String version, String description, ModLoaderType loaderType, String environment) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.description = description;
        this.loaderType = loaderType;
        this.environment = environment;
    }

    // Getters...
}
```

---

### Step 2.2: TOML and JSON Mod Parsers
**Target File:** `src/main/java/com/companion/mod/ModMetadataExtractor.java`

Create a service to inspect any `.jar` file and parse its metadata file.

```java
package com.companion.mod;

import com.companion.model.ModLoaderType;
import com.companion.model.ModMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.File;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ModMetadataExtractor {

    private final ObjectMapper jsonMapper = new ObjectMapper();

    public ModMetadata extract(File jarFile) throws Exception {
        try (ZipFile zip = new ZipFile(jarFile)) {
            
            // 1. Check Fabric (fabric.mod.json)
            ZipEntry fabricEntry = zip.getEntry("fabric.mod.json");
            if (fabricEntry != null) {
                try (InputStream is = zip.getInputStream(fabricEntry)) {
                    JsonNode root = jsonMapper.readTree(is);
                    return new ModMetadata(
                        root.path("id").asText(),
                        root.path("name").asText(root.path("id").asText()),
                        root.path("version").asText("0.0.0"),
                        root.path("description").asText(""),
                        ModLoaderType.FABRIC,
                        root.path("environment").asText("*")
                    );
                }
            }

            // 2. Check NeoForge (META-INF/neoforge.mods.toml)
            ZipEntry neoForgeEntry = zip.getEntry("META-INF/neoforge.mods.toml");
            if (neoForgeEntry != null) {
                try (InputStream is = zip.getInputStream(neoForgeEntry)) {
                    return parseTomlMetadata(is, ModLoaderType.NEOFORGE);
                }
            }

            // 3. Check Forge (META-INF/mods.toml)
            ZipEntry forgeEntry = zip.getEntry("META-INF/mods.toml");
            if (forgeEntry != null) {
                try (InputStream is = zip.getInputStream(forgeEntry)) {
                    return parseTomlMetadata(is, ModLoaderType.FORGE);
                }
            }
        }
        throw new IllegalArgumentException("Unknown or unparseable mod jar: " + jarFile.getName());
    }

    private ModMetadata parseTomlMetadata(InputStream is, ModLoaderType loaderType) throws Exception {
        TomlParseResult result = Toml.parse(is);
        if (result.hasErrors()) {
            throw new IllegalArgumentException("Invalid TOML metadata: " + result.errors());
        }

        // [[mods]] array in TOML
        if (!result.contains("mods")) {
            throw new IllegalArgumentException("Missing [[mods]] section in TOML");
        }

        TomlTable modTable = result.getArray("mods").getTable(0);
        String id = modTable.getString("modId");
        String name = modTable.getString("displayName");
        String version = modTable.getString("version");
        String description = modTable.getString("description");

        return new ModMetadata(
            id != null ? id : "unknown",
            name != null ? name : id,
            version != null ? version : "0.0.0",
            description != null ? description : "",
            loaderType,
            "both"
        );
    }
}
```

---

## Phase 3: Headless Mod Loader Installer Strategy

Implement installer logic to download and headlessly execute installers.

### Step 3.1: Abstraction Interface
**Target File:** `src/main/java/com/companion/installer/ModLoaderInstaller.java`

```java
package com.companion.installer;

import java.io.File;
import java.util.concurrent.CompletableFuture;

public interface ModLoaderInstaller {
    /**
     * Checks if the version JSON profile exists for this loader installation.
     */
    boolean isInstalled(String mcVersion, String loaderVersion, File gameDir);

    /**
     * Downloads and headlessly installs the loader into gameDir.
     */
    CompletableFuture<Void> install(String mcVersion, String loaderVersion, File gameDir, String javaExecutablePath);
}
```

---

### Step 3.2: Process Runner Helper
**Target File:** `src/main/java/com/companion/installer/ProcessExecutionHelper.java`

Utility class to execute `ProcessBuilder` commands, capture output for logging, and handle process timeouts/errors.

```java
package com.companion.installer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.List;

public class ProcessExecutionHelper {

    private static final Logger logger = LoggerFactory.getLogger(ProcessExecutionHelper.class);

    public static int runProcess(List<String> command, File workingDir) throws Exception {
        logger.info("Executing command: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null) {
            pb.directory(workingDir);
        }
        pb.redirectErrorStream(true);

        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.info("[Installer Output] {}", line);
            }
        }

        int exitCode = process.waitFor();
        logger.info("Process finished with exit code: {}", exitCode);
        return exitCode;
    }
}
```

---

### Step 3.3: Forge Installer Implementation
**Target File:** `src/main/java/com/companion/installer/ForgeInstaller.java`

**Installer URL Format:**  
`https://maven.minecraftforge.net/net/minecraftforge/forge/{mcVersion}-{forgeVersion}/forge-{mcVersion}-{forgeVersion}-installer.jar`

**CLI Execution Command:**  
`java -jar forge-installer.jar --installClient <gameDir>`

```java
package com.companion.installer;

import com.companion.util.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ForgeInstaller implements ModLoaderInstaller {

    private static final Logger logger = LoggerFactory.getLogger(ForgeInstaller.class);
    private static final String FORGE_MAVEN_BASE = "https://maven.minecraftforge.net/net/minecraftforge/forge/";

    @Override
    public boolean isInstalled(String mcVersion, String loaderVersion, File gameDir) {
        String profileId = mcVersion + "-forge-" + loaderVersion;
        File versionJson = new File(gameDir, "versions/" + profileId + "/" + profileId + ".json");
        return versionJson.exists();
    }

    @Override
    public CompletableFuture<Void> install(String mcVersion, String loaderVersion, File gameDir, String javaExecutablePath) {
        return CompletableFuture.runAsync(() -> {
            try {
                if (isInstalled(mcVersion, loaderVersion, gameDir)) {
                    logger.info("Forge {}-{} is already installed.", mcVersion, loaderVersion);
                    return;
                }

                String fullVersion = mcVersion + "-" + loaderVersion;
                String downloadUrl = FORGE_MAVEN_BASE + fullVersion + "/forge-" + fullVersion + "-installer.jar";

                File cacheDir = new File(gameDir, ".cache/installers");
                cacheDir.mkdirs();
                File installerJar = new File(cacheDir, "forge-" + fullVersion + "-installer.jar");

                if (!installerJar.exists()) {
                    logger.info("Downloading Forge installer from {}", downloadUrl);
                    HttpUtils.downloadFile(downloadUrl, installerJar);
                }

                logger.info("Running Forge installer headlessly...");
                List<String> command = List.of(
                    javaExecutablePath,
                    "-jar",
                    installerJar.getAbsolutePath(),
                    "--installClient",
                    gameDir.getAbsolutePath()
                );

                int exitCode = ProcessExecutionHelper.runProcess(command, cacheDir);
                if (exitCode != 0) {
                    throw new RuntimeException("Forge installer failed with exit code: " + exitCode);
                }

                logger.info("Forge installed successfully.");
            } catch (Exception e) {
                throw new RuntimeException("Forge installation failed", e);
            }
        });
    }
}
```

---

### Step 3.4: NeoForge Installer Implementation
**Target File:** `src/main/java/com/companion/installer/NeoForgeInstaller.java`

**Installer URL Format:**  
* For NeoForge 1.20.2+ (e.g. version `20.4.80`):  
  `https://maven.neoforged.net/releases/net/neoforged/neoforge/{neoForgeVersion}/neoforge-{neoForgeVersion}-installer.jar`

**CLI Execution Command:**  
`java -jar neoforge-installer.jar --install-client <gameDir>`

```java
package com.companion.installer;

import com.companion.util.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class NeoForgeInstaller implements ModLoaderInstaller {

    private static final Logger logger = LoggerFactory.getLogger(NeoForgeInstaller.class);
    private static final String NEOFORGE_MAVEN_BASE = "https://maven.neoforged.net/releases/net/neoforged/neoforge/";

    @Override
    public boolean isInstalled(String mcVersion, String loaderVersion, File gameDir) {
        // NeoForge profile directory can be named "neoforge-" + loaderVersion or "neoforge-" + mcVersion + "-" + loaderVersion
        File versionsDir = new File(gameDir, "versions");
        if (!versionsDir.exists()) return false;

        File[] matches = versionsDir.listFiles((dir, name) -> name.toLowerCase().contains("neoforge") && name.contains(loaderVersion));
        return matches != null && matches.length > 0;
    }

    @Override
    public CompletableFuture<Void> install(String mcVersion, String loaderVersion, File gameDir, String javaExecutablePath) {
        return CompletableFuture.runAsync(() -> {
            try {
                if (isInstalled(mcVersion, loaderVersion, gameDir)) {
                    logger.info("NeoForge {} is already installed.", loaderVersion);
                    return;
                }

                // Modern NeoForge uses standalone version numbers (e.g. 20.4.80)
                String downloadUrl = NEOFORGE_MAVEN_BASE + loaderVersion + "/neoforge-" + loaderVersion + "-installer.jar";

                File cacheDir = new File(gameDir, ".cache/installers");
                cacheDir.mkdirs();
                File installerJar = new File(cacheDir, "neoforge-" + loaderVersion + "-installer.jar");

                if (!installerJar.exists()) {
                    logger.info("Downloading NeoForge installer from {}", downloadUrl);
                    HttpUtils.downloadFile(downloadUrl, installerJar);
                }

                logger.info("Running NeoForge installer headlessly...");
                List<String> command = List.of(
                    javaExecutablePath,
                    "-jar",
                    installerJar.getAbsolutePath(),
                    "--install-client",
                    gameDir.getAbsolutePath()
                );

                int exitCode = ProcessExecutionHelper.runProcess(command, cacheDir);
                if (exitCode != 0) {
                    throw new RuntimeException("NeoForge installer failed with exit code: " + exitCode);
                }

                logger.info("NeoForge installed successfully.");
            } catch (Exception e) {
                throw new RuntimeException("NeoForge installation failed", e);
            }
        });
    }
}
```

---

## Phase 4: Version Profile JSON Parser & Argument Resolver

After the installer runs, it generates a `<version-id>.json` profile inside `.minecraft/versions/<profile-id>/`. We must parse this file to construct launch JVM/Game arguments and classpaths.

### Step 4.1: Data Models for Version JSON
**Target File:** `src/main/java/com/companion/profile/VersionProfile.java`

```java
package com.companion.profile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class VersionProfile {
    @JsonProperty("id")
    private String id;

    @JsonProperty("mainClass")
    private String mainClass;

    @JsonProperty("inheritsFrom")
    private String inheritsFrom;

    @JsonProperty("libraries")
    private List<LibrarySpec> libraries;

    @JsonProperty("arguments")
    private JsonNode arguments; // Can be legacy string or structured object

    // Getters and Setters...
    public String getId() { return id; }
    public String getMainClass() { return mainClass; }
    public String getInheritsFrom() { return inheritsFrom; }
    public List<LibrarySpec> getLibraries() { return libraries; }
    public JsonNode getArguments() { return arguments; }
}
```

**Target File:** `src/main/java/com/companion/profile/LibrarySpec.java`

```java
package com.companion.profile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class LibrarySpec {
    @JsonProperty("name")
    private String name; // e.g. "net.neoforged:neoforge:20.4.80"

    public String getName() { return name; }

    public String getArtifactPath() {
        if (name == null) return null;
        String[] parts = name.split(":");
        if (parts.length < 3) return null;

        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];

        String classifier = parts.length > 3 ? "-" + parts[3] : "";

        return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + ".jar";
    }
}
```

---

### Step 4.2: Profile Resolution Service
**Target File:** `src/main/java/com/companion/profile/VersionProfileResolver.java`

```java
package com.companion.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class VersionProfileResolver {

    private final ObjectMapper mapper = new ObjectMapper();

    public VersionProfile parseProfile(File versionJsonFile) throws Exception {
        return mapper.readValue(versionJsonFile, VersionProfile.class);
    }

    /**
     * Resolves library JAR absolute paths from local libraries folder.
     */
    public List<String> buildClasspath(VersionProfile profile, File gameDir) {
        List<String> classpath = new ArrayList<>();
        File librariesDir = new File(gameDir, "libraries");

        if (profile.getLibraries() != null) {
            for (LibrarySpec lib : profile.getLibraries()) {
                String relPath = lib.getArtifactPath();
                if (relPath != null) {
                    File jarFile = new File(librariesDir, relPath);
                    if (jarFile.exists()) {
                        classpath.add(jarFile.getAbsolutePath());
                    }
                }
            }
        }
        return classpath;
    }

    /**
     * Extracts JVM arguments from version JSON (including @args argument files).
     */
    public List<String> extractJvmArguments(VersionProfile profile, File gameDir) {
        List<String> jvmArgs = new ArrayList<>();
        JsonNode argsNode = profile.getArguments();

        if (argsNode != null && argsNode.has("jvm")) {
            JsonNode jvmNode = argsNode.get("jvm");
            if (jvmNode.isArray()) {
                for (JsonNode arg : jvmNode) {
                    if (arg.isTextual()) {
                        String value = arg.asText();
                        // Handle replacement placeholders like ${library_directory}
                        value = value.replace("${library_directory}", new File(gameDir, "libraries").getAbsolutePath());
                        value = value.replace("${classpath_separator}", File.pathSeparator);
                        jvmArgs.add(value);
                    }
                }
            }
        }
        return jvmArgs;
    }
}
```

---

## Phase 5: Launch Command Builder & Java Runtime Selection

### Step 5.1: Java Version Selector
**Target File:** `src/main/java/com/companion/launch/JavaRuntimeSelector.java`

Map Minecraft versions to their required Java runtimes.

```java
package com.companion.launch;

public class JavaRuntimeSelector {

    public static int getRequiredJavaMajorVersion(String minecraftVersion) {
        String[] parts = minecraftVersion.split("\\.");
        if (parts.length < 2) return 17;

        int minor = Integer.parseInt(parts[1]);
        int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;

        if (minor < 17) {
            return 8; // MC < 1.17 uses Java 8
        } else if (minor == 17) {
            return 16; // MC 1.17 uses Java 16
        } else if (minor < 20 || (minor == 20 && patch < 5)) {
            return 17; // MC 1.18 - 1.20.4 uses Java 17
        } else {
            return 21; // MC 1.20.5+ uses Java 21
        }
    }

    public static String getJavaExecutablePath(int majorVersion) {
        // Look up Java path configured in launcher options or system path
        String systemJavaHome = System.getProperty("java.home");
        return systemJavaHome + "/bin/java"; // Can be expanded to detect installed JREs
    }
}
```

---

### Step 5.2: Unified Launch Command Builder
**Target File:** `src/main/java/com/companion/launch/LaunchCommandBuilder.java`

Construct the launch process arguments dynamically based on loader type.

```java
package com.companion.launch;

import com.companion.model.ModLoaderType;
import com.companion.profile.VersionProfile;
import com.companion.profile.VersionProfileResolver;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class LaunchCommandBuilder {

    private final VersionProfileResolver profileResolver = new VersionProfileResolver();

    public List<String> buildLaunchCommand(
            String mcVersion,
            ModLoaderType loaderType,
            String loaderVersion,
            File gameDir,
            String username,
            String sessionToken
    ) throws Exception {

        List<String> command = new ArrayList<>();

        int requiredJavaVersion = JavaRuntimeSelector.getRequiredJavaMajorVersion(mcVersion);
        String javaExec = JavaRuntimeSelector.getJavaExecutablePath(requiredJavaVersion);
        command.add(javaExec);

        if (loaderType == ModLoaderType.FABRIC) {
            // Fabric launch logic
            command.add("-cp");
            command.add(buildFabricClasspath(gameDir));
            command.add("net.fabricmc.loader.impl.launch.knot.KnotClient");
        } else {
            // Forge / NeoForge launch logic
            File profileJson = locateVersionProfileJson(gameDir, loaderType, loaderVersion);
            VersionProfile profile = profileResolver.parseProfile(profileJson);

            // Add JVM Arguments from Version Profile
            List<String> jvmArgs = profileResolver.extractJvmArguments(profile, gameDir);
            command.addAll(jvmArgs);

            // Classpath
            List<String> classpath = profileResolver.buildClasspath(profile, gameDir);
            command.add("-cp");
            command.add(String.join(File.pathSeparator, classpath));

            // Main class
            command.add(profile.getMainClass());
        }

        // Standard Minecraft Game Arguments
        command.add("--username"); command.add(username);
        command.add("--version"); command.add(mcVersion);
        command.add("--gameDir"); command.add(gameDir.getAbsolutePath());
        command.add("--assetsDir"); command.add(new File(gameDir, "assets").getAbsolutePath());
        command.add("--accessToken"); command.add(sessionToken);

        return command;
    }

    private File locateVersionProfileJson(File gameDir, ModLoaderType loaderType, String loaderVersion) {
        File versionsDir = new File(gameDir, "versions");
        File[] matches = versionsDir.listFiles((dir, name) -> name.contains(loaderVersion));
        if (matches != null && matches.length > 0) {
            File profileJson = new File(matches[0], matches[0].getName() + ".json");
            if (profileJson.exists()) return profileJson;
        }
        throw new IllegalStateException("Version profile JSON not found for " + loaderType + " version " + loaderVersion);
    }

    private String buildFabricClasspath(File gameDir) {
        // Collect Fabric libraries and vanilla client JAR
        return new File(gameDir, "bin/client.jar").getAbsolutePath();
    }
}
```

---

## Phase 6: Integration, Verification & Validation

### Step 6.1: Service Orchestration Entry Point
**Target File:** `src/main/java/com/companion/service/GameLauncherService.java`

Connect all components together into a single workflow.

```java
package com.companion.service;

import com.companion.installer.*;
import com.companion.launch.JavaRuntimeSelector;
import com.companion.launch.LaunchCommandBuilder;
import com.companion.model.ModLoaderType;
import com.companion.model.ServerManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

public class GameLauncherService {

    private static final Logger logger = LoggerFactory.getLogger(GameLauncherService.class);
    private final LaunchCommandBuilder commandBuilder = new LaunchCommandBuilder();

    public void prepareAndLaunch(ServerManifest manifest, File gameDir, String username, String token) throws Exception {
        logger.info("Preparing environment for loader: {}", manifest.getLoaderType());

        int requiredJava = JavaRuntimeSelector.getRequiredJavaMajorVersion(manifest.getMinecraftVersion());
        String javaExec = JavaRuntimeSelector.getJavaExecutablePath(requiredJava);

        // 1. Select and execute installer strategy
        ModLoaderInstaller installer;
        switch (manifest.getLoaderType()) {
            case FORGE -> installer = new ForgeInstaller();
            case NEOFORGE -> installer = new NeoForgeInstaller();
            case FABRIC -> installer = new FabricInstaller();
            default -> throw new UnsupportedOperationException("Unsupported loader: " + manifest.getLoaderType());
        }

        logger.info("Ensuring modloader is installed...");
        installer.install(
            manifest.getMinecraftVersion(),
            manifest.getLoaderVersion(),
            gameDir,
            javaExec
        ).get(); // Await completion

        // 2. Build launch command
        List<String> launchCommand = commandBuilder.buildLaunchCommand(
            manifest.getMinecraftVersion(),
            manifest.getLoaderType(),
            manifest.getLoaderVersion(),
            gameDir,
            username,
            token
        );

        // 3. Launch Minecraft Process
        logger.info("Launching Minecraft...");
        ProcessBuilder pb = new ProcessBuilder(launchCommand);
        pb.directory(gameDir);
        pb.inheritIO();
        pb.start();
    }
}
```

---

### Step 6.2: Agent Execution Verification Checklist

The coding agent must test and verify the following assertions:

* [ ] **Dependency Check:** `build.gradle` compiles with `tomlj`.
* [ ] **TOML Metadata:** `ModMetadataExtractor` successfully reads a sample `.jar` containing `META-INF/mods.toml` and `META-INF/neoforge.mods.toml`.
* [ ] **Installer Download:** `ForgeInstaller` downloads the installer JAR to `.cache/installers/`.
* [ ] **Headless Installation:** Process builder runs `java -jar forge-installer.jar --installClient <dir>` and exits with `0`.
* [ ] **Profile JSON Generation:** Verification that `.minecraft/versions/<id>/<id>.json` exists after installer run.
* [ ] **JVM Arguments:** Launch command contains `@` argument files (e.g. `@win_args.txt`) intact.
* [ ] **Game Directory Isolation:** Launch command correctly routes `--gameDir` to unique profile folder.
