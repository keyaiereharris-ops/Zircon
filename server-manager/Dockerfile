FROM eclipse-temurin:21-jdk-alpine

WORKDIR /app

# Install bash, curl, and tar (required for server installers and decompression)
RUN apk add --no-cache bash curl tar

# Copy built Fat JAR from Gradle Shadow build (path is relative to build context root)
COPY server-manager/build/libs/server-manager-all.jar /app/server-manager.jar

# Exposed Ports:
# 25565 - Public TCP Multiplexer (Minecraft & HTTP Ingress)
# 25564 - Admin Web API / UI
# 25700-25710 - Internal Server Instance Pool
EXPOSE 25565 25564 25700-25710

# External volume mount for persistent server data
VOLUME ["/app/server-data"]

# Run server manager with explicit data directory property
ENTRYPOINT ["java", "-Dmcmanager.dataDir=/app/server-data", "-Xms1G", "-Xmx4G", "-jar", "/app/server-manager.jar"]
