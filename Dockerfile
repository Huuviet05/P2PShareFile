# Dockerfile cho Standalone Relay Server
FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /app

# Copy pom.xml và download dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build application
RUN mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy jar file từ build stage
COPY --from=build /app/target/*.jar app.jar

# Copy classes directory (chứa compiled classes)
COPY --from=build /app/target/classes ./classes

# Tạo thư mục cho relay storage
RUN mkdir -p /app/relay-storage && chmod 777 /app/relay-storage

# Environment variables (có thể override khi chạy)
ENV PORT=8080
ENV STORAGE_DIR=/app/relay-storage
ENV FILE_EXPIRY_HOURS=24
ENV MAX_FILE_SIZE_MB=100
ENV ENABLE_CORS=true

# Expose port
EXPOSE ${PORT}

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:${PORT}/api/relay/status/health || exit 1

# Start relay server
CMD ["java", "-cp", "classes:app.jar", "org.example.p2psharefile.relay.StandaloneRelayServer"]
