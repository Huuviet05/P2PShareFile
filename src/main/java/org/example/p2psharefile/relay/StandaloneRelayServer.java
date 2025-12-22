package org.example.p2psharefile.relay;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

/**
 * StandaloneRelayServer - Relay Server độc lập có thể chạy riêng biệt
 * 
 * Chức năng:
 * - Chạy relay server độc lập không phụ thuộc vào P2P client
 * - Có thể deploy lên cloud (Render, Heroku, Railway, etc.)
 * - Hỗ trợ config qua environment variables
 * - Tự động cleanup file hết hạn
 * 
 * Environment Variables:
 * - PORT: Port để chạy server (default: 8080)
 * - STORAGE_DIR: Thư mục lưu file (default: ./relay-storage)
 * - FILE_EXPIRY_HOURS: Thời gian hết hạn file (giờ, default: 24)
 * - MAX_FILE_SIZE_MB: Kích thước file tối đa (MB, default: 100)
 * - ENABLE_CORS: Enable CORS (default: true)
 * 
 * Sử dụng:
 * <pre>
 * // Cách 1: Run từ command line
 * java -cp target/classes org.example.p2psharefile.relay.StandaloneRelayServer
 * 
 * // Cách 2: Với custom port
 * PORT=9090 java -cp target/classes org.example.p2psharefile.relay.StandaloneRelayServer
 * 
 * // Cách 3: Deploy lên Render (set env vars trong dashboard)
 * PORT=10000
 * STORAGE_DIR=/tmp/relay-storage
 * FILE_EXPIRY_HOURS=48
 * </pre>
 * 
 * @author P2PShareFile Team
 * @version 1.0
 */
public class StandaloneRelayServer {
    
    private static final Logger LOGGER = Logger.getLogger(StandaloneRelayServer.class.getName());
    
    // Default values
    private static final int DEFAULT_PORT = 8080;
    private static final String DEFAULT_STORAGE_DIR = "relay-storage";
    private static final long DEFAULT_EXPIRY_HOURS = 24;
    private static final int DEFAULT_MAX_FILE_SIZE_MB = 100;
    
    public static void main(String[] args) {
        try {
            LOGGER.info("\n╔══════════════════════════════════════════════════╗");
            LOGGER.info("║   🌐 STANDALONE RELAY SERVER FOR P2P FILE SHARE  ║");
            LOGGER.info("╚══════════════════════════════════════════════════╝\n");
            
            // Đọc config từ environment variables
            int port = getEnvInt("PORT", DEFAULT_PORT);
            String storageDirPath = getEnv("STORAGE_DIR", DEFAULT_STORAGE_DIR);
            long expiryHours = getEnvLong("FILE_EXPIRY_HOURS", DEFAULT_EXPIRY_HOURS);
            int maxFileSizeMB = getEnvInt("MAX_FILE_SIZE_MB", DEFAULT_MAX_FILE_SIZE_MB);
            boolean enableCors = getEnvBoolean("ENABLE_CORS", true);
            
            Path storageDir = Paths.get(storageDirPath);
            long expiryMs = expiryHours * 60 * 60 * 1000; // Convert hours to ms
            
            // In config
            LOGGER.info("📋 Configuration:");
            LOGGER.info("   • Port: " + port);
            LOGGER.info("   • Storage Directory: " + storageDir.toAbsolutePath());
            LOGGER.info("   • File Expiry: " + expiryHours + " hours");
            LOGGER.info("   • Max File Size: " + maxFileSizeMB + " MB");
            LOGGER.info("   • CORS: " + (enableCors ? "Enabled" : "Disabled"));
            LOGGER.info("");
            
            // Tạo và khởi động server
            RelayServer server = new RelayServer(port, storageDir, expiryMs);
            server.start();
            
            LOGGER.info("✅ Relay Server đang chạy!");
            LOGGER.info("📡 Endpoints:");
            LOGGER.info("   • Upload: POST http://localhost:" + port + "/api/relay/upload");
            LOGGER.info("   • Download: GET http://localhost:" + port + "/api/relay/download/:uploadId");
            LOGGER.info("   • Status: GET http://localhost:" + port + "/api/relay/status/:uploadId");
            LOGGER.info("   • Health: GET http://localhost:" + port + "/api/relay/status/health");
            LOGGER.info("   • Peer Register: POST http://localhost:" + port + "/api/peers/register");
            LOGGER.info("   • Peer List: GET http://localhost:" + port + "/api/peers/list");
            LOGGER.info("   • Peer Heartbeat: POST http://localhost:" + port + "/api/peers/heartbeat");
            LOGGER.info("   • File Register: POST http://localhost:" + port + "/api/files/register");
            LOGGER.info("   • File Search: GET http://localhost:" + port + "/api/files/search");
            LOGGER.info("   • PIN Create: POST http://localhost:" + port + "/api/pin/create");
            LOGGER.info("   • PIN Find: GET http://localhost:" + port + "/api/pin/find");
            LOGGER.info("");
            LOGGER.info("⚡ Server is ready to accept connections!");
            LOGGER.info("   Press Ctrl+C to stop the server\n");
            
            // Keep server running
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOGGER.info("\n🛑 Shutting down relay server...");
                server.stop();
                LOGGER.info("✅ Server stopped successfully");
            }));
            
            // Chạy mãi mãi cho đến khi bị dừng
            Thread.currentThread().join();
            
        } catch (Exception e) {
            LOGGER.severe("❌ Failed to start relay server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * Đọc environment variable dạng String
     */
    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }
    
    /**
     * Đọc environment variable dạng int
     */
    private static int getEnvInt(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            LOGGER.warning("⚠ Invalid " + key + " value: " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }
    
    /**
     * Đọc environment variable dạng long
     */
    private static long getEnvLong(String key, long defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            LOGGER.warning("⚠ Invalid " + key + " value: " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }
    
    /**
     * Đọc environment variable dạng boolean
     */
    private static boolean getEnvBoolean(String key, boolean defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }
}
