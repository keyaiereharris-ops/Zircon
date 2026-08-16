# Agent Plan: Enforce Zircon Client Connection Verification

This step-by-step agent plan can be used directly by an AI coding agent or implemented manually.

---

## Phase 1: Backend Ticket Manager & REST Endpoint

### Step 1.1: Create `JoinTicketManager.java`
**File Location**: `main/java/com/mcmanager/server/auth/JoinTicketManager.java`

Maintains short-lived (e.g., 60-second) connection tickets issued by the launcher when a player clicks "PLAY".

```java
package com.mcmanager.server.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class JoinTicketManager {

    private static final long TICKET_TTL_MS = 60_000; // 60 seconds
    private static final Map<String, Long> activeTickets = new ConcurrentHashMap<>();

    static {
        // Cleanup thread for expired tickets
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            activeTickets.entrySet().removeIf(entry -> entry.getValue() < now);
        }, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Registers an intent to join for a specific username or UUID.
     */
    public static void registerTicket(String identifier) {
        if (identifier != null && !identifier.isBlank()) {
            activeTickets.put(identifier.trim().toLowerCase(), System.currentTimeMillis() + TICKET_TTL_MS);
        }
    }

    /**
     * Checks if a ticket exists and consumes it (one-time use).
     */
    public static boolean consumeTicket(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return false;
        }
        String key = identifier.trim().toLowerCase();
        Long expiry = activeTickets.remove(key);
        return expiry != null && expiry > System.currentTimeMillis();
    }
}
```

---

### Step 1.2: Add REST Endpoint in `InstanceController.java` & `JavalinApp.java`

#### 1. Add route in `InstanceController.java`:
```java
/** POST /api/instances/{id}/join-intent */
public void registerJoinIntent(Context ctx) {
    JoinIntentRequest body = ctx.bodyAsClass(JoinIntentRequest.class);
    if (body == null || (body.username == null && body.uuid == null)) {
        ctx.status(400).result("username or uuid is required");
        return;
    }
    
    if (body.username != null) JoinTicketManager.registerTicket(body.username);
    if (body.uuid != null) JoinTicketManager.registerTicket(body.uuid);
    
    ctx.json(Map.of("ok", true, "expiresInSeconds", 60));
}

public static class JoinIntentRequest {
    public String username;
    public String uuid;
}
```

#### 2. Register endpoint in `JavalinApp.java`:
```java
// Allow launcher clients to register join intent without requiring admin bearer auth
app.post("/api/instances/{id}/join-intent", instanceController::registerJoinIntent);
```

---

## Phase 2: Netty Minecraft Disconnect Packet Encoder

### Step 2.1: Create `MinecraftDisconnectUtil.java`
**File Location**: `main/java/com/mcmanager/server/multiplexer/MinecraftDisconnectUtil.java`

Constructs a valid Minecraft binary `Disconnect (Login)` packet containing formatted JSON text.

```java
package com.mcmanager.server.multiplexer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;

public final class MinecraftDisconnectUtil {

    private MinecraftDisconnectUtil() {}

    /**
     * Creates a Minecraft Login Disconnect Packet (Packet ID 0x00 in Login State).
     */
    public static ByteBuf createDisconnectPacket(String jsonMessage) {
        byte[] messageBytes = jsonMessage.getBytes(StandardCharsets.UTF_8);
        
        ByteBuf packetBuf = Unpooled.buffer();
        writeVarInt(packetBuf, 0x00); // Packet ID for Login Disconnect
        writeVarInt(packetBuf, messageBytes.length); // String length
        packetBuf.writeBytes(messageBytes); // String payload

        ByteBuf frameBuf = Unpooled.buffer();
        writeVarInt(frameBuf, packetBuf.readableBytes()); // Total Frame Length
        frameBuf.writeBytes(packetBuf);
        packetBuf.release();

        return frameBuf;
    }

    public static String buildCustomErrorMessage() {
        return """
        {
          "text": "⚡ Zircon Client Required\\n\\n",
          "color": "red",
          "bold": true,
          "extra": [
            {
              "text": "You must use the official Zircon Launcher to join this server.\\n\\n",
              "color": "gray",
              "bold": false
            },
            {
              "text": "Launch the game using your Zircon client to auto-sync mods and connect.",
              "color": "gold"
            }
          ]
        }
        """;
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value & 0x7F);
    }
}
```

---

## Phase 3: Protocol Inspector & Connection Verification

### Step 3.1: Update `ProtocolDetector.java`

Inspect the incoming Minecraft connection: if it's a Minecraft `Login Start` packet (Packet `0x00` in Login state), extract the player's username.

Check if `JoinTicketManager.consumeTicket(username)` returns `true`:
- **If Valid**: Hand off to `ProxyHandler` as normal.
- **If Invalid**: Write the `createDisconnectPacket(...)` to the channel and close the socket!

#### Updates to `ProtocolDetector.java`:
```java
// Inside ProtocolDetector.java

if (isMinecraftHandshake(in)) {
    String username = tryExtractLoginUsername(in);
    if (username != null) {
        boolean authorized = JoinTicketManager.consumeTicket(username);
        if (!authorized) {
            log.info("Rejected connection for '{}' — No active Zircon join ticket found.", username);
            ByteBuf disconnectPacket = MinecraftDisconnectUtil.createDisconnectPacket(
                    MinecraftDisconnectUtil.buildCustomErrorMessage());
            ctx.writeAndFlush(disconnectPacket).addListener(f -> ctx.close());
            return;
        }
    }
}
```

---

## Phase 4: Client Launcher Pre-Join Hook

### Step 4.1: Update Launcher Flow in `MainController.java`
**File Location**: `main/java/com/mcmanager/client/ui/controller/MainController.java`

Right before launching the Minecraft client process, make a lightweight HTTP POST call to register the intent to join.

#### Implementation in `MainController.java`:
```java
// Inside runFlow(String serverAddress) before runner.launch(...)

status("Registering pre-join intent ticket with Zircon Server...");
try {
    registerPreJoinIntent(baseUrl, instanceId, session.getUsername(), session.getUuid());
} catch (Exception e) {
    log.warn("Could not pre-register join ticket: {}", e.getMessage());
}

// Proceed with runner.launch(...)
```

#### Helper Method:
```java
private void registerPreJoinIntent(String baseUrl, String instanceId, String username, String uuid) 
        throws IOException, InterruptedException {
    HttpClient client = HttpClient.newHttpClient();
    String json = String.format("{\"username\":\"%s\",\"uuid\":\"%s\"}", username, uuid);
    
    HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/instances/" + instanceId + "/join-intent"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
            
    client.send(request, HttpResponse.BodyHandlers.discarding());
}
```

---

## Verification & Testing Checklist

1. **Vanilla Launcher Test**:
   - Open standard MultiMC, Prism, or Official Mojang Launcher.
   - Try connecting to `localhost:25565` (or server IP).
   - **Expected Result**: Immediately rejected with the formatted red/gold in-game error screen: *"⚡ Zircon Client Required"*.
2. **Zircon Client Launcher Test**:
   - Click **PLAY** inside the Zircon Client Launcher.
   - **Expected Result**: Ticket is registered, Netty proxy validates the ticket, and player connects straight into the game server!
