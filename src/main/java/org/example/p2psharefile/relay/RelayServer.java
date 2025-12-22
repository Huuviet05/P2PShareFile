package org.example.p2psharefile.relay;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * RelayServer - HTTP Server đơn giản để relay file giữa các peers
 * 
 * Chức năng:
 * - Upload file theo chunks (multipart)
 * - Download file với resume support
 * - Lưu trữ file tạm thời với expiry time
 * - Cleanup file hết hạn tự động
 * 
 * Endpoints:
 * - POST /api/relay/upload - Upload chunks
 * - GET /api/relay/download/:uploadId - Download file
 * - GET /api/relay/status/:uploadId - Kiểm tra status
 * 
 * @author P2PShareFile Team
 * @version 1.0
 */
public class RelayServer {
    
    private static final Logger LOGGER = Logger.getLogger(RelayServer.class.getName());
    
    private final int port;
    private final Path storageDir;
    private final long defaultExpiryMs;
    private final Map<String, UploadSession> uploads;
    private final Map<String, FileEntry> fileRegistry;  // File registry cho search
    private final Map<String, PINSession> pinRegistry;  // PIN registry cho Quick Share
    private final ScheduledExecutorService cleanupExecutor;
    private final PeerRegistry peerRegistry;  // Peer discovery qua relay
    
    private HttpServer server;
    private volatile boolean running;
    
    /**
     * File entry trong registry (để search)
     */
    private static class FileEntry {
        String uploadId;
        String fileName;
        long fileSize;
        String fileHash;
        String senderId;
        String senderName;
        String downloadUrl;
        long createdTime;
        long expiryTime;
        
        FileEntry(String uploadId, String fileName, long fileSize, String fileHash,
                 String senderId, String senderName, String downloadUrl, long expiryTime) {
            this.uploadId = uploadId;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.fileHash = fileHash;
            this.senderId = senderId;
            this.senderName = senderName;
            this.downloadUrl = downloadUrl;
            this.createdTime = System.currentTimeMillis();
            this.expiryTime = expiryTime;
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() > expiryTime;
        }
    }
    
    /**
     * PIN session cho Quick Share
     */
    private static class PINSession {
        String pin;
        String uploadId;
        String fileName;
        long fileSize;
        String fileHash;
        String senderId;
        String senderName;
        String downloadUrl;
        long createdTime;
        long expiryTime;
        
        PINSession(String pin, String uploadId, String fileName, long fileSize, String fileHash,
                  String senderId, String senderName, String downloadUrl, long expiryTime) {
            this.pin = pin;
            this.uploadId = uploadId;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.fileHash = fileHash;
            this.senderId = senderId;
            this.senderName = senderName;
            this.downloadUrl = downloadUrl;
            this.createdTime = System.currentTimeMillis();
            this.expiryTime = expiryTime;
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() > expiryTime;
        }
    }
    
    /**
     * Upload session tracking
     */
    private static class UploadSession {
        String uploadId;
        String fileName;
        long totalSize;
        long uploadedSize;
        List<Integer> receivedChunks;
        long createdTime;
        long expiryTime;
        Path filePath;
        
        UploadSession(String uploadId, String fileName) {
            this.uploadId = uploadId;
            this.fileName = fileName;
            this.receivedChunks = new ArrayList<>();
            this.createdTime = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() > expiryTime;
        }
        
        boolean isComplete() {
            return uploadedSize >= totalSize && totalSize > 0;
        }
    }
    
    /**
     * Constructor
     * @param port Port để chạy server
     * @param storageDir Thư mục lưu file
     * @param defaultExpiryMs Thời gian hết hạn mặc định (ms)
     */
    public RelayServer(int port, Path storageDir, long defaultExpiryMs) {
        this.port = port;
        this.storageDir = storageDir;
        this.defaultExpiryMs = defaultExpiryMs;
        this.uploads = new ConcurrentHashMap<>();
        this.fileRegistry = new ConcurrentHashMap<>();
        this.pinRegistry = new ConcurrentHashMap<>();
        this.cleanupExecutor = Executors.newScheduledThreadPool(1);
        this.peerRegistry = new PeerRegistry();
        this.running = false;
    }
    
    /**
     * Khởi động server
     */
    public void start() throws IOException {
        if (running) {
            LOGGER.warning("⚠ Server đã chạy rồi");
            return;
        }
        
        // Tạo storage directory
        Files.createDirectories(storageDir);
        
        // Tạo HTTP server
        server = HttpServer.create(new InetSocketAddress(port), 0);
        
        // Đăng ký endpoints
        server.createContext("/api/relay/upload", new UploadHandler());
        server.createContext("/api/relay/download", new DownloadHandler());
        server.createContext("/api/relay/status", new StatusHandler());
        
        // Health check endpoint (cho Render/Docker)
        server.createContext("/api/relay/status/health", new HealthCheckHandler());
        
        // Peer discovery endpoints
        server.createContext("/api/peers/register", new PeerRegisterHandler());
        server.createContext("/api/peers/list", new PeerListHandler());
        server.createContext("/api/peers/heartbeat", new PeerHeartbeatHandler());
        
        // File search endpoints
        server.createContext("/api/files/register", new FileRegisterHandler());
        server.createContext("/api/files/search", new FileSearchHandler());
        
        // PIN endpoints cho Quick Share
        server.createContext("/api/pin/create", new PINCreateHandler());
        server.createContext("/api/pin/find", new PINFindHandler());
        
        // Executor
        server.setExecutor(Executors.newCachedThreadPool());
        
        // Start
        server.start();
        running = true;
        
        // Schedule cleanup job (mỗi 10 phút)
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredFiles, 10, 10, TimeUnit.MINUTES);
        
        // Schedule peer cleanup (mỗi 30 giây)
        cleanupExecutor.scheduleAtFixedRate(() -> peerRegistry.cleanupExpiredPeers(), 30, 30, TimeUnit.SECONDS);
        
        LOGGER.info("✅ Relay Server đã khởi động tại cổng " + port);
        LOGGER.info("📁 Thư mục lưu trữ: " + storageDir.toAbsolutePath());
        LOGGER.info("⏱ Thời gian hết hạn mặc định: " + (defaultExpiryMs / 1000 / 60) + " phút");
    }
    
    /**
     * Dừng server
     */
    public void stop() {
        if (!running) return;
        
        running = false;
        if (server != null) {
            server.stop(0);
        }
        cleanupExecutor.shutdown();
        
        LOGGER.info("⛔ Relay Server đã dừng");
    }
    
    /**
     * Handler cho upload
     */
    private class UploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            
            try {
                // Đọc headers
                String uploadId = exchange.getRequestHeaders().getFirst("X-Upload-Id");
                String fileName = exchange.getRequestHeaders().getFirst("X-File-Name");
                String chunkIndexStr = exchange.getRequestHeaders().getFirst("X-Chunk-Index");
                
                if (uploadId == null || fileName == null || chunkIndexStr == null) {
                    sendResponse(exchange, 400, "Missing headers");
                    return;
                }
                
                int chunkIndex = Integer.parseInt(chunkIndexStr);
                
                // Tạo hoặc lấy session
                UploadSession session = uploads.computeIfAbsent(uploadId, id -> {
                    UploadSession s = new UploadSession(id, fileName);
                    s.filePath = storageDir.resolve(uploadId + "_" + fileName);
                    s.expiryTime = System.currentTimeMillis() + defaultExpiryMs;
                    LOGGER.info("📤 Bắt đầu upload mới: " + uploadId + " - " + fileName);
                    return s;
                });
                
                // Đọc chunk data
                byte[] chunkData = exchange.getRequestBody().readAllBytes();
                
                // Ghi chunk vào file
                appendChunk(session.filePath, chunkData);
                session.receivedChunks.add(chunkIndex);
                session.uploadedSize += chunkData.length;
                
                LOGGER.fine(String.format("✓ Nhận chunk %d của %s (%.1f KB)", 
                           chunkIndex, fileName, chunkData.length / 1024.0));
                
                // Response
                String response = String.format("{\"uploadId\":\"%s\",\"chunkIndex\":%d,\"status\":\"ok\"}", 
                                              uploadId, chunkIndex);
                sendResponse(exchange, 200, response);
                
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "❌ Lỗi upload: " + e.getMessage(), e);
                sendResponse(exchange, 500, "Upload failed: " + e.getMessage());
            }
        }
    }
    
    /**
     * Handler cho download
     */
    private class DownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            
            try {
                // Parse upload ID từ path: /api/relay/download/{uploadId}
                String path = exchange.getRequestURI().getPath();
                String[] parts = path.split("/");
                if (parts.length < 5) {
                    sendResponse(exchange, 400, "Invalid path");
                    return;
                }
                
                String uploadId = parts[4];
                UploadSession session = uploads.get(uploadId);
                
                if (session == null || !Files.exists(session.filePath)) {
                    sendResponse(exchange, 404, "File not found");
                    return;
                }
                
                if (session.isExpired()) {
                    sendResponse(exchange, 410, "File expired");
                    return;
                }
                
                LOGGER.info("📥 Download file: " + uploadId + " - " + session.fileName);
                
                // Support Range header cho resume
                String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
                long fileSize = Files.size(session.filePath);
                long startByte = 0;
                
                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    String[] range = rangeHeader.substring(6).split("-");
                    startByte = Long.parseLong(range[0]);
                    exchange.sendResponseHeaders(206, fileSize - startByte); // Partial Content
                    LOGGER.fine("📍 Resume download từ byte " + startByte);
                } else {
                    exchange.sendResponseHeaders(200, fileSize);
                }
                
                // Gửi file
                try (OutputStream os = exchange.getResponseBody();
                     FileInputStream fis = new FileInputStream(session.filePath.toFile())) {
                    
                    if (startByte > 0) {
                        fis.skip(startByte);
                    }
                    
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                }
                
                LOGGER.info("✅ Download hoàn thành: " + session.fileName);
                
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "❌ Lỗi download: " + e.getMessage(), e);
                sendResponse(exchange, 500, "Download failed: " + e.getMessage());
            }
        }
    }
    
    /**
     * Handler cho status check
     */
    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            
            try {
                String path = exchange.getRequestURI().getPath();
                String[] parts = path.split("/");
                if (parts.length < 5) {
                    sendResponse(exchange, 400, "Invalid path");
                    return;
                }
                
                String uploadId = parts[4];
                UploadSession session = uploads.get(uploadId);
                
                if (session == null) {
                    sendResponse(exchange, 404, "Upload not found");
                    return;
                }
                
                String response = String.format(
                    "{\"uploadId\":\"%s\",\"fileName\":\"%s\",\"uploadedSize\":%d,\"chunks\":%d,\"expired\":%b,\"complete\":%b}",
                    session.uploadId, session.fileName, session.uploadedSize, 
                    session.receivedChunks.size(), session.isExpired(), session.isComplete()
                );
                
                sendResponse(exchange, 200, response);
                
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "❌ Lỗi status: " + e.getMessage(), e);
                sendResponse(exchange, 500, "Status check failed");
            }
        }
    }
    
    /**
     * Ghi chunk vào file
     */
    private void appendChunk(Path filePath, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(filePath.toFile(), true)) {
            fos.write(data);
        }
    }
    
    /**
     * Gửi response
     */
    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] bytes = response.getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
    
    /**
     * Cleanup file hết hạn
     */
    private void cleanupExpiredFiles() {
        try {
            int cleanedCount = 0;
            Iterator<Map.Entry<String, UploadSession>> iterator = uploads.entrySet().iterator();
            
            while (iterator.hasNext()) {
                Map.Entry<String, UploadSession> entry = iterator.next();
                UploadSession session = entry.getValue();
                
                if (session.isExpired()) {
                    try {
                        Files.deleteIfExists(session.filePath);
                        iterator.remove();
                        cleanedCount++;
                        LOGGER.fine("🗑 Đã xóa file hết hạn: " + session.fileName);
                    } catch (IOException e) {
                        LOGGER.warning("⚠ Không thể xóa file: " + session.fileName);
                    }
                }
            }
            
            if (cleanedCount > 0) {
                LOGGER.info("🧹 Cleanup: Đã xóa " + cleanedCount + " file hết hạn");
            }
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "❌ Lỗi cleanup: " + e.getMessage(), e);
        }
    }
    
    /**
     * Handler cho peer registration (POST /api/peers/register)
     * Body: JSON {peerId, displayName, publicIp, port, publicKey}
     */
    private class PeerRegisterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                
                // Parse JSON manually (simple approach)
                String peerId = extractJsonValue(body, "peerId");
                String displayName = extractJsonValue(body, "displayName");
                String publicIp = extractJsonValue(body, "publicIp");
                int port = Integer.parseInt(extractJsonValue(body, "port"));
                String publicKey = extractJsonValue(body, "publicKey");
                
                // Nếu publicIp là "auto", dùng IP của client
                if ("auto".equals(publicIp)) {
                    // Ưu tiên lấy từ X-Forwarded-For header (khi server sau proxy/load balancer)
                    String forwardedFor = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
                    if (forwardedFor != null && !forwardedFor.isEmpty()) {
                        // X-Forwarded-For có thể chứa nhiều IP: "client, proxy1, proxy2"
                        // Lấy IP đầu tiên (IP của client thực)
                        publicIp = forwardedFor.split(",")[0].trim();
                    } else {
                        // Fallback: Lấy từ remote address
                        publicIp = exchange.getRemoteAddress().getAddress().getHostAddress();
                    }
                }
                
                peerRegistry.registerPeer(peerId, displayName, publicIp, port, publicKey);
                
                String response = "{\"success\":true,\"message\":\"Peer registered\",\"publicIp\":\"" + publicIp + "\"}";
                sendJsonResponse(exchange, 200, response);
                
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Lỗi register peer: " + e.getMessage(), e);
                sendResponse(exchange, 400, "Bad Request: " + e.getMessage());
            }
        }
    }
    
    /**
     * Handler cho peer list (GET /api/peers/list?peerId=xxx)
     */
    private class PeerListHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            
            try {
                String query = exchange.getRequestURI().getQuery();
                String requestingPeerId = null;
                
                if (query != null) {
                    String[] params = query.split("&");
                    for (String param : params) {
                        if (param.startsWith("peerId=")) {
                            requestingPeerId = param.substring(7);
                        }
                    }
                }
                
                List<org.example.p2psharefile.model.PeerInfo> peers;
                if (requestingPeerId != null) {
                    peers = peerRegistry.getPeersExcluding(requestingPeerId);
                } else {
                    peers = peerRegistry.getAllPeers();
                }
                
                // Build JSON response
                StringBuilder json = new StringBuilder("{\"peers\":[");
                for (int i = 0; i < peers.size(); i++) {
                    if (i > 0) json.append(",");
                    org.example.p2psharefile.model.PeerInfo peer = peers.get(i);
                    json.append("{")
                        .append("\"peerId\":\"").append(peer.getPeerId()).append("\",")
                        .append("\"displayName\":\"").append(peer.getDisplayName()).append("\",")
                        .append("\"ipAddress\":\"").append(peer.getIpAddress()).append("\",")
                        .append("\"port\":").append(peer.getPort()).append(",")
                        .append("\"publicKey\":\"").append(peer.getPublicKey()).append("\"")
                        .append("}");
                }
                json.append("],\"count\":").append(peers.size()).append("}");
                
                sendJsonResponse(exchange, 200, json.toString());
                
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Lỗi list peers: " + e.getMessage(), e);
                sendResponse(exchange, 500, "Internal Server Error");
            }
        }
    }
    
    /**
     * Handler cho heartbeat (POST /api/peers/heartbeat?peerId=xxx)
     */
    private class PeerHeartbeatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            
            try {
                String query = exchange.getRequestURI().getQuery();
                String peerId = null;
                
                if (query != null && query.startsWith("peerId=")) {
                    peerId = query.substring(7);
                }
                
                if (peerId == null) {
                    sendResponse(exchange, 400, "Missing peerId");
                    return;
                }
                
                peerRegistry.heartbeat(peerId);
                sendJsonResponse(exchange, 200, "{\"success\":true}");
                
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Lỗi heartbeat: " + e.getMessage(), e);
                sendResponse(exchange, 500, "Internal Server Error");
            }
        }
    }
    
    /**
     * Helper: Send JSON response
     */
    private void sendJsonResponse(HttpExchange exchange, int statusCode, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        byte[] response = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }
    
    /**
     * Helper: Extract JSON value (simple parser)
     */
    private String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]+)\"";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        // Try without quotes (for numbers)
        pattern = "\"" + key + "\"\\s*:\\s*([0-9]+)";
        p = java.util.regex.Pattern.compile(pattern);
        m = p.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
    
    /**
     * Health check handler - Để check server có sống không (cho Render/Docker)
     */
    private class HealthCheckHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            
            try {
                // Tạo health check response
                long uptime = System.currentTimeMillis();
                int activePeers = peerRegistry != null ? peerRegistry.getActivePeerCount() : 0;
                int activeUploads = uploads != null ? uploads.size() : 0;
                
                String response = String.format(
                    "{\"status\":\"healthy\",\"uptime\":%d,\"activePeers\":%d,\"activeUploads\":%d,\"timestamp\":%d}",
                    uptime, activePeers, activeUploads, System.currentTimeMillis()
                );
                
                sendResponse(exchange, 200, response);
                
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "❌ Health check error: " + e.getMessage(), e);
                sendResponse(exchange, 500, "{\"status\":\"unhealthy\",\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }
    
    // ========== FILE SEARCH HANDLERS ==========
    
    /**
     * Handler đăng ký file vào registry (POST /api/files/register)
     * Body: JSON {uploadId, fileName, fileSize, fileHash, senderId, senderName}
     */
    private class FileRegisterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                
                String uploadId = extractJsonValue(body, "uploadId");
                String fileName = extractJsonValue(body, "fileName");
                String fileSizeStr = extractJsonValue(body, "fileSize");
                String fileHash = extractJsonValue(body, "fileHash");
                String senderId = extractJsonValue(body, "senderId");
                String senderName = extractJsonValue(body, "senderName");
                
                if (uploadId == null || fileName == null) {
                    sendResponse(exchange, 400, "Missing required fields");
                    return;
                }
                
                long fileSize = fileSizeStr != null ? Long.parseLong(fileSizeStr) : 0;
                String downloadUrl = "/api/relay/download/" + uploadId;
                
                FileEntry entry = new FileEntry(uploadId, fileName, fileSize, fileHash,
                                               senderId, senderName, downloadUrl, 
                                               System.currentTimeMillis() + defaultExpiryMs);
                fileRegistry.put(uploadId, entry);
                
                LOGGER.info("📝 File registered: " + fileName + " (ID: " + uploadId + ")");
                
                sendJsonResponse(exchange, 200, "{\"success\":true,\"uploadId\":\"" + uploadId + "\"}");
                
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Lỗi register file: " + e.getMessage(), e);
                sendResponse(exchange, 400, "Bad Request: " + e.getMessage());
            }
        }
    }
    
    /**
     * Handler tìm kiếm file (GET /api/files/search?q=xxx)
     */
    private class FileSearchHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            
            try {
                String query = exchange.getRequestURI().getQuery();
                String searchQuery = "";
                String excludeSenderId = null;
                
                if (query != null) {
                    String[] params = query.split("&");
                    for (String param : params) {
                        if (param.startsWith("q=")) {
                            searchQuery = java.net.URLDecoder.decode(param.substring(2), "UTF-8").toLowerCase();
                        } else if (param.startsWith("excludeSender=")) {
                            excludeSenderId = param.substring(14);
                        }
                    }
                }
                
                // Tìm kiếm file trong registry
                List<FileEntry> results = new ArrayList<>();
                for (FileEntry entry : fileRegistry.values()) {
                    if (entry.isExpired()) continue;
                    if (excludeSenderId != null && excludeSenderId.equals(entry.senderId)) continue;
                    
                    if (searchQuery.isEmpty() || entry.fileName.toLowerCase().contains(searchQuery)) {
                        results.add(entry);
                    }
                }
                
                // Build JSON response
                StringBuilder json = new StringBuilder("{\"files\":[");
                for (int i = 0; i < results.size(); i++) {
                    if (i > 0) json.append(",");
                    FileEntry f = results.get(i);
                    json.append("{")
                        .append("\"uploadId\":\"").append(f.uploadId).append("\",")
                        .append("\"fileName\":\"").append(f.fileName).append("\",")
                        .append("\"fileSize\":").append(f.fileSize).append(",")
                        .append("\"fileHash\":\"").append(f.fileHash != null ? f.fileHash : "").append("\",")
                        .append("\"senderId\":\"").append(f.senderId != null ? f.senderId : "").append("\",")
                        .append("\"senderName\":\"").append(f.senderName != null ? f.senderName : "").append("\",")
                        .append("\"downloadUrl\":\"").append(f.downloadUrl).append("\"")
                        .append("}");
                }
                json.append("],\"count\":").append(results.size()).append("}");
                
                LOGGER.info("🔍 File search: \"" + searchQuery + "\" -> " + results.size() + " results");
                sendJsonResponse(exchange, 200, json.toString());
                
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Lỗi search files: " + e.getMessage(), e);
                sendResponse(exchange, 500, "Internal Server Error");
            }
        }
    }
    
    // ========== PIN HANDLERS ==========
    
    /**
     * Handler tạo PIN (POST /api/pin/create)
     * Body: JSON {pin, uploadId, fileName, fileSize, fileHash, senderId, senderName, downloadUrl}
     */
    private class PINCreateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                
                String pin = extractJsonValue(body, "pin");
                String uploadId = extractJsonValue(body, "uploadId");
                String fileName = extractJsonValue(body, "fileName");
                String fileSizeStr = extractJsonValue(body, "fileSize");
                String fileHash = extractJsonValue(body, "fileHash");
                String senderId = extractJsonValue(body, "senderId");
                String senderName = extractJsonValue(body, "senderName");
                String downloadUrl = extractJsonValue(body, "downloadUrl");
                String expiryStr = extractJsonValue(body, "expiryMs");
                
                if (pin == null || uploadId == null || fileName == null) {
                    sendResponse(exchange, 400, "Missing required fields");
                    return;
                }
                
                long fileSize = fileSizeStr != null ? Long.parseLong(fileSizeStr) : 0;
                long expiryMs = expiryStr != null ? Long.parseLong(expiryStr) : 600000; // Default 10 minutes
                
                PINSession session = new PINSession(pin, uploadId, fileName, fileSize, fileHash,
                                                   senderId, senderName, downloadUrl,
                                                   System.currentTimeMillis() + expiryMs);
                pinRegistry.put(pin, session);
                
                LOGGER.info("📌 PIN created: " + pin + " for file: " + fileName);
                
                sendJsonResponse(exchange, 200, "{\"success\":true,\"pin\":\"" + pin + "\"}");
                
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Lỗi create PIN: " + e.getMessage(), e);
                sendResponse(exchange, 400, "Bad Request: " + e.getMessage());
            }
        }
    }
    
    /**
     * Handler tìm PIN (GET /api/pin/find?pin=xxx)
     */
    private class PINFindHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            
            try {
                String query = exchange.getRequestURI().getQuery();
                String pin = null;
                
                if (query != null && query.startsWith("pin=")) {
                    pin = query.substring(4);
                }
                
                if (pin == null) {
                    sendResponse(exchange, 400, "Missing pin parameter");
                    return;
                }
                
                PINSession session = pinRegistry.get(pin);
                
                if (session == null) {
                    sendJsonResponse(exchange, 404, "{\"found\":false,\"error\":\"PIN not found\"}");
                    return;
                }
                
                if (session.isExpired()) {
                    pinRegistry.remove(pin);
                    sendJsonResponse(exchange, 410, "{\"found\":false,\"error\":\"PIN expired\"}");
                    return;
                }
                
                // Return PIN session info
                String json = String.format(
                    "{\"found\":true,\"pin\":\"%s\",\"uploadId\":\"%s\",\"fileName\":\"%s\"," +
                    "\"fileSize\":%d,\"fileHash\":\"%s\",\"senderId\":\"%s\",\"senderName\":\"%s\"," +
                    "\"downloadUrl\":\"%s\"}",
                    session.pin, session.uploadId, session.fileName, session.fileSize,
                    session.fileHash != null ? session.fileHash : "",
                    session.senderId != null ? session.senderId : "",
                    session.senderName != null ? session.senderName : "",
                    session.downloadUrl != null ? session.downloadUrl : ""
                );
                
                LOGGER.info("📌 PIN found: " + pin + " -> " + session.fileName);
                sendJsonResponse(exchange, 200, json);
                
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Lỗi find PIN: " + e.getMessage(), e);
                sendResponse(exchange, 500, "Internal Server Error");
            }
        }
    }
    
    /**
     * Main method để chạy standalone
     */
    public static void main(String[] args) {
        try {
            int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
            Path storageDir = Paths.get(args.length > 1 ? args[1] : "relay-storage");
            long expiryMs = 24 * 60 * 60 * 1000; // 24 giờ
            
            RelayServer server = new RelayServer(port, storageDir, expiryMs);
            server.start();
            
            LOGGER.info("\n" + "=".repeat(60));
            LOGGER.info("  RELAY SERVER - P2PShareFile");
            LOGGER.info("=".repeat(60));
            LOGGER.info("✅ Server đang chạy tại: http://localhost:" + port);
            LOGGER.info("📁 Thư mục lưu trữ: " + storageDir.toAbsolutePath());
            LOGGER.info("\nEndpoints:");
            LOGGER.info("  • POST   http://localhost:" + port + "/api/relay/upload");
            LOGGER.info("  • GET    http://localhost:" + port + "/api/relay/download/:uploadId");
            LOGGER.info("  • GET    http://localhost:" + port + "/api/relay/status/:uploadId");
            LOGGER.info("\nNhấn Ctrl+C để dừng server...\n");
            
            // Keep running
            Thread.currentThread().join();
            
        } catch (Exception e) {
            System.err.println("❌ Lỗi khởi động server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
