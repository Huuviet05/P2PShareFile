package org.example.p2psharefile.network;

import org.example.p2psharefile.compression.FileCompression;
import org.example.p2psharefile.model.FileInfo;
import org.example.p2psharefile.model.PeerInfo;
import org.example.p2psharefile.model.TransferState;
import org.example.p2psharefile.model.TransferState.TransferStatus;
import org.example.p2psharefile.model.RelayFileInfo;
import org.example.p2psharefile.model.RelayUploadRequest;
import org.example.p2psharefile.model.RelayTransferProgress;
import org.example.p2psharefile.security.AESEncryption;
import org.example.p2psharefile.security.SecurityManager;

import javax.crypto.SecretKey;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * ChunkedFileTransferService - Truyền file theo chunk với hỗ trợ resume
 * 
 * Kết hợp cả P2P (LAN) và Relay (Internet):
 * - P2P: Sử dụng SSLSocket kết nối trực tiếp
 * - Relay: Sử dụng RelayClient khi không kết nối được P2P
 * 
 * Đặc điểm:
 * - Chia file thành các chunk nhỏ (mặc định 64KB)
 * - Mỗi chunk được mã hóa và gửi riêng biệt
 * - Hỗ trợ pause/resume download
 * - Progress tracking chi tiết
 * - Khôi phục từ chunk cuối cùng khi resume
 * 
 * Protocol:
 * - CMD_REQUEST_METADATA (0x01): Yêu cầu thông tin file
 * - CMD_REQUEST_CHUNK (0x02): Yêu cầu chunk cụ thể
 * - CMD_RESPONSE_METADATA (0x11): Trả về metadata
 * - CMD_RESPONSE_CHUNK (0x12): Trả về dữ liệu chunk
 * 
 * @author P2PShareFile Team
 * @version 2.0 - Chunked Transfer with Resume
 */
public class ChunkedFileTransferService {
    
    private static final Logger LOGGER = Logger.getLogger(ChunkedFileTransferService.class.getName());
    private static final String DEFAULT_KEY = "P2PShareFileSecretKey123456789";
    private static final int CONNECTION_TIMEOUT = 10000;  // 10s
    private static final int READ_TIMEOUT = 120000;       // 120s
    private static final int CHUNKED_TRANSFER_PORT = 9999; // Port cố định cho chunked transfer
    
    // Protocol commands
    private static final byte CMD_REQUEST_METADATA = 0x01;
    private static final byte CMD_REQUEST_CHUNK = 0x02;
    private static final byte CMD_RESPONSE_METADATA = 0x11;
    private static final byte CMD_RESPONSE_CHUNK = 0x12;
    private static final byte CMD_ERROR = (byte) 0xFF;
    
    private final PeerInfo localPeer;
    private final SecurityManager securityManager;
    private final SecretKey encryptionKey;
    
    // Relay support
    private RelayClient relayClient;
    private RelayConfig relayConfig;
    
    // Server socket để nhận requests từ peers khác
    private SSLServerSocket serverSocket;
    private ExecutorService executorService;
    private volatile boolean running = false;
    
    // Active transfers
    private final Map<String, TransferState> activeTransfers = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> transferTasks = new ConcurrentHashMap<>();
    
    /**
     * Interface callback cho progress
     */
    public interface ChunkedTransferListener {
        void onProgress(TransferState state);
        void onChunkReceived(TransferState state, int chunkIndex);
        void onComplete(TransferState state, File file);
        void onError(TransferState state, Exception e);
        void onPaused(TransferState state);
        void onResumed(TransferState state);
    }
    
    /**
     * Interface callback tương thích với FileTransferService
     */
    public interface TransferProgressListener {
        void onProgress(long bytesTransferred, long totalBytes);
        void onComplete(File file);
        void onError(Exception e);
        void onP2PFailed(String reason);
        void onRelayFallback(String transferId);
    }
    
    public ChunkedFileTransferService(PeerInfo localPeer, SecurityManager securityManager) {
        this.localPeer = localPeer;
        this.securityManager = securityManager;
        this.encryptionKey = AESEncryption.createKeyFromString(DEFAULT_KEY);
    }
    
    public ChunkedFileTransferService(PeerInfo localPeer, SecurityManager securityManager, SecretKey customKey) {
        this.localPeer = localPeer;
        this.securityManager = securityManager;
        this.encryptionKey = customKey;
    }
    
    // ========== Relay Support ==========
    
    /**
     * Bật relay với config
     */
    public void enableRelay(RelayConfig config) {
        this.relayConfig = config;
        this.relayClient = new RelayClient(config);
        System.out.println("✓ Relay đã được bật: " + config.getServerUrl());
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
    
    // ========== Server Methods ==========
    
    /**
     * Bắt đầu service với server socket để nhận requests
     */
    public void start() throws IOException {
        if (running) return;
        
        running = true;
        executorService = Executors.newCachedThreadPool();
        
        // Tạo SSLServerSocket để lắng nghe chunk requests
        serverSocket = securityManager.createSSLServerSocket(CHUNKED_TRANSFER_PORT);
        
        // Thread lắng nghe requests
        executorService.submit(this::listenForRequests);
        
        System.out.println("✓ Chunked File Transfer Service đã khởi động trên port " + CHUNKED_TRANSFER_PORT);
    }
    
    /**
     * Thread lắng nghe requests từ peers
     */
    private void listenForRequests() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                executorService.submit(() -> handleClientRequest((SSLSocket) clientSocket));
            } catch (SocketException e) {
                if (running) {
                    LOGGER.warning("Server socket error: " + e.getMessage());
                }
                break;
            } catch (IOException e) {
                if (running) {
                    LOGGER.warning("Error accepting connection: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Lấy port đang dùng
     */
    public int getPort() {
        return CHUNKED_TRANSFER_PORT;
    }
    
    /**
     * Dừng service
     */
    public void stop() {
        running = false;
        
        // Cancel tất cả active transfers
        for (Future<?> task : transferTasks.values()) {
            task.cancel(true);
        }
        transferTasks.clear();
        activeTransfers.clear();
        
        // Đóng server socket
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            LOGGER.warning("Error closing server socket: " + e.getMessage());
        }
        
        if (executorService != null) {
            executorService.shutdownNow();
        }
        
        System.out.println("✓ Chunked File Transfer Service đã dừng");
    }
    
    /**
     * Xử lý request từ client
     */
    private void handleClientRequest(SSLSocket socket) {
        try {
            socket.setSoTimeout(READ_TIMEOUT);
            socket.startHandshake();
            
            DataInputStream dis = new DataInputStream(socket.getInputStream());
            DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
            
            byte command = dis.readByte();
            
            switch (command) {
                case CMD_REQUEST_METADATA:
                    handleMetadataRequest(dis, dos);
                    break;
                case CMD_REQUEST_CHUNK:
                    handleChunkRequest(dis, dos);
                    break;
                default:
                    dos.writeByte(CMD_ERROR);
                    dos.writeUTF("Unknown command: " + command);
            }
            
        } catch (Exception e) {
            LOGGER.warning("Error handling client request: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }
    
    /**
     * Xử lý yêu cầu metadata
     */
    private void handleMetadataRequest(DataInputStream dis, DataOutputStream dos) throws IOException {
        String filePath = dis.readUTF();
        File file = new File(filePath);
        
        if (!file.exists() || !file.isFile()) {
            dos.writeByte(CMD_ERROR);
            dos.writeUTF("File không tồn tại: " + filePath);
            return;
        }
        
        dos.writeByte(CMD_RESPONSE_METADATA);
        dos.writeUTF(file.getName());                           // fileName
        dos.writeLong(file.length());                           // fileSize
        dos.writeInt(TransferState.DEFAULT_CHUNK_SIZE);         // chunkSize
        dos.writeBoolean(FileCompression.shouldCompress(file.getName())); // compressed
        dos.flush();
        
        System.out.println("📋 Đã gửi metadata: " + file.getName() + " (" + file.length() + " bytes)");
    }
    
    /**
     * Xử lý yêu cầu chunk
     */
    private void handleChunkRequest(DataInputStream dis, DataOutputStream dos) throws IOException {
        String filePath = dis.readUTF();
        int chunkIndex = dis.readInt();
        int chunkSize = dis.readInt();
        
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            dos.writeByte(CMD_ERROR);
            dos.writeUTF("File không tồn tại");
            return;
        }
        
        long offset = (long) chunkIndex * chunkSize;
        int actualChunkSize = (int) Math.min(chunkSize, file.length() - offset);
        
        if (offset >= file.length() || actualChunkSize <= 0) {
            dos.writeByte(CMD_ERROR);
            dos.writeUTF("Invalid chunk index: " + chunkIndex);
            return;
        }
        
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            
            byte[] chunkData = new byte[actualChunkSize];
            int bytesRead = raf.read(chunkData);
            
            if (bytesRead != actualChunkSize) {
                dos.writeByte(CMD_ERROR);
                dos.writeUTF("Failed to read chunk data");
                return;
            }
            
            // Nén nếu cần
            boolean shouldCompress = FileCompression.shouldCompress(file.getName());
            if (shouldCompress) {
                chunkData = FileCompression.compress(chunkData);
            }
            
            // Mã hóa
            byte[] encryptedChunk = null;
            try {
                encryptedChunk = AESEncryption.encrypt(chunkData, encryptionKey);
            } catch (Exception e) {
                throw new IOException("Encryption failed: " + e.getMessage());
            }

            // Gửi response
            dos.writeByte(CMD_RESPONSE_CHUNK);
            dos.writeInt(chunkIndex);                    // chunkIndex
            dos.writeInt(actualChunkSize);               // originalSize
            dos.writeBoolean(shouldCompress);            // compressed
            dos.writeInt(encryptedChunk.length);         // encryptedSize
            dos.write(encryptedChunk);                   // data
            dos.flush();
        }
    }
    
    // ========== Download methods ==========
    
    /**
     * Download file - xác định dùng P2P hay Relay
     */
    public void downloadFile(PeerInfo peer, FileInfo fileInfo, 
                            String saveDirectory, TransferProgressListener listener) {
        executorService.submit(() -> {
            try {
                // CHỈ download qua relay khi peer IP là "relay" hoặc null/empty
                boolean isPeerFromRelay = "relay".equals(peer.getIpAddress()) || 
                                          peer.getIpAddress() == null || 
                                          peer.getIpAddress().isEmpty();
                
                if (isPeerFromRelay) {
                    // Peer từ relay, phải download qua relay
                    if (fileInfo.getRelayFileInfo() != null && isRelayEnabled()) {
                        System.out.println("🌐 Download qua relay server...");
                        if (listener != null) {
                            listener.onRelayFallback("relay-" + System.currentTimeMillis());
                        }
                        downloadFileViaRelay(fileInfo.getRelayFileInfo(), saveDirectory, listener);
                        return;
                    } else {
                        throw new IOException("Peer từ relay nhưng không có relay file info");
                    }
                }
                
                // Peer LAN - download P2P với chunked protocol
                System.out.println("🔗 Download P2P (chunked) từ " + peer.getIpAddress() + ":" + CHUNKED_TRANSFER_PORT);
                downloadChunkedP2P(peer, fileInfo, saveDirectory, listener);
                
            } catch (Exception e) {
                System.err.println("❌ Download error: " + e.getMessage());
                if (listener != null) {
                    listener.onError(e);
                }
            }
        });
    }
    
    /**
     * Download file với chunked transfer (hỗ trợ resume) - trả về TransferState
     */
    public TransferState downloadFileChunked(PeerInfo peer, FileInfo fileInfo, 
                                             String saveDirectory, ChunkedTransferListener listener) {
        // Tạo hoặc lấy TransferState existing
        String transferKey = peer.getPeerId() + "_" + fileInfo.getFilePath();
        TransferState state = activeTransfers.get(transferKey);
        
        if (state == null) {
            state = new TransferState(fileInfo.getFileName(), fileInfo.getFilePath(), fileInfo.getFileSize());
            state.setSaveDirectory(saveDirectory);
            state.setPeerIp(peer.getIpAddress());
            state.setPeerPort(CHUNKED_TRANSFER_PORT);
            activeTransfers.put(transferKey, state);
        }
        
        final TransferState finalState = state;
        
        // Bắt đầu download task
        Future<?> task = executorService.submit(() -> {
            try {
                downloadChunks(peer, fileInfo, finalState, listener);
            } catch (Exception e) {
                finalState.fail(e.getMessage());
                if (listener != null) {
                    listener.onError(finalState, e);
                }
            }
        });
        
        transferTasks.put(transferKey, task);
        return state;
    }
    
    /**
     * Download P2P với protocol đơn giản (tương thích TransferProgressListener)
     */
    private void downloadChunkedP2P(PeerInfo peer, FileInfo fileInfo, 
                                     String saveDirectory, TransferProgressListener listener) throws Exception {
        TransferState state = new TransferState(fileInfo.getFileName(), fileInfo.getFilePath(), fileInfo.getFileSize());
        state.setSaveDirectory(saveDirectory);
        state.setPeerIp(peer.getIpAddress());
        state.setPeerPort(CHUNKED_TRANSFER_PORT);
        
        String transferKey = peer.getPeerId() + "_" + fileInfo.getFilePath();
        activeTransfers.put(transferKey, state);
        
        // Wrap listener
        ChunkedTransferListener chunkedListener = new ChunkedTransferListener() {
            @Override
            public void onProgress(TransferState s) {
                if (listener != null) {
                    listener.onProgress(s.getBytesTransferred(), s.getFileSize());
                }
            }
            
            @Override
            public void onChunkReceived(TransferState s, int chunkIndex) {
                // Progress đã được gọi ở onProgress
            }
            
            @Override
            public void onComplete(TransferState s, File file) {
                if (listener != null) {
                    listener.onComplete(file);
                }
            }
            
            @Override
            public void onError(TransferState s, Exception e) {
                if (listener != null) {
                    listener.onError(e);
                }
            }
            
            @Override
            public void onPaused(TransferState s) {}
            
            @Override
            public void onResumed(TransferState s) {}
        };
        
        downloadChunks(peer, fileInfo, state, chunkedListener);
    }
    
    /**
     * Download các chunk - logic chính
     */
    private void downloadChunks(PeerInfo peer, FileInfo fileInfo, 
                               TransferState state, ChunkedTransferListener listener) throws Exception {
        
        System.out.println("📥 Bắt đầu chunked download: " + fileInfo.getFileName());
        
        // 1. Lấy metadata từ peer (nếu chưa có)
        if (state.getTotalChunks() == 0 || state.getStatus() == TransferStatus.PENDING) {
            requestMetadata(peer, fileInfo, state);
        }
        
        state.start();
        
        // 2. Tạo file tạm để lưu chunks
        File saveDir = new File(state.getSaveDirectory());
        if (!saveDir.exists()) {
            saveDir.mkdirs();
        }
        
        File tempFile = new File(saveDir, state.getFileName() + ".part");
        File finalFile = new File(saveDir, state.getFileName());
        
        // 3. Tạo file với kích thước đầy đủ nếu chưa có
        if (!tempFile.exists()) {
            try (RandomAccessFile raf = new RandomAccessFile(tempFile, "rw")) {
                raf.setLength(state.getFileSize());
            }
        }
        
        // 4. Download từng chunk
        int totalChunks = state.getTotalChunks();
        int startChunk = state.getNextMissingChunk();
        
        System.out.println("  📦 Tổng: " + totalChunks + " chunks, bắt đầu từ: " + startChunk);
        
        int lastLoggedPercent = -1;
        
        for (int i = startChunk; i < totalChunks; i++) {
            // Kiểm tra thread interrupted
            if (Thread.currentThread().isInterrupted()) {
                state.cancel();
                if (tempFile.exists()) tempFile.delete();
                return;
            }
            
            // Kiểm tra CANCELLED
            if (state.getStatus() == TransferStatus.CANCELLED) {
                System.out.println("  ❌ Download đã bị hủy");
                if (tempFile.exists()) tempFile.delete();
                return;
            }
            
            // Chờ nếu đang pause
            while (state.getStatus() == TransferStatus.PAUSED) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    state.cancel();
                    if (tempFile.exists()) tempFile.delete();
                    return;
                }
                if (state.getStatus() == TransferStatus.CANCELLED) {
                    if (tempFile.exists()) tempFile.delete();
                    return;
                }
            }
            
            // Skip chunk đã nhận
            if (state.isChunkReceived(i)) {
                continue;
            }
            
            // Download chunk
            byte[] chunkData = downloadChunk(peer, fileInfo.getFilePath(), i, state.getChunkSize());
            
            if (chunkData != null) {
                // Kiểm tra trạng thái trước khi ghi
                if (state.getStatus() == TransferStatus.CANCELLED) {
                    if (tempFile.exists()) tempFile.delete();
                    return;
                }
                
                // Ghi chunk vào file
                long offset = state.getChunkOffset(i);
                try (RandomAccessFile raf = new RandomAccessFile(tempFile, "rw")) {
                    raf.seek(offset);
                    raf.write(chunkData);
                }
                
                // Cập nhật state
                state.markChunkReceived(i, chunkData.length);
                
                // Notify listener
                if (listener != null) {
                    listener.onChunkReceived(state, i);
                    listener.onProgress(state);
                }
                
                // Log progress mỗi 10% (giảm log verbose)
                int percent = state.getProgressPercent();
                if (percent / 10 > lastLoggedPercent / 10) {
                    System.out.printf("  ⏳ Progress: %d%% (%d/%d chunks)%n", 
                        percent, state.getReceivedChunkCount(), totalChunks);
                    lastLoggedPercent = percent;
                }
            } else {
                throw new IOException("Failed to download chunk " + i);
            }
        }
        
        // 5. Hoàn tất
        if (state.isComplete()) {
            // Rename temp file to final
            if (finalFile.exists()) {
                finalFile.delete();
            }
            Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            
            state.complete();
            System.out.println("  ✅ Download hoàn tất: " + finalFile.getAbsolutePath());
            
            if (listener != null) {
                listener.onComplete(state, finalFile);
            }
            
            // Cleanup
            String transferKey = peer.getPeerId() + "_" + fileInfo.getFilePath();
            activeTransfers.remove(transferKey);
            transferTasks.remove(transferKey);
        }
    }
    
    /**
     * Yêu cầu metadata từ peer
     */
    private void requestMetadata(PeerInfo peer, FileInfo fileInfo, TransferState state) throws Exception {
        SSLSocket socket = securityManager.createSSLSocket(peer.getIpAddress(), CHUNKED_TRANSFER_PORT);
        socket.connect(new InetSocketAddress(peer.getIpAddress(), CHUNKED_TRANSFER_PORT), CONNECTION_TIMEOUT);
        socket.setSoTimeout(READ_TIMEOUT);
        socket.startHandshake();
        
        try (DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream())) {
            
            dos.writeByte(CMD_REQUEST_METADATA);
            dos.writeUTF(fileInfo.getFilePath());
            dos.flush();
            
            byte response = dis.readByte();
            if (response == CMD_ERROR) {
                throw new IOException(dis.readUTF());
            }
            
            if (response == CMD_RESPONSE_METADATA) {
                String fileName = dis.readUTF();
                long fileSize = dis.readLong();
                int chunkSize = dis.readInt();
                boolean compressed = dis.readBoolean();
                
                state.setFileName(fileName);
                state.setFileSize(fileSize);
                state.setChunkSize(chunkSize);
                
                System.out.println("  📋 Metadata: " + fileName + " (" + fileSize + " bytes, " + 
                    state.getTotalChunks() + " chunks)");
            }
        } finally {
            socket.close();
        }
    }
    
    /**
     * Download một chunk từ peer
     */
    private byte[] downloadChunk(PeerInfo peer, String filePath, int chunkIndex, int chunkSize) throws Exception {
        SSLSocket socket = securityManager.createSSLSocket(peer.getIpAddress(), CHUNKED_TRANSFER_PORT);
        socket.connect(new InetSocketAddress(peer.getIpAddress(), CHUNKED_TRANSFER_PORT), CONNECTION_TIMEOUT);
        socket.setSoTimeout(READ_TIMEOUT);
        socket.startHandshake();
        
        try (DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream())) {
            
            dos.writeByte(CMD_REQUEST_CHUNK);
            dos.writeUTF(filePath);
            dos.writeInt(chunkIndex);
            dos.writeInt(chunkSize);
            dos.flush();
            
            byte response = dis.readByte();
            if (response == CMD_ERROR) {
                throw new IOException(dis.readUTF());
            }
            
            if (response == CMD_RESPONSE_CHUNK) {
                int receivedIndex = dis.readInt();
                int originalSize = dis.readInt();
                boolean compressed = dis.readBoolean();
                int encryptedSize = dis.readInt();
                
                byte[] encryptedData = new byte[encryptedSize];
                dis.readFully(encryptedData);
                
                // Giải mã
                byte[] decrypted = AESEncryption.decrypt(encryptedData, encryptionKey);
                
                // Giải nén nếu cần
                if (compressed) {
                    decrypted = FileCompression.decompress(decrypted);
                }
                
                return decrypted;
            }
            
            return null;
        } finally {
            socket.close();
        }
    }
    
    // ========== Relay Download ==========
    
    /**
     * Download file qua relay
     */
    public void downloadFileViaRelay(RelayFileInfo fileInfo, String saveDirectory, TransferProgressListener listener) {
        if (!isRelayEnabled()) {
            System.err.println("❌ Relay chưa được bật");
            if (listener != null) {
                listener.onError(new IOException("Relay chưa được bật"));
            }
            return;
        }
        
        executorService.submit(() -> {
            try {
                System.out.println("🌐 Downloading từ relay: " + fileInfo.getFileName());
                
                // Tạo file đích
                File saveDir = new File(saveDirectory);
                if (!saveDir.exists()) saveDir.mkdirs();
                File destinationFile = new File(saveDir, fileInfo.getFileName());
                
                // Download qua RelayClient
                boolean success = relayClient.downloadFile(fileInfo, destinationFile, 
                    new RelayClient.RelayTransferListener() {
                        @Override
                        public void onProgress(RelayTransferProgress progress) {
                            if (listener != null) {
                                listener.onProgress(progress.getTransferredBytes(), progress.getTotalBytes());
                            }
                        }
                        
                        @Override
                        public void onComplete(RelayFileInfo info) {
                            System.out.println("✅ Download qua relay hoàn tất: " + destinationFile.getAbsolutePath());
                            if (listener != null) {
                                listener.onComplete(destinationFile);
                            }
                        }
                        
                        @Override
                        public void onError(Exception e) {
                            System.err.println("❌ Lỗi download qua relay: " + e.getMessage());
                            if (listener != null) {
                                listener.onError(e);
                            }
                        }
                    }
                );
                
                if (!success && listener != null) {
                    listener.onError(new IOException("Download qua relay thất bại"));
                }
                
            } catch (Exception e) {
                System.err.println("❌ Lỗi download qua relay: " + e.getMessage());
                if (listener != null) {
                    listener.onError(e);
                }
            }
        });
    }
    
    // ========== Control methods ==========
    
    /**
     * Tạm dừng download
     */
    public void pauseTransfer(String transferId) {
        for (TransferState state : activeTransfers.values()) {
            if (state.getTransferId().equals(transferId)) {
                state.pause();
                System.out.println("⏸ Đã tạm dừng: " + state.getFileName());
                return;
            }
        }
    }
    
    /**
     * Tiếp tục download
     */
    public void resumeTransfer(String transferId) {
        for (TransferState state : activeTransfers.values()) {
            if (state.getTransferId().equals(transferId)) {
                state.resume();
                System.out.println("▶ Tiếp tục: " + state.getFileName());
                return;
            }
        }
    }
    
    /**
     * Hủy download
     */
    public void cancelTransfer(String transferId) {
        for (Map.Entry<String, TransferState> entry : activeTransfers.entrySet()) {
            if (entry.getValue().getTransferId().equals(transferId)) {
                // Đặt status CANCELLED
                entry.getValue().cancel();
                
                // Cancel task
                Future<?> task = transferTasks.get(entry.getKey());
                if (task != null) {
                    task.cancel(true);
                }
                
                // Xóa file tạm
                File tempFile = new File(entry.getValue().getSaveDirectory(), 
                    entry.getValue().getFileName() + ".part");
                if (tempFile.exists()) {
                    tempFile.delete();
                }
                
                activeTransfers.remove(entry.getKey());
                transferTasks.remove(entry.getKey());
                
                System.out.println("❌ Đã hủy: " + entry.getValue().getFileName());
                return;
            }
        }
    }
    
    /**
     * Pause tất cả active downloads
     */
    public void pauseAllDownloads() {
        for (TransferState state : activeTransfers.values()) {
            if (state.getStatus() == TransferStatus.IN_PROGRESS) {
                state.pause();
            }
        }
        System.out.println("⏸ Đã tạm dừng tất cả downloads");
    }
    
    /**
     * Resume tất cả paused downloads
     */
    public void resumeAllDownloads() {
        for (TransferState state : activeTransfers.values()) {
            if (state.getStatus() == TransferStatus.PAUSED) {
                state.resume();
            }
        }
        System.out.println("▶ Tiếp tục tất cả downloads");
    }
    
    /**
     * Cancel tất cả active downloads
     */
    public void cancelAllDownloads() {
        for (String key : activeTransfers.keySet()) {
            TransferState state = activeTransfers.get(key);
            if (state != null) {
                state.cancel();
                
                Future<?> task = transferTasks.get(key);
                if (task != null) {
                    task.cancel(true);
                }
                
                File tempFile = new File(state.getSaveDirectory(), state.getFileName() + ".part");
                if (tempFile.exists()) {
                    tempFile.delete();
                }
            }
        }
        activeTransfers.clear();
        transferTasks.clear();
        System.out.println("❌ Đã hủy tất cả downloads");
    }
    
    /**
     * Lấy trạng thái transfer
     */
    public TransferState getTransferState(String transferId) {
        for (TransferState state : activeTransfers.values()) {
            if (state.getTransferId().equals(transferId)) {
                return state;
            }
        }
        return null;
    }
    
    /**
     * Lấy first active transfer (để hiển thị trên UI)
     */
    public TransferState getFirstActiveTransfer() {
        for (TransferState state : activeTransfers.values()) {
            if (state.getStatus() == TransferStatus.IN_PROGRESS || 
                state.getStatus() == TransferStatus.PAUSED) {
                return state;
            }
        }
        return null;
    }
    
    /**
     * Lấy tất cả active transfers
     */
    public Map<String, TransferState> getActiveTransfers() {
        return new ConcurrentHashMap<>(activeTransfers);
    }
    
    /**
     * Kiểm tra có active download không
     */
    public boolean hasActiveDownload() {
        return !activeTransfers.isEmpty();
    }
    
    // ========== Upload Methods (từ FileTransferService) ==========
    
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
                System.out.println("📤 Upload file qua relay: " + file.getName());
                
                // Tạo upload request
                String fileHash = org.example.p2psharefile.security.FileHashUtil.calculateSHA256(file);
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
                        System.out.println("✅ Upload relay thành công: " + info.getUploadId());
                    }
                    
                    @Override
                    public void onError(Exception e) {
                        System.err.println("❌ Upload relay thất bại: " + e.getMessage());
                        if (listener != null) {
                            listener.onError(e);
                        }
                    }
                });
                
                if (fileInfo != null && listener != null) {
                    listener.onComplete(file);
                }
                
            } catch (Exception e) {
                System.err.println("❌ Lỗi upload relay: " + e.getMessage());
                if (listener != null) {
                    listener.onError(e);
                }
            }
        });
    }
    
    /**
     * Upload file đến peer qua P2P (stream-based)
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
     * Lấy encryption key (để chia sẻ với peer khác nếu cần)
     */
    public String getEncryptionKeyString() {
        return AESEncryption.keyToString(encryptionKey);
    }
}
