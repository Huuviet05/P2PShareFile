package org.example.p2psharefile.network;

import org.example.p2psharefile.compression.FileCompression;
import org.example.p2psharefile.security.AESEncryption;
import org.example.p2psharefile.security.SecurityManager;
import org.example.p2psharefile.security.FileHashUtil;
import org.example.p2psharefile.model.FileInfo;
import org.example.p2psharefile.model.PeerInfo;
import org.example.p2psharefile.model.*;

import javax.crypto.SecretKey;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * FileTransferService - Truyền file qua TLS/SSL với mã hóa AES
 * 
 * Quy trình truyền file (với TLS + AES):
 * 1. Peer A yêu cầu download file từ Peer B
 * 2. TLS channel được thiết lập (confidentiality + integrity)
 * 3. Peer B đọc file → nén (GZIP) → mã hóa (AES) → gửi qua TLS
 * 4. Peer A nhận → giải mã → giải nén → lưu file
 * 
 * Security layers:
 * - TLS: Bảo vệ transport channel
 * - AES: Mã hóa file content (defense in depth)
 * 
 * Note: Có thể dùng ephemeral DH để tạo session key thay vì shared AES key
 */
public class FileTransferService {
    
    private static final Logger LOGGER = Logger.getLogger(FileTransferService.class.getName());
    private static final int BUFFER_SIZE = 8192;         // 8KB buffer
    private static final String DEFAULT_KEY = "P2PShareFileSecretKey123456789"; // Default AES key
    private static final int P2P_TIMEOUT_MS = 5000;      // 5s timeout cho P2P
    
    private final PeerInfo localPeer;
    private final SecurityManager securityManager;
    private final SecretKey encryptionKey;
    private final int transferPort;
    
    private RelayClient relayClient;                      // Relay client
    private RelayConfig relayConfig;                      // Relay config
    
    private SSLServerSocket transferServer;
    private ExecutorService executorService;
    private volatile boolean running = false;
    
    /**
     * Interface callback cho progress
     */
    public interface TransferProgressListener {
        void onProgress(long bytesTransferred, long totalBytes);
        void onComplete(File file);
        void onError(Exception e);

        void onP2PFailed(String reason);

        void onRelayFallback(String transferId);
    }
    
    public FileTransferService(PeerInfo localPeer, SecurityManager securityManager) {
        this.localPeer = localPeer;
        this.securityManager = securityManager;
        this.transferPort = localPeer.getPort();
        // Tạo encryption key từ default key
        this.encryptionKey = AESEncryption.createKeyFromString(DEFAULT_KEY);
    }
    
    public FileTransferService(PeerInfo localPeer, SecurityManager securityManager, SecretKey customKey) {
        this.localPeer = localPeer;
        this.securityManager = securityManager;
        this.transferPort = localPeer.getPort();
        this.encryptionKey = customKey;
    }
    
    /**
     * Bật relay với config
     */
    public void enableRelay(RelayConfig config) {
        this.relayConfig = config;
        this.relayClient = new RelayClient(config);
        LOGGER.info("✓ Relay đã được bật: " + config.getServerUrl());
    }
    
    /**
     * Kiểm tra relay có được bật không
     */
    public boolean isRelayEnabled() {
        return relayClient != null && relayConfig != null;
    }
    
    /**
     * Lấy relay client instance
     */
    public RelayClient getRelayClient() {
        return relayClient;
    }
    
    /**
     * Bắt đầu dịch vụ truyền file (với TLS)
     */
    public void start() throws IOException {
        if (running) return;
        
        running = true;
        // SSLServerSocket với port = 0 (auto-assign)
        transferServer = securityManager.createSSLServerSocket(transferPort);
        
        // Nếu port = 0, lấy port thực tế được assign
        int actualPort = transferServer.getLocalPort();
        localPeer.setPort(actualPort);
        
        executorService = Executors.newCachedThreadPool();
        
        // Thread lắng nghe yêu cầu download
        executorService.submit(this::listenForTransferRequests);
        
        System.out.println("✓ File Transfer Service (TLS) đã khởi động trên port " + actualPort);
    }
    
    /**
     * Dừng dịch vụ
     */
    public void stop() {
        running = false;
        
        try {
            if (transferServer != null && !transferServer.isClosed()) {
                transferServer.close();
            }
        } catch (IOException e) {
            System.err.println("Lỗi khi đóng transfer server: " + e.getMessage());
        }
        
        if (executorService != null) {
            executorService.shutdownNow();
        }
        
        System.out.println("✓ File Transfer Service đã dừng");
    }
    
    /**
     * Thread lắng nghe yêu cầu download từ peer khác
     */
    private void listenForTransferRequests() {
        while (running) {
            try {
                Socket clientSocket = transferServer.accept();
                executorService.submit(() -> handleTransferRequest(clientSocket));
            } catch (SocketException e) {
                // Server socket đã đóng
                break;
            } catch (IOException e) {
                if (running) {
                    System.err.println("Lỗi khi accept transfer connection: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Xử lý yêu cầu download từ peer khác (Upload file)
     * HỖ TRỢ CHUNKED TRANSFER VỚI RESUME
     */
    private void handleTransferRequest(Socket socket) {
        try (DataInputStream dis = new DataInputStream(socket.getInputStream());
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {
            
            // Đọc request type (hỗ trợ cả legacy và chunked)
            String requestType = dis.readUTF();
            
            if ("CHUNKED_REQUEST".equals(requestType)) {
                // Chunked transfer protocol
                handleChunkedUpload(dis, dos);
            } else {
                // Legacy stream-based protocol - xử lý như file path
                handleLegacyUpload(requestType, dos);
            }
            
        } catch (Exception e) {
            System.err.println("Lỗi khi upload file: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }
    
    /**
     * Xử lý upload theo chunks với hỗ trợ resume
     */
    private void handleChunkedUpload(DataInputStream dis, DataOutputStream dos) throws Exception {
        String filePath = dis.readUTF();
        int startChunk = dis.readInt();  // Resume từ chunk này
        
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            dos.writeUTF("CHUNKED_ERROR");
            dos.writeUTF("File không tồn tại");
            return;
        }
        
        LOGGER.info("📤 Chunked upload: " + file.getName() + " (từ chunk " + startChunk + ")");
        
        long fileSize = file.length();
        int chunkSize = 256 * 1024;  // 256KB chunks
        int totalChunks = (int) Math.ceil((double) fileSize / chunkSize);
        
        // Gửi metadata
        dos.writeUTF("CHUNKED_SUCCESS");
        dos.writeUTF(file.getName());
        dos.writeLong(fileSize);
        dos.writeInt(totalChunks);
        dos.writeInt(chunkSize);
        dos.flush();
        
        // Gửi từng chunk
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] buffer = new byte[chunkSize];
            
            for (int i = startChunk; i < totalChunks; i++) {
                // Seek đến vị trí chunk
                long pos = (long) i * chunkSize;
                raf.seek(pos);
                
                // Đọc chunk
                int bytesToRead = (int) Math.min(chunkSize, fileSize - pos);
                int bytesRead = raf.read(buffer, 0, bytesToRead);
                
                if (bytesRead <= 0) break;
                
                // Mã hóa chunk
                byte[] chunkData = new byte[bytesRead];
                System.arraycopy(buffer, 0, chunkData, 0, bytesRead);
                byte[] encryptedChunk = AESEncryption.encrypt(chunkData, encryptionKey);
                
                // Gửi chunk
                dos.writeUTF("CHUNK");
                dos.writeInt(i);                       // Chunk index
                dos.writeInt(encryptedChunk.length);   // Encrypted size
                dos.write(encryptedChunk);
                dos.flush();
                
                // Đợi ACK
                String ack = dis.readUTF();
                if ("PAUSE".equals(ack)) {
                    LOGGER.info("⏸ Client paused tại chunk " + i);
                    // Đợi resume hoặc cancel
                    String resumeMsg = dis.readUTF();
                    if ("CANCEL".equals(resumeMsg)) {
                        LOGGER.info("❌ Client cancelled download");
                        return;
                    }
                    // Nếu RESUME thì tiếp tục
                } else if ("CANCEL".equals(ack)) {
                    LOGGER.info("❌ Client cancelled download");
                    return;
                }
                // ACK received, tiếp tục
            }
            
            // Gửi hoàn tất
            dos.writeUTF("COMPLETE");
            dos.flush();
            
            LOGGER.info("✅ Chunked upload hoàn tất: " + file.getName());
        }
    }
    
    /**
     * Xử lý upload theo cách cũ (stream-based) để tương thích ngược
     */
    private void handleLegacyUpload(String filePath, DataOutputStream dos) throws Exception {
        // filePath đã được đọc từ trước (requestType chính là filePath trong legacy mode)
        
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            dos.writeBoolean(false);
            dos.writeUTF("File không tồn tại");
            return;
        }
        
        System.out.println("📤 Legacy upload file: " + file.getName());
        
        // Đọc file
        byte[] fileData = Files.readAllBytes(file.toPath());
        
        // Nén file (nếu cần)
        boolean compressed = FileCompression.shouldCompress(file.getName());
        if (compressed) {
            fileData = FileCompression.compress(fileData);
            System.out.println("  ✓ Đã nén: " + fileData.length + " bytes");
        }
        
        // Mã hóa file
        byte[] encryptedData = AESEncryption.encrypt(fileData, encryptionKey);
        System.out.println("  ✓ Đã mã hóa: " + encryptedData.length + " bytes");
        
        // Gửi thông tin file
        dos.writeBoolean(true);               // Success
        dos.writeUTF(file.getName());         // Tên file
        dos.writeLong(file.length());         // Kích thước gốc
        dos.writeBoolean(compressed);         // Có nén không
        dos.writeLong(encryptedData.length);  // Kích thước sau mã hóa
        
        // Gửi dữ liệu file
        dos.write(encryptedData);
        dos.flush();
        
        System.out.println("  ✓ Legacy upload hoàn tất");
    }
    
    /**
     * Download file từ peer khác (qua TLS hoặc Relay)
     * 
     * @param peer Peer có file
     * @param fileInfo Thông tin file cần download
     * @param saveDirectory Thư mục lưu file
     * @param listener Listener để theo dõi progress
     */
    public void downloadFile(PeerInfo peer, FileInfo fileInfo, 
                            String saveDirectory, TransferProgressListener listener) {
        executorService.submit(() -> {
            try {
                System.out.println("📥 Đang download file: " + fileInfo.getFileName() + " từ " + peer);
                
                // Nếu peer là từ relay hoặc file có relay info, download qua relay
                if ("relay".equals(peer.getIpAddress()) || 
                    (fileInfo.getRelayFileInfo() != null && isRelayEnabled())) {
                    
                    if (fileInfo.getRelayFileInfo() != null) {
                        System.out.println("🌐 Download qua relay server...");
                        if (listener != null) {
                            listener.onRelayFallback("relay-" + System.currentTimeMillis());
                        }
                        downloadFileViaRelay(fileInfo.getRelayFileInfo(), saveDirectory, listener);
                        return;
                    } else {
                        System.err.println("❌ File không có relay info");
                        if (listener != null) {
                            listener.onError(new IOException("File not available on relay server"));
                        }
                        return;
                    }
                }
                
                // Download P2P với chunked protocol
                downloadChunkedP2P(peer, fileInfo, saveDirectory, listener);
                
            } catch (Exception e) {
                System.err.println("Lỗi khi download file: " + e.getMessage());
                e.printStackTrace();
                if (listener != null) {
                    listener.onError(e);
                }
            }
        });
    }
    
    // Trạng thái pause/resume cho P2P download
    private volatile boolean p2pPaused = false;
    private volatile boolean p2pCancelled = false;
    private volatile int resumeFromChunk = 0;
    private volatile String currentP2PDownloadFile = null;
    
    /**
     * Pause P2P download
     */
    public void pauseP2PDownload() {
        p2pPaused = true;
        LOGGER.info("⏸ P2P download paused");
    }
    
    /**
     * Resume P2P download
     */
    public void resumeP2PDownload() {
        p2pPaused = false;
        synchronized (this) {
            notifyAll();
        }
        LOGGER.info("▶ P2P download resumed");
    }
    
    /**
     * Cancel P2P download
     */
    public void cancelP2PDownload() {
        p2pCancelled = true;
        p2pPaused = false;
        synchronized (this) {
            notifyAll();
        }
        LOGGER.info("❌ P2P download cancelled");
    }
    
    /**
     * Check if P2P download is paused
     */
    public boolean isP2PPaused() {
        return p2pPaused;
    }
    
    /**
     * Download file từ peer sử dụng chunked protocol với resume support
     */
    private void downloadChunkedP2P(PeerInfo peer, FileInfo fileInfo, 
                                     String saveDirectory, TransferProgressListener listener) {
        SSLSocket socket = null;
        try {
            LOGGER.info("📥 Chunked download: " + fileInfo.getFileName() + " từ " + peer);
            
            // Reset trạng thái
            p2pPaused = false;
            p2pCancelled = false;
            currentP2PDownloadFile = fileInfo.getFileName();
            
            // Kiểm tra file .part có tồn tại không (để resume)
            File saveDir = new File(saveDirectory);
            if (!saveDir.exists()) saveDir.mkdirs();
            
            File destFile = new File(saveDir, fileInfo.getFileName());
            File tempFile = new File(saveDir, fileInfo.getFileName() + ".part");
            
            // Nếu có file .part, tính chunk để resume
            if (tempFile.exists() && resumeFromChunk > 0) {
                LOGGER.info("📍 Resume từ chunk " + resumeFromChunk);
            } else {
                resumeFromChunk = 0;
            }
            
            // Kết nối
            socket = securityManager.createSSLSocket(peer.getIpAddress(), peer.getPort());
            socket.connect(new InetSocketAddress(peer.getIpAddress(), peer.getPort()), 5000);
            socket.setSoTimeout(120000);  // 2 phút timeout cho chunked transfer
            socket.startHandshake();
            
            DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
            DataInputStream dis = new DataInputStream(socket.getInputStream());
            
            // Gửi chunked request
            dos.writeUTF("CHUNKED_REQUEST");
            dos.writeUTF(fileInfo.getFilePath());
            dos.writeInt(resumeFromChunk);
            dos.flush();
            
            // Đọc response
            String msgType = dis.readUTF();
            if ("CHUNKED_ERROR".equals(msgType)) {
                String error = dis.readUTF();
                throw new IOException("Server error: " + error);
            }
            
            // Parse metadata
            String fileName = dis.readUTF();
            long totalSize = dis.readLong();
            int totalChunks = dis.readInt();
            int chunkSize = dis.readInt();
            
            LOGGER.info(String.format("📦 File: %s, Size: %d, Chunks: %d, ChunkSize: %d", 
                        fileName, totalSize, totalChunks, chunkSize));
            
            // Tính lại vị trí resume
            long bytesAlreadyReceived = (long) resumeFromChunk * chunkSize;
            
            // Mở file để ghi (append nếu resume)
            try (RandomAccessFile raf = new RandomAccessFile(tempFile, "rw")) {
                if (resumeFromChunk > 0) {
                    raf.seek(tempFile.length());
                }
                
                long totalBytesReceived = bytesAlreadyReceived;
                
                // Nhận từng chunk
                while (true) {
                    // Check cancel
                    if (p2pCancelled) {
                        dos.writeUTF("CANCEL");
                        dos.flush();
                        LOGGER.info("❌ Download cancelled by user");
                        currentP2PDownloadFile = null;
                        return;
                    }
                    
                    // Check pause
                    while (p2pPaused && !p2pCancelled) {
                        dos.writeUTF("PAUSE");
                        dos.flush();
                        if (listener != null) {
                            listener.onProgress(totalBytesReceived, totalSize);
                        }
                        LOGGER.info("⏸ Download paused tại byte " + totalBytesReceived);
                        
                        // Đợi resume hoặc cancel
                        synchronized (this) {
                            try {
                                wait(1000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                    
                    if (p2pCancelled) continue;
                    
                    // Đọc message type
                    String msg = dis.readUTF();
                    
                    if ("COMPLETE".equals(msg)) {
                        break;
                    }
                    
                    if (!"CHUNK".equals(msg)) {
                        throw new IOException("Unexpected message: " + msg);
                    }
                    
                    // Đọc chunk
                    int chunkIndex = dis.readInt();
                    int encryptedSize = dis.readInt();
                    byte[] encryptedData = new byte[encryptedSize];
                    dis.readFully(encryptedData);
                    
                    // Giải mã chunk
                    byte[] decryptedData = AESEncryption.decrypt(encryptedData, encryptionKey);
                    
                    // Ghi vào file
                    raf.write(decryptedData);
                    
                    // Cập nhật progress
                    totalBytesReceived += decryptedData.length;
                    resumeFromChunk = chunkIndex + 1;
                    
                    // Thông báo progress
                    if (listener != null) {
                        listener.onProgress(totalBytesReceived, totalSize);
                    }
                    
                    // Gửi ACK
                    dos.writeUTF("ACK");
                    dos.flush();
                }
            }
            
            // Rename temp file thành file cuối cùng
            if (tempFile.exists()) {
                Files.move(tempFile.toPath(), destFile.toPath(), 
                          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            
            // Reset
            resumeFromChunk = 0;
            currentP2PDownloadFile = null;
            
            LOGGER.info("✅ Chunked download hoàn tất: " + destFile.getAbsolutePath());
            
            if (listener != null) {
                listener.onComplete(destFile);
            }
            
        } catch (Exception e) {
            LOGGER.severe("❌ Download error: " + e.getMessage());
            e.printStackTrace();
            currentP2PDownloadFile = null;
            if (listener != null) {
                listener.onError(e);
            }
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }    
    /**
     * Download file với fallback tự động từ P2P sang Relay
                }
            }
        });
    }    
    /**
     * Download file với fallback tự động từ P2P sang Relay
     * 
     * @param peer Peer có file
     * @param fileInfo Thông tin file
     * @param saveDirectory Thư mục lưu file
     * @param listener Listener để theo dõi progress
     */
    public void downloadFileWithFallback(PeerInfo peer, FileInfo fileInfo,
                                         String saveDirectory, TransferProgressListener listener) {
        executorService.submit(() -> {
            try {
                LOGGER.info("🔄 Thử download P2P từ " + peer.getDisplayName());
                
                // Thử P2P trước với timeout
                Future<Boolean> p2pFuture = executorService.submit(() -> {
                    try {
                        downloadFileP2PSync(peer, fileInfo, saveDirectory, listener);
                        return true;
                    } catch (Exception e) {
                        LOGGER.warning("⚠ P2P thất bại: " + e.getMessage());
                        return false;
                    }
                });
                
                try {
                    // Đợi P2P với timeout
                    boolean p2pSuccess = p2pFuture.get(
                        relayConfig != null ? relayConfig.getP2pTimeoutMs() : P2P_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS
                    );
                    
                    if (p2pSuccess) {
                        LOGGER.info("✅ P2P download thành công");
                        return;
                    }
                    
                } catch (TimeoutException e) {
                    p2pFuture.cancel(true);
                    LOGGER.info("⏱ P2P timeout sau " + P2P_TIMEOUT_MS + "ms");
                    if (listener != null) {
                        listener.onP2PFailed("Timeout");
                    }
                } catch (Exception e) {
                    LOGGER.warning("⚠ P2P exception: " + e.getMessage());
                    if (listener != null) {
                        listener.onP2PFailed(e.getMessage());
                    }
                }
                
                // Fallback sang Relay nếu được bật
                if (isRelayEnabled()) {
                    LOGGER.info("🔄 Fallback sang Relay...");
                    if (listener != null) {
                        listener.onRelayFallback("relay-" + System.currentTimeMillis());
                    }
                    
                    // Kiểm tra xem fileInfo có relayFileInfo không
                    if (fileInfo.getRelayFileInfo() != null) {
                        LOGGER.info("📡 Đang download qua relay server...");
                        downloadFileViaRelay(fileInfo.getRelayFileInfo(), saveDirectory, listener);
                    } else {
                        LOGGER.warning("⚠ File chưa có relay info, không thể download qua relay");
                        if (listener != null) {
                            listener.onError(new IOException("File not available on relay server"));
                        }
                    }
                    
                } else {
                    LOGGER.severe("❌ Relay chưa được bật, không thể fallback");
                    if (listener != null) {
                        listener.onError(new IOException("P2P failed and relay not enabled"));
                    }
                }
                
            } catch (Exception e) {
                LOGGER.severe("❌ Lỗi download: " + e.getMessage());
                if (listener != null) {
                    listener.onError(e);
                }
            }
        });
    }
    
    /**
     * Download P2P đồng bộ (dùng cho timeout check)
     */
    private void downloadFileP2PSync(PeerInfo peer, FileInfo fileInfo,
                                     String saveDirectory, TransferProgressListener listener) throws Exception {
        SSLSocket socket = securityManager.createSSLSocket(peer.getIpAddress(), peer.getPort());
        socket.connect(new InetSocketAddress(peer.getIpAddress(), peer.getPort()), 3000);
        socket.setSoTimeout(30000);
        socket.startHandshake();
        
        try (ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream())) {
            
            oos.writeUTF(fileInfo.getFilePath());
            oos.flush();
            
            boolean success = dis.readBoolean();
            if (!success) {
                throw new IOException("Peer từ chối: " + dis.readUTF());
            }
            
            String fileName = dis.readUTF();
            long originalSize = dis.readLong();
            boolean compressed = dis.readBoolean();
            long encryptedSize = dis.readLong();
            
            byte[] encryptedData = new byte[(int) encryptedSize];
            int totalRead = 0;
            
            while (totalRead < encryptedSize) {
                int bytesRead = dis.read(encryptedData, totalRead, (int)(encryptedSize - totalRead));
                if (bytesRead == -1) break;
                totalRead += bytesRead;
                
                if (listener != null) {
                    listener.onProgress(totalRead, encryptedSize);
                }
            }
            
            byte[] decrypted = AESEncryption.decrypt(encryptedData, encryptionKey);
            byte[] finalData = compressed ? FileCompression.decompress(decrypted) : decrypted;
            
            File savedFile = new File(saveDirectory, fileName);
            Files.write(savedFile.toPath(), finalData);
            
            if (listener != null) {
                listener.onComplete(savedFile);
            }
            
        } finally {
            socket.close();
        }
    }
    
    /**
     * Upload file qua relay
     */
    public void uploadFileViaRelay(File file, PeerInfo recipient, TransferProgressListener listener) {
        if (!isRelayEnabled()) {
            LOGGER.severe("❌ Relay chưa được bật");
            if (listener != null) {
                listener.onError(new IllegalStateException("Relay not enabled"));
            }
            return;
        }
        
        executorService.submit(() -> {
            try {
                LOGGER.info("📤 Upload file qua relay: " + file.getName());
                
                // Tạo upload request
                String fileHash = FileHashUtil.calculateSHA256(file);
                RelayUploadRequest request = new RelayUploadRequest(
                    localPeer.getPeerId(),
                    localPeer.getDisplayName(),
                    file.getName(),
                    file.length(),
                    fileHash
                );
                request.setRecipientId(recipient.getPeerId());
                request.setMimeType(guessMimeType(file.getName()));
                
                // Upload
                RelayFileInfo fileInfo = relayClient.uploadFile(file, request, new RelayClient.RelayTransferListener() {
                    @Override
                    public void onProgress(RelayTransferProgress progress) {
                        if (listener != null) {
                            listener.onProgress(progress.getTransferredBytes(), progress.getTotalBytes());
                        }
                    }
                    
                    @Override
                    public void onComplete(RelayFileInfo info) {
                        LOGGER.info("✅ Upload relay thành công: " + info.getUploadId());
                        // TODO: Gửi RelayFileInfo cho recipient qua signaling
                    }
                    
                    @Override
                    public void onError(Exception e) {
                        LOGGER.severe("❌ Upload relay thất bại: " + e.getMessage());
                        if (listener != null) {
                            listener.onError(e);
                        }
                    }
                });
                
                if (fileInfo != null && listener != null) {
                    listener.onComplete(file);
                }
                
            } catch (Exception e) {
                LOGGER.severe("❌ Lỗi upload relay: " + e.getMessage());
                if (listener != null) {
                    listener.onError(e);
                }
            }
        });
    }
    
    /**
     * Download file qua relay
     */
    public void downloadFileViaRelay(RelayFileInfo fileInfo, String saveDirectory, TransferProgressListener listener) {
        if (!isRelayEnabled()) {
            LOGGER.severe("❌ Relay chưa được bật");
            if (listener != null) {
                listener.onError(new IllegalStateException("Relay not enabled"));
            }
            return;
        }
        
        executorService.submit(() -> {
            try {
                LOGGER.info("📥 Download file qua relay: " + fileInfo.getFileName());
                
                File destFile = new File(saveDirectory, fileInfo.getFileName());
                
                boolean success = relayClient.downloadFile(fileInfo, destFile, new RelayClient.RelayTransferListener() {
                    @Override
                    public void onProgress(RelayTransferProgress progress) {
                        if (listener != null) {
                            listener.onProgress(progress.getTransferredBytes(), progress.getTotalBytes());
                        }
                    }
                    
                    @Override
                    public void onComplete(RelayFileInfo info) {
                        LOGGER.info("✅ Download relay thành công");
                        if (listener != null) {
                            listener.onComplete(destFile);
                        }
                    }
                    
                    @Override
                    public void onError(Exception e) {
                        LOGGER.severe("❌ Download relay thất bại: " + e.getMessage());
                        if (listener != null) {
                            listener.onError(e);
                        }
                    }
                });
                
            } catch (Exception e) {
                LOGGER.severe("❌ Lỗi download relay: " + e.getMessage());
                if (listener != null) {
                    listener.onError(e);
                }
            }
        });
    }
    
    /**
     * Đoán MIME type từ tên file
     */
    private String guessMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }    
    /**
     * Upload file đơn giản (không qua request-response) với TLS
     * Dùng khi muốn chủ động gửi file cho peer
     */
    public void uploadFileToPeer(PeerInfo peer, File file, TransferProgressListener listener) {
        executorService.submit(() -> {
            try {
                System.out.println("📤 Đang gửi file: " + file.getName() + " đến " + peer);
                
                // Kết nối đến peer qua TLS
                SSLSocket socket = securityManager.createSSLSocket(peer.getIpAddress(), peer.getPort());
                socket.connect(new InetSocketAddress(peer.getIpAddress(), peer.getPort()), 5000);
                socket.setSoTimeout(60000);
                socket.startHandshake();
                
                try (DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {
                    
                    // Đọc file
                    byte[] fileData = Files.readAllBytes(file.toPath());
                    long originalSize = fileData.length;
                    
                    // Nén
                    boolean compressed = FileCompression.shouldCompress(file.getName());
                    if (compressed) {
                        fileData = FileCompression.compress(fileData);
                    }
                    
                    // Mã hóa
                    byte[] encryptedData = AESEncryption.encrypt(fileData, encryptionKey);
                    
                    // Gửi metadata
                    dos.writeUTF(file.getName());
                    dos.writeLong(originalSize);
                    dos.writeBoolean(compressed);
                    dos.writeLong(encryptedData.length);
                    
                    // Gửi data
                    dos.write(encryptedData);
                    dos.flush();
                    
                    System.out.println("  ✅ Upload hoàn tất");
                    
                    if (listener != null) {
                        listener.onComplete(file);
                    }
                    
                } finally {
                    socket.close();
                }
                
            } catch (Exception e) {
                System.err.println("Lỗi khi upload file: " + e.getMessage());
                if (listener != null) {
                    listener.onError(e);
                }
            }
        });
    }
    
    /**
     * Lấy encryption key (để chia sẻ với peer khác nếu cần)
     */
    public String getEncryptionKeyString() {
        return AESEncryption.keyToString(encryptionKey);
    }
}
