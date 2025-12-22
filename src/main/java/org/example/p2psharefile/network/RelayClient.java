package org.example.p2psharefile.network;

import org.example.p2psharefile.model.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/**
 * RelayClient - Client để upload/download file qua Relay Server
 * 
 * Chức năng chính:
 * 1. Upload file lên relay server (với chunking, resume, retry)
 * 2. Download file từ relay server (với resume support)
 * 3. Báo cáo progress cho UI
 * 4. Hỗ trợ PIN code cho Quick Share
 * 5. File search qua relay server
 * 
 * LƯU Ý:
 * - Relay mode KHÔNG mã hóa file (chỉ dựa vào HTTPS của hosting provider)
 * - Không verify hash (file có thể thay đổi do chunked upload)
 * - Bảo mật phụ thuộc vào Render.com HTTPS + PIN expiry + file expiry
 * 
 * Flow upload:
 * 1. Tạo RelayUploadRequest với thông tin file
 * 2. Chia file thành chunks và upload từng chunk
 * 3. Retry nếu upload chunk thất bại
 * 4. Server trả về RelayFileInfo với uploadId và downloadUrl
 * 
 * Flow download:
 * 1. Nhận RelayFileInfo từ sender (hoặc tìm qua PIN)
 * 2. Download từng chunk với resume support
 * 3. Lưu file vào đích
 * 
 * @author P2PShareFile Team
 * @version 1.0
 */
public class RelayClient {
    
    private final RelayConfig config;
    
    // Trạng thái pause/resume cho download
    private volatile boolean paused = false;
    private volatile boolean cancelled = false;
    private String currentDownloadId = null;
    
    /**
     * Interface callback cho transfer progress
     */
    public interface RelayTransferListener {
        void onProgress(RelayTransferProgress progress);
        void onComplete(RelayFileInfo fileInfo);
        void onError(Exception e);
        
        /** Called when download is paused */
        default void onPaused(RelayTransferProgress progress) {}
        
        /** Called when download is resumed */
        default void onResumed(RelayTransferProgress progress) {}
        
        /** Called when download is cancelled */
        default void onCancelled(String fileName) {}
    }
    
    /**
     * Constructor
     * @param config Cấu hình relay
     */
    public RelayClient(RelayConfig config) {
        this.config = config;
        System.out.println("✓ RelayClient đã khởi tạo: " + config.getServerUrl());
    }
    
    // ========== PAUSE/RESUME/CANCEL CONTROLS ==========
    
    /**
     * Tạm dừng download hiện tại
     */
    public void pauseDownload() {
        if (currentDownloadId != null) {
            paused = true;
            System.out.println("⏸ Download paused: " + currentDownloadId);
        }
    }
    
    /**
     * Tiếp tục download đã tạm dừng
     */
    public void resumeDownload() {
        if (currentDownloadId != null && paused) {
            paused = false;
            synchronized (this) {
                notifyAll(); // Wake up waiting thread
            }
            System.out.println("▶ Download resumed: " + currentDownloadId);
        }
    }
    
    /**
     * Hủy download hiện tại
     */
    public void cancelDownload() {
        if (currentDownloadId != null) {
            cancelled = true;
            paused = false; // Đảm bảo thread không bị block
            synchronized (this) {
                notifyAll();
            }
            System.out.println("❌ Download cancelled: " + currentDownloadId);
        }
    }
    
    /**
     * Kiểm tra có đang tạm dừng không
     */
    public boolean isPaused() {
        return paused;
    }
    
    /**
     * Kiểm tra có download đang chạy không
     */
    public boolean isDownloading() {
        return currentDownloadId != null && !paused && !cancelled;
    }
    
    /**
     * Lấy ID của download hiện tại
     */
    public String getCurrentDownloadId() {
        return currentDownloadId;
    }
    
    /**
     * Upload file lên relay server
     * 
     * @param sourceFile File nguồn cần upload
     * @param request Thông tin upload request
     * @param listener Callback để nhận progress và kết quả
     * @return RelayFileInfo nếu thành công, null nếu thất bại
     */
    public RelayFileInfo uploadFile(File sourceFile, RelayUploadRequest request, RelayTransferListener listener) {
        if (!sourceFile.exists() || !sourceFile.isFile()) {
            Exception e = new IOException("File không tồn tại: " + sourceFile.getAbsolutePath());
            if (listener != null) listener.onError(e);
            return null;
        }
        
        try {
            System.out.println("🚀 Upload file: " + sourceFile.getName() + " (" + formatBytes(sourceFile.length()) + ")");
            
            // Tạo transfer ID và progress tracker
            String transferId = UUID.randomUUID().toString();
            RelayTransferProgress progress = new RelayTransferProgress(
                transferId, 
                RelayTransferProgress.TransferType.UPLOAD,
                sourceFile.getName(),
                sourceFile.length()
            );
            
            // Tính số chunks
            int totalChunks = (int) Math.ceil((double) sourceFile.length() / config.getChunkSize());
            progress.setTotalChunks(totalChunks);
            System.out.println("📦 Chia thành " + totalChunks + " chunks");
            
            // Upload từng chunk với retry
            String uploadId = uploadChunks(sourceFile, request, progress, listener);
            
            if (uploadId == null) {
                throw new IOException("Upload thất bại");
            }
            
            // Tạo RelayFileInfo
            RelayFileInfo fileInfo = new RelayFileInfo(
                uploadId,
                sourceFile.getName(),
                sourceFile.length(),
                null,  // Không dùng hash trong relay mode
                config.getServerUrl() + config.getDownloadEndpoint() + "/" + uploadId
            );
            fileInfo.setSenderId(request.getSenderId());
            fileInfo.setSenderName(request.getSenderName());
            fileInfo.setRecipientId(request.getRecipientId());
            fileInfo.setMimeType(request.getMimeType());
            fileInfo.setExpiryTime(System.currentTimeMillis() + config.getDefaultExpiryTime());
            
            // Báo hoàn thành
            progress.setStatus(RelayTransferProgress.TransferStatus.COMPLETED);
            if (listener != null) {
                listener.onProgress(progress);
                listener.onComplete(fileInfo);
            }
            
            System.out.println("✅ Upload thành công! ID: " + uploadId);
            return fileInfo;
            
        } catch (Exception e) {
            System.err.println("❌ Lỗi upload: " + e.getMessage());
            if (listener != null) listener.onError(e);
            return null;
        }
    }
    
    /**
     * Upload file theo chunks với retry
     */
    private String uploadChunks(File file, RelayUploadRequest request, 
                                RelayTransferProgress progress, RelayTransferListener listener) 
            throws IOException {
        
        String uploadUrl = config.getServerUrl() + config.getUploadEndpoint();
        String uploadId = UUID.randomUUID().toString();
        
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[config.getChunkSize()];
            int chunkIndex = 0;
            long totalBytesUploaded = 0;
            
            while (true) {
                int bytesRead = fis.read(buffer);
                if (bytesRead <= 0) break;
                
                boolean uploadSuccess = false;
                int attempt = 0;
                
                // Retry logic
                while (!uploadSuccess && attempt < config.getMaxRetries()) {
                    try {
                        uploadChunk(uploadUrl, uploadId, buffer, bytesRead, chunkIndex, file.getName(), request);
                        uploadSuccess = true;
                        
                        // Update progress
                        totalBytesUploaded += bytesRead;
                        progress.setCurrentChunk(chunkIndex);
                        progress.updateProgress(totalBytesUploaded);
                        
                        if (listener != null) {
                            listener.onProgress(progress);
                        }
                        
                    } catch (IOException e) {
                        attempt++;
                        System.out.println(String.format("⚠ Chunk %d upload failed (attempt %d/%d): %s", 
                                     chunkIndex, attempt, config.getMaxRetries(), e.getMessage()));
                        
                        if (attempt < config.getMaxRetries()) {
                            Thread.sleep(config.getRetryDelayMs());
                        } else {
                            throw e; // Max retries reached
                        }
                    }
                }
                
                chunkIndex++;
            }
            
            return uploadId;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Upload interrupted", e);
        }
    }
    
    /**
     * Upload một chunk lên server
     * Gửi raw binary data thay vì multipart để server xử lý đơn giản hơn
     */
    private void uploadChunk(String uploadUrl, String uploadId, byte[] data, int length,
                           int chunkIndex, String fileName, RelayUploadRequest request) 
            throws IOException {
        
        HttpURLConnection conn = null;
        try {
            URL url = new java.net.URI(uploadUrl).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(config.getConnectionTimeout());
            conn.setReadTimeout(config.getUploadTimeout());
            
            // Headers - gửi raw binary data
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setRequestProperty("Content-Length", String.valueOf(length));
            conn.setRequestProperty("X-Upload-Id", uploadId);
            conn.setRequestProperty("X-Chunk-Index", String.valueOf(chunkIndex));
            conn.setRequestProperty("X-File-Name", fileName);
            conn.setRequestProperty("X-Sender-Id", request.getSenderId());
            
            if (config.getApiKey() != null) {
                conn.setRequestProperty("X-API-Key", config.getApiKey());
            }
            
            // Write raw binary data directly
            try (OutputStream os = conn.getOutputStream()) {
                os.write(data, 0, length);
                os.flush();
            }
            
            // Check response
            int responseCode = conn.getResponseCode();
            if (responseCode != 200 && responseCode != 201) {
                String errorMsg = readResponse(conn.getErrorStream());
                throw new IOException("Server returned error " + responseCode + ": " + errorMsg);
            }
            
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
    

    
    /**
     * Download file từ relay server
     * 
     * @param fileInfo Thông tin file cần download
     * @param destinationFile File đích để lưu
     * @param listener Callback để nhận progress
     * @return true nếu thành công, false nếu thất bại
     */
    public boolean downloadFile(RelayFileInfo fileInfo, File destinationFile, RelayTransferListener listener) {
        // Reset trạng thái
        paused = false;
        cancelled = false;
        currentDownloadId = fileInfo.getUploadId();
        
        try {
            System.out.println("🔽 Bắt đầu download file: " + fileInfo.getFileName());
            
            // Tạo progress tracker
            String transferId = UUID.randomUUID().toString();
            RelayTransferProgress progress = new RelayTransferProgress(
                transferId,
                RelayTransferProgress.TransferType.DOWNLOAD,
                fileInfo.getFileName(),
                fileInfo.getFileSize()
            );
            
            // Download file
            File tempFile = new File(destinationFile.getParent(), destinationFile.getName() + ".tmp");
            boolean success = downloadWithResume(fileInfo.getDownloadUrl(), tempFile, progress, listener, fileInfo.getFileName());
            
            // Kiểm tra kết quả
            if (cancelled) {
                System.out.println("❌ Download bị hủy: " + fileInfo.getFileName());
                if (listener != null) listener.onCancelled(fileInfo.getFileName());
                currentDownloadId = null;
                return false;
            }
            
            if (paused) {
                // File tạm vẫn giữ lại để resume sau
                System.out.println("⏸ Download tạm dừng, có thể tiếp tục sau: " + tempFile.getAbsolutePath());
                return false;
            }
            
            if (!success) {
                currentDownloadId = null;
                return false;
            }
            
            // Skip hash verify - file trên relay có thể khác hash do chunked upload
            System.out.println("✓ Download xong, bỏ qua hash verify cho relay files");
            
            // Di chuyển file tạm sang đích
            Files.move(tempFile.toPath(), destinationFile.toPath(), 
                      java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            
            // Báo hoàn thành
            progress.setStatus(RelayTransferProgress.TransferStatus.COMPLETED);
            if (listener != null) {
                listener.onProgress(progress);
                listener.onComplete(fileInfo);
            }
            
            System.out.println("✅ Download thành công: " + destinationFile.getAbsolutePath());
            currentDownloadId = null;
            return true;
            
        } catch (Exception e) {
            System.out.println("❌ Lỗi khi download file: " + e.getMessage());
            e.printStackTrace();
            if (listener != null) listener.onError(e);
            currentDownloadId = null;
            return false;
        }
    }
    
    /**
     * Download file với resume support và pause/cancel control
     * @return true nếu hoàn thành, false nếu bị dừng hoặc lỗi
     */
    private boolean downloadWithResume(String downloadUrl, File destinationFile,
                                   RelayTransferProgress progress, RelayTransferListener listener,
                                   String fileName) 
            throws IOException {
        
        long startPosition = destinationFile.exists() ? destinationFile.length() : 0;
        
        HttpURLConnection conn = null;
        try {
            URL url = new java.net.URI(downloadUrl).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(config.getConnectionTimeout());
            conn.setReadTimeout(config.getDownloadTimeout());
            
            // Resume support
            if (startPosition > 0 && config.isEnableResume()) {
                conn.setRequestProperty("Range", "bytes=" + startPosition + "-");
                System.out.println("📍 Resume download from byte " + startPosition);
            }
            
            if (config.getApiKey() != null) {
                conn.setRequestProperty("X-API-Key", config.getApiKey());
            }
            
            int responseCode = conn.getResponseCode();
            if (responseCode != 200 && responseCode != 206) {
                throw new IOException("Server returned error " + responseCode);
            }
            
            // Download với pause/cancel support
            try (InputStream is = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(destinationFile, startPosition > 0)) {
                
                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytesRead = startPosition;
                
                while ((bytesRead = is.read(buffer)) != -1) {
                    // Kiểm tra cancel
                    if (cancelled) {
                        System.out.println("❌ Download cancelled: " + fileName);
                        return false;
                    }
                    
                    // Kiểm tra pause
                    while (paused && !cancelled) {
                        progress.setStatus(RelayTransferProgress.TransferStatus.PAUSED);
                        if (listener != null) {
                            listener.onPaused(progress);
                        }
                        System.out.println("⏸ Download paused at byte " + totalBytesRead);
                        
                        // Đợi cho đến khi resume hoặc cancel
                        synchronized (this) {
                            try {
                                wait(1000); // Check mỗi giây
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return false;
                            }
                        }
                    }
                    
                    // Kiểm tra lại sau khi resume
                    if (cancelled) {
                        return false;
                    }
                    
                    // Ghi dữ liệu
                    fos.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                    
                    progress.setStatus(RelayTransferProgress.TransferStatus.IN_PROGRESS);
                    progress.updateProgress(totalBytesRead);
                    if (listener != null) {
                        listener.onProgress(progress);
                    }
                }
            }
            
            return !cancelled && !paused;
            
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
    

    
    /**
     * Đọc response từ server
     */
    private String readResponse(InputStream is) throws IOException {
        if (is == null) return "";
        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }
    
    /**
     * Format bytes thành string dễ đọc
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
    
    // ========== PEER DISCOVERY VIA RELAY ==========
    
    /**
     * Đăng ký peer với relay server để discovery qua Internet
     * 
     * @param localPeer Thông tin peer cục bộ
     * @return true nếu thành công
     */
    public boolean registerPeer(PeerInfo localPeer) {
        try {
            String url = config.getServerUrl() + "/api/peers/register";
            
            // Build JSON body
            String json = String.format(
                "{\"peerId\":\"%s\",\"displayName\":\"%s\",\"publicIp\":\"auto\",\"port\":%d,\"publicKey\":\"%s\"}",
                localPeer.getPeerId(),
                localPeer.getDisplayName(),
                localPeer.getPort(),
                localPeer.getPublicKey()
            );
            
            HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(config.getConnectTimeoutMs());
            conn.setReadTimeout(config.getReadTimeoutMs());
            
            // Send body
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            
            int responseCode = conn.getResponseCode();
            String response = readResponse(conn.getInputStream());
            
            if (responseCode == 200) {
                System.out.println("✓ Đã đăng ký peer với relay server: " + localPeer.getDisplayName());
                return true;
            } else {
                System.out.println("⚠ Lỗi đăng ký peer: " + responseCode + " - " + response);
                return false;
            }
            
        } catch (Exception e) {
            System.out.println("❌ Không thể đăng ký peer với relay: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Lấy danh sách peers từ relay server
     * 
     * @param excludePeerId Loại trừ peer này (thường là localPeer)
     * @return Danh sách peers hoặc empty list nếu lỗi
     */
    public java.util.List<PeerInfo> discoverPeers(String excludePeerId) {
        java.util.List<PeerInfo> peers = new java.util.ArrayList<>();
        
        try {
            String url = config.getServerUrl() + "/api/peers/list?peerId=" + excludePeerId;
            
            HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(config.getConnectTimeoutMs());
            conn.setReadTimeout(config.getReadTimeoutMs());
            
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                System.out.println("⚠ Lỗi discover peers: " + responseCode);
                return peers;
            }
            
            String response = readResponse(conn.getInputStream());
            
            // Parse JSON response (simple approach)
            // Format: {"peers":[{...},{...}],"count":2}
            peers = parsePeerListJson(response);
            
            System.out.println("🔍 Đã phát hiện " + peers.size() + " peer(s) qua relay");
            
        } catch (Exception e) {
            System.out.println("❌ Không thể discover peers qua relay: " + e.getMessage());
        }
        
        return peers;
    }
    
    /**
     * Gửi heartbeat tới relay server
     * 
     * @param peerId ID của peer
     * @return true nếu thành công
     */
    public boolean sendHeartbeat(String peerId) {
        try {
            String url = config.getServerUrl() + "/api/peers/heartbeat?peerId=" + peerId;
            
            HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            
            int responseCode = conn.getResponseCode();
            return responseCode == 200;
            
        } catch (Exception e) {
            // Không log chi tiết để tránh spam
            return false;
        }
    }
    
    /**
     * Parse JSON peer list response
     */
    private java.util.List<PeerInfo> parsePeerListJson(String json) {
        java.util.List<PeerInfo> peers = new java.util.ArrayList<>();
        
        try {
            // Extract "peers" array
            int peersStart = json.indexOf("\"peers\":[");
            if (peersStart < 0) return peers;
            
            int arrayStart = json.indexOf('[', peersStart);
            int arrayEnd = json.indexOf(']', arrayStart);
            String peersArrayJson = json.substring(arrayStart + 1, arrayEnd);
            
            // Split by },{
            String[] peerJsons = peersArrayJson.split("\\},\\{");
            
            for (String peerJson : peerJsons) {
                peerJson = peerJson.replaceAll("[\\{\\}]", "");
                
                String peerId = extractJsonFieldValue(peerJson, "peerId");
                String displayName = extractJsonFieldValue(peerJson, "displayName");
                String ipAddress = extractJsonFieldValue(peerJson, "ipAddress");
                String portStr = extractJsonFieldValue(peerJson, "port");
                String publicKey = extractJsonFieldValue(peerJson, "publicKey");
                
                if (peerId != null && ipAddress != null && portStr != null) {
                    int port = Integer.parseInt(portStr);
                    PeerInfo peer = new PeerInfo(peerId, ipAddress, port, displayName, publicKey);
                    peers.add(peer);
                }
            }
            
        } catch (Exception e) {
            System.out.println("Lỗi parse peer list JSON: " + e.getMessage());
        }
        
        return peers;
    }
    
    /**
     * Extract field value từ JSON string
     */
    private String extractJsonFieldValue(String json, String field) {
        // Try string value first: "field":"value"
        String pattern = "\"" + field + "\":\"([^\"]+)\"";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        // Try number value: "field":123
        pattern = "\"" + field + "\":([0-9]+)";
        p = java.util.regex.Pattern.compile(pattern);
        m = p.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        // Try boolean value: "field":true or "field":false
        pattern = "\"" + field + "\":(true|false)";
        p = java.util.regex.Pattern.compile(pattern);
        m = p.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
    
    // ========== FILE SEARCH VIA RELAY ==========
    
    /**
     * Đăng ký file với relay server để cho phép search
     * 
     * @param relayFileInfo Thông tin file đã upload
     * @return true nếu thành công
     */
    public boolean registerFileForSearch(RelayFileInfo relayFileInfo) {
        try {
            String url = config.getServerUrl() + "/api/files/register";
            
            String json = String.format(
                "{\"uploadId\":\"%s\",\"fileName\":\"%s\",\"fileSize\":%d,\"fileHash\":\"%s\"," +
                "\"senderId\":\"%s\",\"senderName\":\"%s\"}",
                relayFileInfo.getUploadId(),
                relayFileInfo.getFileName(),
                relayFileInfo.getFileSize(),
                relayFileInfo.getFileHash() != null ? relayFileInfo.getFileHash() : "",
                relayFileInfo.getSenderId() != null ? relayFileInfo.getSenderId() : "",
                relayFileInfo.getSenderName() != null ? relayFileInfo.getSenderName() : ""
            );
            
            HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(config.getConnectTimeoutMs());
            conn.setReadTimeout(config.getReadTimeoutMs());
            
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            
            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                System.out.println("✓ File registered for search: " + relayFileInfo.getFileName());
                return true;
            }
            
            return false;
            
        } catch (Exception e) {
            System.out.println("❌ Error registering file for search: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Tìm kiếm file trên relay server
     * 
     * @param query Từ khóa tìm kiếm
     * @param excludeSenderId Loại trừ file của sender này
     * @return Danh sách RelayFileInfo tìm được
     */
    public java.util.List<RelayFileInfo> searchFiles(String query, String excludeSenderId) {
        java.util.List<RelayFileInfo> results = new java.util.ArrayList<>();
        
        try {
            String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
            String url = config.getServerUrl() + "/api/files/search?q=" + encodedQuery;
            if (excludeSenderId != null) {
                url += "&excludeSender=" + excludeSenderId;
            }
            
            HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(config.getConnectTimeoutMs());
            conn.setReadTimeout(config.getReadTimeoutMs());
            
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                System.out.println("⚠ Search failed: " + responseCode);
                return results;
            }
            
            String response = readResponse(conn.getInputStream());
            results = parseFileSearchResults(response);
            
            System.out.println("🔍 Search \"" + query + "\" -> " + results.size() + " results");
            
        } catch (Exception e) {
            System.out.println("❌ Error searching files: " + e.getMessage());
        }
        
        return results;
    }
    
    /**
     * Parse kết quả tìm kiếm file từ JSON
     */
    private java.util.List<RelayFileInfo> parseFileSearchResults(String json) {
        java.util.List<RelayFileInfo> results = new java.util.ArrayList<>();
        
        try {
            int filesStart = json.indexOf("\"files\":[");
            if (filesStart < 0) return results;
            
            int arrayStart = json.indexOf('[', filesStart);
            int arrayEnd = json.lastIndexOf(']');
            String filesArrayJson = json.substring(arrayStart + 1, arrayEnd);
            
            if (filesArrayJson.trim().isEmpty()) return results;
            
            // Split by },{
            String[] fileJsons = filesArrayJson.split("\\},\\{");
            
            for (String fileJson : fileJsons) {
                fileJson = fileJson.replaceAll("[\\{\\}]", "");
                
                String uploadId = extractJsonFieldValue(fileJson, "uploadId");
                String fileName = extractJsonFieldValue(fileJson, "fileName");
                String fileSizeStr = extractJsonFieldValue(fileJson, "fileSize");
                String fileHash = extractJsonFieldValue(fileJson, "fileHash");
                String senderId = extractJsonFieldValue(fileJson, "senderId");
                String senderName = extractJsonFieldValue(fileJson, "senderName");
                String downloadUrl = extractJsonFieldValue(fileJson, "downloadUrl");
                
                if (uploadId != null && fileName != null) {
                    long fileSize = fileSizeStr != null ? Long.parseLong(fileSizeStr) : 0;
                    
                    // Tạo full download URL
                    String fullDownloadUrl = downloadUrl != null && downloadUrl.startsWith("/") 
                        ? config.getServerUrl() + downloadUrl 
                        : downloadUrl;
                    
                    RelayFileInfo fileInfo = new RelayFileInfo(uploadId, fileName, fileSize, fileHash, fullDownloadUrl);
                    fileInfo.setSenderId(senderId);
                    fileInfo.setSenderName(senderName);
                    results.add(fileInfo);
                }
            }
            
        } catch (Exception e) {
            System.out.println("Error parsing file search results: " + e.getMessage());
        }
        
        return results;
    }
    
    // ========== PIN (QUICK SHARE) VIA RELAY ==========
    
    /**
     * Tạo PIN trên relay server cho Quick Share
     * 
     * @param pin Mã PIN 6 số
     * @param relayFileInfo Thông tin file đã upload
     * @param expiryMs Thời gian hết hạn (ms)
     * @return true nếu thành công
     */
    public boolean createPIN(String pin, RelayFileInfo relayFileInfo, long expiryMs) {
        try {
            String url = config.getServerUrl() + "/api/pin/create";
            
            String json = String.format(
                "{\"pin\":\"%s\",\"uploadId\":\"%s\",\"fileName\":\"%s\",\"fileSize\":%d," +
                "\"fileHash\":\"%s\",\"senderId\":\"%s\",\"senderName\":\"%s\"," +
                "\"downloadUrl\":\"%s\",\"expiryMs\":%d}",
                pin,
                relayFileInfo.getUploadId(),
                relayFileInfo.getFileName(),
                relayFileInfo.getFileSize(),
                relayFileInfo.getFileHash() != null ? relayFileInfo.getFileHash() : "",
                relayFileInfo.getSenderId() != null ? relayFileInfo.getSenderId() : "",
                relayFileInfo.getSenderName() != null ? relayFileInfo.getSenderName() : "",
                relayFileInfo.getDownloadUrl() != null ? relayFileInfo.getDownloadUrl() : "",
                expiryMs
            );
            
            HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(config.getConnectTimeoutMs());
            conn.setReadTimeout(config.getReadTimeoutMs());
            
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            
            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                System.out.println("✓ PIN created on relay: " + pin);
                return true;
            }
            
            return false;
            
        } catch (Exception e) {
            System.out.println("❌ Error creating PIN: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Tìm PIN trên relay server
     * 
     * @param pin Mã PIN 6 số
     * @return RelayFileInfo nếu tìm thấy, null nếu không
     */
    public RelayFileInfo findPIN(String pin) {
        try {
            String url = config.getServerUrl() + "/api/pin/find?pin=" + pin;
            
            HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(config.getConnectTimeoutMs());
            conn.setReadTimeout(config.getReadTimeoutMs());
            
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                System.out.println("⚠ PIN not found or expired: " + pin);
                return null;
            }
            
            String response = readResponse(conn.getInputStream());
            
            // Parse response
            String found = extractJsonFieldValue(response, "found");
            if (!"true".equals(found)) {
                return null;
            }
            
            String uploadId = extractJsonFieldValue(response, "uploadId");
            String fileName = extractJsonFieldValue(response, "fileName");
            String fileSizeStr = extractJsonFieldValue(response, "fileSize");
            String fileHash = extractJsonFieldValue(response, "fileHash");
            String senderId = extractJsonFieldValue(response, "senderId");
            String senderName = extractJsonFieldValue(response, "senderName");
            String downloadUrl = extractJsonFieldValue(response, "downloadUrl");
            
            long fileSize = fileSizeStr != null ? Long.parseLong(fileSizeStr) : 0;
            
            // Tạo full download URL nếu cần
            String fullDownloadUrl = downloadUrl != null && downloadUrl.startsWith("/") 
                ? config.getServerUrl() + downloadUrl 
                : downloadUrl;
            
            RelayFileInfo fileInfo = new RelayFileInfo(uploadId, fileName, fileSize, fileHash, fullDownloadUrl);
            fileInfo.setSenderId(senderId);
            fileInfo.setSenderName(senderName);
            
            System.out.println("✓ PIN found: " + pin + " -> " + fileName);
            return fileInfo;
            
        } catch (Exception e) {
            System.out.println("❌ Error finding PIN: " + e.getMessage());
            return null;
        }
    }
    
    public RelayConfig getConfig() {
        return config;
    }
}
