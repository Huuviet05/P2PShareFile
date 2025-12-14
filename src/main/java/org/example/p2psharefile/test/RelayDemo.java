package org.example.p2psharefile.test;

import org.example.p2psharefile.model.*;
import org.example.p2psharefile.network.*;
import org.example.p2psharefile.security.FileHashUtil;

import java.io.File;
import java.util.Scanner;

/**
 * RelayDemo - Demo test tính năng Relay
 * 
 * Test các chức năng:
 * 1. Khởi tạo RelayClient với config
 * 2. Upload file lên relay server (mock)
 * 3. Download file từ relay server (mock)
 * 4. Hiển thị progress real-time
 * 
 * Lưu ý: Cần có relay server đang chạy để test thật
 * 
 * @author P2PShareFile Team
 */
public class RelayDemo {
    
    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  RELAY DEMO - P2PShareFile");
        System.out.println("=".repeat(60));
        System.out.println();
        
        // Tạo config cho development
        RelayConfig config = RelayConfig.forDevelopment();
        System.out.println("📋 Cấu hình Relay:");
        System.out.println("  • Server URL: " + config.getServerUrl());
        System.out.println("  • Prefer P2P: " + config.isPreferP2P());
        System.out.println("  • P2P Timeout: " + config.getP2pTimeoutMs() + "ms");
        System.out.println("  • Chunk Size: " + formatBytes(config.getChunkSize()));
        System.out.println("  • Enable Encryption: " + config.isEnableEncryption());
        System.out.println("  • Max Retries: " + config.getMaxRetries());
        System.out.println();
        
        // Tạo RelayClient
        RelayClient relayClient = new RelayClient(config);
        System.out.println("✅ RelayClient đã khởi tạo");
        System.out.println();
        
        // Menu
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.println("-".repeat(60));
            System.out.println("MENU:");
            System.out.println("  1. Test Upload File");
            System.out.println("  2. Test Download File");
            System.out.println("  3. Test Config");
            System.out.println("  4. Test Progress Tracking");
            System.out.println("  5. Exit");
            System.out.print("\nChọn (1-5): ");
            
            String choice = scanner.nextLine().trim();
            System.out.println();
            
            switch (choice) {
                case "1" -> testUpload(relayClient, scanner);
                case "2" -> testDownload(relayClient, scanner);
                case "3" -> testConfig();
                case "4" -> testProgressTracking();
                case "5" -> {
                    System.out.println("👋 Bye!");
                    return;
                }
                default -> System.out.println("❌ Lựa chọn không hợp lệ!");
            }
            
            System.out.println();
        }
    }
    
    /**
     * Test upload file
     */
    private static void testUpload(RelayClient relayClient, Scanner scanner) {
        System.out.println("🚀 TEST UPLOAD FILE");
        System.out.println("-".repeat(60));
        
        System.out.print("Nhập đường dẫn file để upload: ");
        String filePath = scanner.nextLine().trim();
        
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            System.out.println("❌ File không tồn tại: " + filePath);
            return;
        }
        
        System.out.println("📄 File: " + file.getName() + " (" + formatBytes(file.length()) + ")");
        System.out.println("🔄 Đang tính hash...");
        
        try {
            String fileHash = FileHashUtil.calculateSHA256(file);
            System.out.println("✓ Hash: " + fileHash);
            
            // Tạo upload request
            RelayUploadRequest request = new RelayUploadRequest(
                "test-peer-id",
                "Test Peer",
                file.getName(),
                file.length(),
                fileHash
            );
            request.setMimeType(guessMimeType(file.getName()));
            request.setDescription("Test upload from RelayDemo");
            
            System.out.println("🚀 Bắt đầu upload...");
            System.out.println();
            
            // Upload với listener
            RelayFileInfo result = relayClient.uploadFile(file, request, new RelayClient.RelayTransferListener() {
                private long lastUpdateTime = 0;
                
                @Override
                public void onProgress(RelayTransferProgress progress) {
                    long now = System.currentTimeMillis();
                    // Chỉ update mỗi 500ms để tránh spam console
                    if (now - lastUpdateTime < 500) return;
                    lastUpdateTime = now;
                    
                    System.out.printf("\r📤 Upload: %.1f%% | %s | %s | ETA: %s",
                        progress.getPercentage(),
                        formatBytes((long) progress.getTransferredBytes()) + "/" + formatBytes(progress.getTotalBytes()),
                        progress.getFormattedSpeed(),
                        progress.getFormattedTimeRemaining()
                    );
                }
                
                @Override
                public void onComplete(RelayFileInfo fileInfo) {
                    System.out.println();
                    System.out.println("✅ Upload thành công!");
                    System.out.println("  • Upload ID: " + fileInfo.getUploadId());
                    System.out.println("  • Download URL: " + fileInfo.getDownloadUrl());
                }
                
                @Override
                public void onError(Exception e) {
                    System.out.println();
                    System.out.println("❌ Upload thất bại: " + e.getMessage());
                    e.printStackTrace();
                }
            });
            
            if (result != null) {
                System.out.println("\n📋 RelayFileInfo:");
                System.out.println(result);
            }
            
        } catch (Exception e) {
            System.out.println("❌ Lỗi: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Test download file
     */
    private static void testDownload(RelayClient relayClient, Scanner scanner) {
        System.out.println("🔽 TEST DOWNLOAD FILE");
        System.out.println("-".repeat(60));
        
        System.out.print("Nhập Upload ID: ");
        String uploadId = scanner.nextLine().trim();
        
        System.out.print("Nhập đường dẫn file đích: ");
        String destPath = scanner.nextLine().trim();
        
        File destFile = new File(destPath);
        
        // Tạo mock RelayFileInfo
        RelayFileInfo fileInfo = new RelayFileInfo();
        fileInfo.setUploadId(uploadId);
        fileInfo.setFileName(destFile.getName());
        fileInfo.setDownloadUrl(relayClient.getConfig().getServerUrl() + 
                                relayClient.getConfig().getDownloadEndpoint() + "/" + uploadId);
        
        System.out.println("🔽 Bắt đầu download...");
        System.out.println();
        
        // Download với listener
        boolean success = relayClient.downloadFile(fileInfo, destFile, new RelayClient.RelayTransferListener() {
            private long lastUpdateTime = 0;
            
            @Override
            public void onProgress(RelayTransferProgress progress) {
                long now = System.currentTimeMillis();
                if (now - lastUpdateTime < 500) return;
                lastUpdateTime = now;
                
                System.out.printf("\r📥 Download: %.1f%% | %s | %s | ETA: %s",
                    progress.getPercentage(),
                    formatBytes((long) progress.getTransferredBytes()) + "/" + formatBytes(progress.getTotalBytes()),
                    progress.getFormattedSpeed(),
                    progress.getFormattedTimeRemaining()
                );
            }
            
            @Override
            public void onComplete(RelayFileInfo fileInfo) {
                System.out.println();
                System.out.println("✅ Download thành công: " + destFile.getAbsolutePath());
            }
            
            @Override
            public void onError(Exception e) {
                System.out.println();
                System.out.println("❌ Download thất bại: " + e.getMessage());
                e.printStackTrace();
            }
        });
        
        if (success) {
            System.out.println("\n✓ File đã lưu: " + destFile.getAbsolutePath());
        }
    }
    
    /**
     * Test config
     */
    private static void testConfig() {
        System.out.println("⚙️  TEST CONFIG");
        System.out.println("-".repeat(60));
        
        // Test các config khác nhau
        System.out.println("1️⃣  Development Config:");
        RelayConfig devConfig = RelayConfig.forDevelopment();
        printConfig(devConfig);
        
        System.out.println("\n2️⃣  Production Config:");
        RelayConfig prodConfig = RelayConfig.forProduction("https://relay.production.com", "prod-api-key");
        printConfig(prodConfig);
        
        System.out.println("\n3️⃣  Custom Config:");
        RelayConfig customConfig = new RelayConfig();
        customConfig.setServerUrl("https://my-relay.com");
        customConfig.setChunkSize(2 * 1024 * 1024); // 2MB
        customConfig.setPreferP2P(false);
        customConfig.setForceRelay(true);
        printConfig(customConfig);
        
        System.out.println("\n4️⃣  Config Validation:");
        System.out.println("  • Dev config valid: " + devConfig.isValid());
        System.out.println("  • Prod config valid: " + prodConfig.isValid());
        System.out.println("  • Custom config valid: " + customConfig.isValid());
    }
    
    /**
     * Test progress tracking
     */
    private static void testProgressTracking() {
        System.out.println("📊 TEST PROGRESS TRACKING");
        System.out.println("-".repeat(60));
        
        // Tạo progress tracker
        RelayTransferProgress progress = new RelayTransferProgress(
            "test-transfer-id",
            RelayTransferProgress.TransferType.UPLOAD,
            "test-file.pdf",
            10 * 1024 * 1024 // 10MB
        );
        
        progress.setTotalChunks(10);
        
        System.out.println("Mô phỏng upload file 10MB với 10 chunks...\n");
        
        // Simulate progress
        for (int i = 0; i <= 10; i++) {
            long bytesTransferred = i * 1024 * 1024; // i MB
            progress.setCurrentChunk(i);
            progress.updateProgress(bytesTransferred);
            
            System.out.printf("Chunk %d/10: %.1f%% | %s | Speed: %s | ETA: %s | Status: %s%n",
                progress.getCurrentChunk(),
                progress.getPercentage(),
                formatBytes(progress.getTransferredBytes()) + "/" + formatBytes(progress.getTotalBytes()),
                progress.getFormattedSpeed(),
                progress.getFormattedTimeRemaining(),
                progress.getStatus()
            );
            
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                break;
            }
        }
        
        System.out.println("\n✅ Transfer completed!");
        System.out.println("Final progress: " + progress);
    }
    
    /**
     * In thông tin config
     */
    private static void printConfig(RelayConfig config) {
        System.out.println("  • Server URL: " + config.getServerUrl());
        System.out.println("  • Prefer P2P: " + config.isPreferP2P());
        System.out.println("  • Force Relay: " + config.isForceRelay());
        System.out.println("  • P2P Timeout: " + config.getP2pTimeoutMs() + "ms");
        System.out.println("  • Chunk Size: " + formatBytes(config.getChunkSize()));
        System.out.println("  • Max Retries: " + config.getMaxRetries());
        System.out.println("  • Enable Encryption: " + config.isEnableEncryption());
        System.out.println("  • Enable Resume: " + config.isEnableResume());
        System.out.println("  • Log Level: " + config.getLogLevel());
    }
    
    /**
     * Format bytes thành string dễ đọc
     */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
    
    /**
     * Đoán MIME type từ extension
     */
    private static String guessMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }
}
