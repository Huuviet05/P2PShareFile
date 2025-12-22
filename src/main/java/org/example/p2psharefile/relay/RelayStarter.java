package org.example.p2psharefile.relay;

import org.example.p2psharefile.network.RelayConfig;
import org.example.p2psharefile.service.P2PService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * RelayStarter - Tự động khởi động Relay Server và enable Relay Client
 * 
 * Chức năng:
 * - Khởi động RelayServer trong background thread
 * - Enable relay trong P2PService với cấu hình development
 * - Tự động tạo thư mục lưu trữ relay
 * 
 * Sử dụng:
 * <pre>
 * P2PService p2pService = new P2PService("MyPeer", 0);
 * RelayStarter.startRelayInBackground(p2pService);
 * p2pService.start();
 * </pre>
 */
public class RelayStarter {
    
    private static final Logger LOGGER = Logger.getLogger(RelayStarter.class.getName());
    private static final int DEFAULT_RELAY_PORT = 8080;
    private static final String DEFAULT_STORAGE_DIR = "relay-storage";
    private static final long DEFAULT_EXPIRY_MS = 24 * 60 * 60 * 1000; // 24 giờ
    
    // ========== RELAY SERVER URL ==========
    // Mặc định sử dụng Render.com relay server cho Internet sharing
    // Để test local: set USE_LOCAL_RELAY=true
    private static final String PRODUCTION_RELAY_URL = "https://p2p-relay-server.onrender.com";
    
    // Environment variables
    private static final String ENV_RELAY_SERVER_URL = "RELAY_SERVER_URL";
    private static final String ENV_START_RELAY_SERVER = "START_RELAY_SERVER"; // true/false
    private static final String ENV_USE_LOCAL_RELAY = "USE_LOCAL_RELAY"; // true/false để dùng local server
    
    private static RelayServer relayServer;
    private static ExecutorService relayExecutor;
    
    /**
     * Khởi động Relay Server và enable Relay Client
     * 
     * @param p2pService P2P Service để enable relay
     * @return true nếu thành công
     */
    public static boolean startRelayInBackground(P2PService p2pService) {
        return startRelayInBackground(p2pService, DEFAULT_RELAY_PORT, DEFAULT_STORAGE_DIR, DEFAULT_EXPIRY_MS);
    }
    
    /**
     * Khởi động Relay Server và enable Relay Client với cấu hình tùy chỉnh
     * 
     * @param p2pService P2P Service để enable relay
     * @param port Port cho relay server
     * @param storageDir Thư mục lưu file relay
     * @param expiryMs Thời gian hết hạn file (milliseconds)
     * @return true nếu thành công
     */
    public static boolean startRelayInBackground(P2PService p2pService, int port, String storageDir, long expiryMs) {
        try {
                LOGGER.info("\n🌐 ========== KHỞI ĐỘNG RELAY SYSTEM ==========");
            
            // Kiểm tra environment variables
            String relayServerUrl = System.getenv(ENV_RELAY_SERVER_URL);
            String useLocalRelayEnv = System.getenv(ENV_USE_LOCAL_RELAY);
            boolean useLocalRelay = "true".equalsIgnoreCase(useLocalRelayEnv);
            
            // Xác định relay server URL
            String actualRelayUrl;
            if (relayServerUrl != null && !relayServerUrl.isEmpty()) {
                // Custom URL từ environment
                actualRelayUrl = relayServerUrl;
                LOGGER.info("🌍 Sử dụng relay server từ environment: " + actualRelayUrl);
            } else if (useLocalRelay) {
                // Test mode: sử dụng local server
                actualRelayUrl = null; // Sẽ khởi động local server
                LOGGER.info("🏠 Chế độ test: Sử dụng local relay server");
            } else {
                // Mặc định: Sử dụng Render.com production server
                actualRelayUrl = PRODUCTION_RELAY_URL;
                LOGGER.info("🌍 Sử dụng Render.com relay server: " + actualRelayUrl);
            }
            
            // Nếu có URL (remote server), không cần start local
            if (actualRelayUrl != null) {
                LOGGER.info("   → Không khởi động local relay server");
                
                RelayConfig config = RelayConfig.forDevelopment();
                config.setServerUrl(actualRelayUrl);
                config.setPreferP2P(true);
                config.setP2pTimeoutMs(10000);
                config.setForceRelay(false);
                
                p2pService.enableRelay(config);
                
                LOGGER.info("✅ RelayClient đã được kích hoạt (remote server)");
                LOGGER.info("   • Server URL: " + config.getServerUrl());
                LOGGER.info("   • Ưu tiên P2P: " + config.isPreferP2P());
                LOGGER.info("   • P2P Timeout: " + config.getP2pTimeoutMs() + "ms");
                LOGGER.info("==================================================\n");
                
                return true;
            }
            
            // Local relay mode: Start local server
            String startServerEnv = System.getenv(ENV_START_RELAY_SERVER);
            boolean shouldStartServer = (startServerEnv == null || "true".equalsIgnoreCase(startServerEnv));
            if (!shouldStartServer) {
                LOGGER.info("⚠ Relay server bị disable (START_RELAY_SERVER=false)");
                return false;
            }
            
            // Tạo thư mục lưu trữ nếu chưa có
            Path storagePath = Paths.get(storageDir);
            if (!Files.exists(storagePath)) {
                Files.createDirectories(storagePath);
                LOGGER.info("📁 Đã tạo thư mục relay: " + storagePath.toAbsolutePath());
            }
            
            // Khởi động Relay Server trong background
            LOGGER.info("🚀 Đang khởi động RelayServer LOCAL...");
            LOGGER.info("   • Port: " + port);
            LOGGER.info("   • Thư mục lưu trữ: " + storagePath.toAbsolutePath());
            LOGGER.info("   • Thời gian hết hạn: " + (expiryMs / 1000 / 60 / 60) + " giờ");
            
            relayServer = new RelayServer(port, storagePath, expiryMs);
            relayExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "RelayServer-Thread");
                thread.setDaemon(true); // Daemon thread để tự động dừng khi app exit
                return thread;
            });
            
            relayExecutor.submit(() -> {
                try {
                    relayServer.start();
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "❌ Lỗi khởi động RelayServer: " + e.getMessage(), e);
                }
            });
            
            // Đợi một chút để server khởi động
            Thread.sleep(500);

            LOGGER.info("✅ RelayServer đã khởi động trên port " + port);
            
            // Enable Relay Client trong P2PService
            LOGGER.info("\n🔧 Cấu hình RelayClient...");
            RelayConfig config = RelayConfig.forDevelopment();
            // Sử dụng localhost với port vừa khởi động
            config.setServerUrl("http://localhost:" + port);
            config.setPreferP2P(true);
            config.setP2pTimeoutMs(10000); // 10 giây timeout cho P2P
            config.setForceRelay(false);
            
            p2pService.enableRelay(config);

            System.out.println("✅ RelayClient đã được kích hoạt");
            System.out.println("   • Server URL: " + config.getServerUrl());
            System.out.println("   • Ưu tiên P2P: " + config.isPreferP2P());
            System.out.println("   • P2P Timeout: " + config.getP2pTimeoutMs() + "ms");
            System.out.println("   • Bắt buộc Relay: " + config.isForceRelay());

            System.out.println("\n📡 Relay System sẵn sàng!");
            System.out.println("   → P2P LAN: Ưu tiên (nhanh)");
            System.out.println("   → Relay Internet: Fallback tự động");
            System.out.println("==================================================\n");
            
            return true;
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "❌ Lỗi khởi động Relay System: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Dừng Relay Server
     */
    public static void stopRelay() {
        if (relayServer != null) {
            LOGGER.info("🛑 Đang dừng RelayServer...");
            relayServer.stop();
            relayServer = null;
        }
        if (relayExecutor != null) {
            relayExecutor.shutdown();
            relayExecutor = null;
        }
        LOGGER.info("✅ RelayServer đã dừng");
    }
    
    /**
     * Kiểm tra relay có đang chạy không
     */
    public static boolean isRelayRunning() {
        return relayServer != null;
    }
    
    /**
     * Lấy relay server instance (để test)
     */
    public static RelayServer getRelayServer() {
        return relayServer;
    }
}
