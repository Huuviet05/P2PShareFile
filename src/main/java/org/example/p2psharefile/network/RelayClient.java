package org.example.p2psharefile.network;

import org.example.p2psharefile.model.*;
import org.example.p2psharefile.security.AESEncryption;
import org.example.p2psharefile.security.FileHashUtil;

import javax.crypto.SecretKey;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * RelayClient - Client để upload/download file qua Relay Server
 * 
 * Chức năng chính:
 * 1. Upload file lên relay server (với chunking, resume, retry)
 * 2. Download file từ relay server (với chunking, resume, verify)
 * 3. Mã hóa file client-side trước khi upload (AES-GCM)
 * 4. Giải mã file sau khi download
 * 5. Tính hash để verify integrity
 * 6. Báo cáo progress cho UI
 * 
 * Flow upload:
 * 1. Tạo RelayUploadRequest với thông tin file
 * 2. (Optional) Mã hóa file nếu config.enableEncryption = true
 * 3. Chia file thành chunks và upload từng chunk
 * 4. Retry nếu upload chunk thất bại
 * 5. Server trả về RelayFileInfo với uploadId và downloadUrl
 * 
 * Flow download:
 * 1. Nhận RelayFileInfo từ sender
 * 2. Download từng chunk với resume support
 * 3. Verify hash sau khi download xong
 * 4. (Optional) Giải mã file nếu encrypted
 * 5. Lưu file vào đích
 * 
 * @author P2PShareFile Team
 * @version 1.0
 */
public class RelayClient {
    
    private static final Logger LOGGER = Logger.getLogger(RelayClient.class.getName());
    private static final String BOUNDARY = "----RelayUploadBoundary" + System.currentTimeMillis();
    
    private final RelayConfig config;
    private final SecretKey encryptionKey;
    
    /**
     * Interface callback cho transfer progress
     */
    public interface RelayTransferListener {
        void onProgress(RelayTransferProgress progress);
        void onComplete(RelayFileInfo fileInfo);
        void onError(Exception e);
    }
    
    /**
     * Constructor
     * @param config Cấu hình relay
     */
    public RelayClient(RelayConfig config) {
        this.config = config;
        // Tạo encryption key từ peer's private key hoặc shared secret
        this.encryptionKey = AESEncryption.createKeyFromString("RelayEncryptionKey123456789012"); // TODO: Use proper key
        
        if (config.isEnableLogging()) {
            LOGGER.setLevel(parseLogLevel(config.getLogLevel()));
            LOGGER.info("✓ RelayClient initialized: " + config);
        }
    }
    
    /**
     * Parse log level string sang java.util.logging.Level
     * Map các level phổ biến: DEBUG -> FINE, WARN -> WARNING
     */
    private Level parseLogLevel(String levelStr) {
        if (levelStr == null) return Level.INFO;
        
        return switch (levelStr.toUpperCase()) {
            case "DEBUG" -> Level.FINE;
            case "TRACE" -> Level.FINEST;
            case "WARN" -> Level.WARNING;
            case "ERROR" -> Level.SEVERE;
            default -> Level.parse(levelStr.toUpperCase());
        };
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
            LOGGER.info("🚀 Bắt đầu upload file: " + sourceFile.getName() + " (" + sourceFile.length() + " bytes)");
            
            // Tạo transfer ID và progress tracker
            String transferId = UUID.randomUUID().toString();
            RelayTransferProgress progress = new RelayTransferProgress(
                transferId, 
                RelayTransferProgress.TransferType.UPLOAD,
                sourceFile.getName(),
                sourceFile.length()
            );
            
            // Tính hash của file
            String fileHash = FileHashUtil.calculateSHA256(sourceFile);
            request.setFileHash(fileHash);
            LOGGER.info("📝 File hash (SHA-256): " + fileHash);
            
            // Mã hóa file nếu cần
            File fileToUpload = sourceFile;
            if (config.isEnableEncryption()) {
                LOGGER.info("🔐 Mã hóa file trước khi upload...");
                fileToUpload = encryptFile(sourceFile);
                request.setEncrypted(true);
                request.setEncryptionAlgorithm("AES-GCM-256");
            }
            
            // Tính số chunks
            int totalChunks = (int) Math.ceil((double) fileToUpload.length() / config.getChunkSize());
            progress.setTotalChunks(totalChunks);
            LOGGER.info("📦 Chia file thành " + totalChunks + " chunks (chunk size: " + 
                       formatBytes(config.getChunkSize()) + ")");
            
            // Upload từng chunk với retry
            String uploadId = uploadChunks(fileToUpload, request, progress, listener);
            
            if (uploadId == null) {
                throw new IOException("Upload thất bại");
            }
            
            // Tạo RelayFileInfo
            RelayFileInfo fileInfo = new RelayFileInfo(
                uploadId,
                sourceFile.getName(),
                sourceFile.length(),
                fileHash,
                config.getServerUrl() + config.getDownloadEndpoint() + "/" + uploadId
            );
            fileInfo.setSenderId(request.getSenderId());
            fileInfo.setSenderName(request.getSenderName());
            fileInfo.setRecipientId(request.getRecipientId());
            fileInfo.setEncrypted(request.isEncrypted());
            fileInfo.setEncryptionAlgorithm(request.getEncryptionAlgorithm());
            fileInfo.setMimeType(request.getMimeType());
            fileInfo.setExpiryTime(System.currentTimeMillis() + config.getDefaultExpiryTime());
            
            // Xóa file tạm nếu đã mã hóa
            if (config.isEnableEncryption() && fileToUpload != sourceFile) {
                fileToUpload.delete();
            }
            
            // Báo hoàn thành
            progress.setStatus(RelayTransferProgress.TransferStatus.COMPLETED);
            if (listener != null) {
                listener.onProgress(progress);
                listener.onComplete(fileInfo);
            }
            
            LOGGER.info("✅ Upload thành công! Upload ID: " + uploadId);
            return fileInfo;
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "❌ Lỗi khi upload file: " + e.getMessage(), e);
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
                        
                        LOGGER.fine(String.format("✓ Chunk %d/%d uploaded (%.1f%%)", 
                                   chunkIndex + 1, progress.getTotalChunks(), progress.getPercentage()));
                        
                    } catch (IOException e) {
                        attempt++;
                        LOGGER.warning(String.format("⚠ Chunk %d upload failed (attempt %d/%d): %s", 
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
            
            // Headers
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
            conn.setRequestProperty("X-Upload-Id", uploadId);
            conn.setRequestProperty("X-Chunk-Index", String.valueOf(chunkIndex));
            conn.setRequestProperty("X-File-Name", fileName);
            conn.setRequestProperty("X-Sender-Id", request.getSenderId());
            
            if (config.getApiKey() != null) {
                conn.setRequestProperty("X-API-Key", config.getApiKey());
            }
            
            // Write multipart body
            try (OutputStream os = conn.getOutputStream()) {
                writeMultipartData(os, data, length, chunkIndex);
            }
            
            // Check response
            int responseCode = conn.getResponseCode();
            if (responseCode != 200 && responseCode != 201) {
                String errorMsg = readResponse(conn.getErrorStream());
                throw new IOException("Server returned error " + responseCode + ": " + errorMsg);
            }
            
            LOGGER.finest("Chunk " + chunkIndex + " uploaded successfully");
            
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
    
    /**
     * Viết multipart form data
     */
    private void writeMultipartData(OutputStream os, byte[] data, int length, int chunkIndex) 
            throws IOException {
        
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), true);
        
        // Metadata
        writer.append("--").append(BOUNDARY).append("\r\n");
        writer.append("Content-Disposition: form-data; name=\"chunkIndex\"\r\n\r\n");
        writer.append(String.valueOf(chunkIndex)).append("\r\n");
        
        // File chunk
        writer.append("--").append(BOUNDARY).append("\r\n");
        writer.append("Content-Disposition: form-data; name=\"chunk\"; filename=\"chunk_")
              .append(String.valueOf(chunkIndex)).append("\"\r\n");
        writer.append("Content-Type: application/octet-stream\r\n\r\n");
        writer.flush();
        
        os.write(data, 0, length);
        os.flush();
        
        writer.append("\r\n");
        writer.append("--").append(BOUNDARY).append("--\r\n");
        writer.flush();
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
        try {
            LOGGER.info("🔽 Bắt đầu download file: " + fileInfo.getFileName());
            
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
            downloadWithResume(fileInfo.getDownloadUrl(), tempFile, progress, listener);
            
            // Verify hash
            String downloadedHash = FileHashUtil.calculateSHA256(tempFile);
            if (!downloadedHash.equals(fileInfo.getFileHash())) {
                throw new IOException("Hash không khớp! Expected: " + fileInfo.getFileHash() + 
                                    ", Got: " + downloadedHash);
            }
            LOGGER.info("✓ Hash verified: " + downloadedHash);
            
            // Giải mã nếu cần
            if (fileInfo.isEncrypted()) {
                LOGGER.info("🔓 Giải mã file...");
                File decryptedFile = decryptFile(tempFile);
                tempFile.delete();
                tempFile = decryptedFile;
            }
            
            // Di chuyển file tạm sang đích
            Files.move(tempFile.toPath(), destinationFile.toPath(), 
                      java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            
            // Báo hoàn thành
            progress.setStatus(RelayTransferProgress.TransferStatus.COMPLETED);
            if (listener != null) {
                listener.onProgress(progress);
                listener.onComplete(fileInfo);
            }
            
            LOGGER.info("✅ Download thành công: " + destinationFile.getAbsolutePath());
            return true;
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "❌ Lỗi khi download file: " + e.getMessage(), e);
            if (listener != null) listener.onError(e);
            return false;
        }
    }
    
    /**
     * Download file với resume support
     */
    private void downloadWithResume(String downloadUrl, File destinationFile,
                                   RelayTransferProgress progress, RelayTransferListener listener) 
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
                LOGGER.info("📍 Resume download from byte " + startPosition);
            }
            
            if (config.getApiKey() != null) {
                conn.setRequestProperty("X-API-Key", config.getApiKey());
            }
            
            int responseCode = conn.getResponseCode();
            if (responseCode != 200 && responseCode != 206) {
                throw new IOException("Server returned error " + responseCode);
            }
            
            // Download
            try (InputStream is = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(destinationFile, startPosition > 0)) {
                
                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytesRead = startPosition;
                
                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                    
                    progress.updateProgress(totalBytesRead);
                    if (listener != null) {
                        listener.onProgress(progress);
                    }
                }
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
     * Mã hóa file trước khi upload
     */
    private File encryptFile(File sourceFile) throws Exception {
        File encryptedFile = new File(sourceFile.getParent(), sourceFile.getName() + ".encrypted");
        
        // Đọc file và mã hóa
        byte[] fileData = Files.readAllBytes(sourceFile.toPath());
        byte[] encryptedData = AESEncryption.encrypt(fileData, encryptionKey);
        
        // Ghi file đã mã hóa
        Files.write(encryptedFile.toPath(), encryptedData);
        return encryptedFile;
    }
    
    /**
     * Giải mã file sau khi download
     */
    private File decryptFile(File encryptedFile) throws Exception {
        File decryptedFile = new File(encryptedFile.getParent(), 
                                     encryptedFile.getName().replace(".encrypted", ""));
        
        // Đọc file đã mã hóa và giải mã
        byte[] encryptedData = Files.readAllBytes(encryptedFile.toPath());
        byte[] decryptedData = AESEncryption.decrypt(encryptedData, encryptionKey);
        
        // Ghi file đã giải mã
        Files.write(decryptedFile.toPath(), decryptedData);
        return decryptedFile;
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
                LOGGER.info("✓ Đã đăng ký peer với relay server: " + localPeer.getDisplayName());
                return true;
            } else {
                LOGGER.warning("⚠ Lỗi đăng ký peer: " + responseCode + " - " + response);
                return false;
            }
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "❌ Không thể đăng ký peer với relay: " + e.getMessage(), e);
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
                LOGGER.warning("⚠ Lỗi discover peers: " + responseCode);
                return peers;
            }
            
            String response = readResponse(conn.getInputStream());
            
            // Parse JSON response (simple approach)
            // Format: {"peers":[{...},{...}],"count":2}
            peers = parsePeerListJson(response);
            
            LOGGER.info("🔍 Đã phát hiện " + peers.size() + " peer(s) qua relay");
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "❌ Không thể discover peers qua relay: " + e.getMessage(), e);
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
            LOGGER.log(Level.WARNING, "Lỗi parse peer list JSON: " + e.getMessage(), e);
        }
        
        return peers;
    }
    
    /**
     * Extract field value từ JSON string
     */
    private String extractJsonFieldValue(String json, String field) {
        String pattern = "\"" + field + "\":\"([^\"]+)\"";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        // Try without quotes (for numbers)
        pattern = "\"" + field + "\":([0-9]+)";
        p = java.util.regex.Pattern.compile(pattern);
        m = p.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
    
    public RelayConfig getConfig() {
        return config;
    }
}
