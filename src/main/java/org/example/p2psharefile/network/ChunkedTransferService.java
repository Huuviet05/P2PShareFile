package org.example.p2psharefile.network;

import org.example.p2psharefile.compression.FileCompression;
import org.example.p2psharefile.security.AESEncryption;
import org.example.p2psharefile.security.SecurityManager;
import org.example.p2psharefile.security.FileHashUtil;
import org.example.p2psharefile.model.FileInfo;
import org.example.p2psharefile.model.PeerInfo;

import javax.crypto.SecretKey;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * ChunkedTransferService - Truyền file theo chunks với hỗ trợ resume
 * 
 * Khác biệt với stream-based:
 * - File được chia thành nhiều chunks nhỏ (mặc định 256KB)
 * - Mỗi chunk được mã hóa riêng và gửi độc lập
 * - Client có thể resume từ chunk cuối cùng đã nhận
 * - Không cần load toàn bộ file vào RAM
 * 
 * Protocol:
 * 1. Client gửi: REQUEST, filePath, startChunk
 * 2. Server trả: SUCCESS, fileName, totalSize, totalChunks, chunkSize
 * 3. Loop: Server gửi chunkIndex + chunkData, Client xác nhận
 * 4. Server gửi: COMPLETE
 * 
 * @author P2PShareFile Team
 * @version 2.0 - Chunk-based with Resume
 */
public class ChunkedTransferService {
    
    private static final Logger LOGGER = Logger.getLogger(ChunkedTransferService.class.getName());
    
    // Chunk configuration
    public static final int DEFAULT_CHUNK_SIZE = 256 * 1024;  // 256KB chunks
    public static final int BUFFER_SIZE = 8192;               // 8KB read buffer
    private static final String DEFAULT_KEY = "P2PShareFileSecretKey123456789";
    
    // Protocol messages
    private static final String MSG_REQUEST = "CHUNKED_REQUEST";
    private static final String MSG_SUCCESS = "CHUNKED_SUCCESS";
    private static final String MSG_ERROR = "CHUNKED_ERROR";
    private static final String MSG_CHUNK = "CHUNK";
    private static final String MSG_ACK = "ACK";
    private static final String MSG_COMPLETE = "COMPLETE";
    private static final String MSG_PAUSE = "PAUSE";
    private static final String MSG_RESUME = "RESUME";
    private static final String MSG_CANCEL = "CANCEL";
    
    private final PeerInfo localPeer;
    private final SecurityManager securityManager;
    private final SecretKey encryptionKey;
    private final int chunkSize;
    
    private SSLServerSocket serverSocket;
    private ExecutorService executorService;
    private volatile boolean running = false;
    
    // Download state for pause/resume
    private final ConcurrentHashMap<String, DownloadState> activeDownloads = new ConcurrentHashMap<>();
    
    /**
     * Trạng thái download để hỗ trợ pause/resume
     */
    public static class DownloadState {
        private final String fileId;
        private final String fileName;
        private final long totalSize;
        private final int totalChunks;
        private final File tempFile;
        private final File destFile;
        
        private volatile int currentChunk;
        private volatile long bytesReceived;
        private volatile boolean paused;
        private volatile boolean cancelled;
        private volatile long startTime;
        private volatile long lastUpdateTime;
        
        public DownloadState(String fileId, String fileName, long totalSize, int totalChunks,
                             File tempFile, File destFile) {
            this.fileId = fileId;
            this.fileName = fileName;
            this.totalSize = totalSize;
            this.totalChunks = totalChunks;
            this.tempFile = tempFile;
            this.destFile = destFile;
            this.currentChunk = 0;
            this.bytesReceived = 0;
            this.paused = false;
            this.cancelled = false;
            this.startTime = System.currentTimeMillis();
            this.lastUpdateTime = startTime;
        }
        
        // Getters
        public String getFileId() { return fileId; }
        public String getFileName() { return fileName; }
        public long getTotalSize() { return totalSize; }
        public int getTotalChunks() { return totalChunks; }
        public File getTempFile() { return tempFile; }
        public File getDestFile() { return destFile; }
        public int getCurrentChunk() { return currentChunk; }
        public long getBytesReceived() { return bytesReceived; }
        public boolean isPaused() { return paused; }
        public boolean isCancelled() { return cancelled; }
        
        // Setters
        public void setCurrentChunk(int chunk) { this.currentChunk = chunk; }
        public void setBytesReceived(long bytes) { this.bytesReceived = bytes; }
        public void setPaused(boolean paused) { this.paused = paused; }
        public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
        
        // Progress calculations
        public double getProgress() {
            return totalSize > 0 ? (double) bytesReceived / totalSize : 0;
        }
        
        public int getPercentComplete() {
            return (int) (getProgress() * 100);
        }
        
        public double getSpeedBytesPerSecond() {
            long elapsed = System.currentTimeMillis() - startTime;
            return elapsed > 0 ? (bytesReceived * 1000.0 / elapsed) : 0;
        }
        
        public long getEstimatedSecondsRemaining() {
            double speed = getSpeedBytesPerSecond();
            if (speed > 0) {
                return (long) ((totalSize - bytesReceived) / speed);
            }
            return -1;
        }
        
        public void updateLastTime() {
            this.lastUpdateTime = System.currentTimeMillis();
        }
    }
    
    /**
     * Interface callback cho transfer progress
     */
    public interface ChunkedTransferListener {
        void onProgress(String fileId, long bytesTransferred, long totalBytes, 
                        int currentChunk, int totalChunks, double speed, long etaSeconds);
        void onComplete(String fileId, File file);
        void onError(String fileId, Exception e);
        void onPaused(String fileId, long bytesTransferred, long totalBytes);
        void onResumed(String fileId, long bytesTransferred, long totalBytes);
        void onCancelled(String fileId);
    }
    
    /**
     * Constructor
     */
    public ChunkedTransferService(PeerInfo localPeer, SecurityManager securityManager) {
        this(localPeer, securityManager, DEFAULT_CHUNK_SIZE);
    }
    
    public ChunkedTransferService(PeerInfo localPeer, SecurityManager securityManager, int chunkSize) {
        this.localPeer = localPeer;
        this.securityManager = securityManager;
        this.chunkSize = chunkSize;
        this.encryptionKey = AESEncryption.createKeyFromString(DEFAULT_KEY);
    }
    
    /**
     * Start server để lắng nghe yêu cầu download
     */
    public void startServer(int port) throws IOException {
        if (running) return;
        
        running = true;
        serverSocket = securityManager.createSSLServerSocket(port);
        
        int actualPort = serverSocket.getLocalPort();
        localPeer.setPort(actualPort);
        
        executorService = Executors.newCachedThreadPool();
        executorService.submit(this::acceptConnections);
        
        LOGGER.info("✓ Chunked Transfer Server started on port " + actualPort);
    }
    
    /**
     * Stop server
     */
    public void stop() {
        running = false;
        
        // Cancel all active downloads
        activeDownloads.values().forEach(state -> state.setCancelled(true));
        activeDownloads.clear();
        
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
        
        LOGGER.info("✓ Chunked Transfer Server stopped");
    }
    
    /**
     * Accept incoming connections
     */
    private void acceptConnections() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                executorService.submit(() -> handleClientRequest(client));
            } catch (SocketException e) {
                if (running) {
                    LOGGER.warning("Socket exception: " + e.getMessage());
                }
                break;
            } catch (IOException e) {
                if (running) {
                    LOGGER.warning("Accept error: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Handle upload request from client
     */
    private void handleClientRequest(Socket socket) {
        try (DataInputStream dis = new DataInputStream(socket.getInputStream());
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {
            
            // Read request
            String msgType = dis.readUTF();
            if (!MSG_REQUEST.equals(msgType)) {
                dos.writeUTF(MSG_ERROR);
                dos.writeUTF("Invalid request type");
                return;
            }
            
            String filePath = dis.readUTF();
            int startChunk = dis.readInt();  // Resume from this chunk
            
            File file = new File(filePath);
            if (!file.exists() || !file.isFile()) {
                dos.writeUTF(MSG_ERROR);
                dos.writeUTF("File không tồn tại");
                return;
            }
            
            LOGGER.info("📤 Chunked upload: " + file.getName() + " (từ chunk " + startChunk + ")");
            
            // Calculate chunks
            long fileSize = file.length();
            int totalChunks = (int) Math.ceil((double) fileSize / chunkSize);
            
            // Send success response with metadata
            dos.writeUTF(MSG_SUCCESS);
            dos.writeUTF(file.getName());
            dos.writeLong(fileSize);
            dos.writeInt(totalChunks);
            dos.writeInt(chunkSize);
            dos.flush();
            
            // Send chunks
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                byte[] buffer = new byte[chunkSize];
                
                for (int i = startChunk; i < totalChunks; i++) {
                    // Seek to chunk position
                    long pos = (long) i * chunkSize;
                    raf.seek(pos);
                    
                    // Read chunk
                    int bytesToRead = (int) Math.min(chunkSize, fileSize - pos);
                    int bytesRead = raf.read(buffer, 0, bytesToRead);
                    
                    if (bytesRead <= 0) break;
                    
                    // Encrypt chunk
                    byte[] chunkData = new byte[bytesRead];
                    System.arraycopy(buffer, 0, chunkData, 0, bytesRead);
                    byte[] encryptedChunk = AESEncryption.encrypt(chunkData, encryptionKey);
                    
                    // Send chunk
                    dos.writeUTF(MSG_CHUNK);
                    dos.writeInt(i);                          // Chunk index
                    dos.writeInt(encryptedChunk.length);      // Encrypted size
                    dos.write(encryptedChunk);
                    dos.flush();
                    
                    // Wait for ACK
                    String ack = dis.readUTF();
                    if (MSG_PAUSE.equals(ack)) {
                        LOGGER.info("⏸ Client paused at chunk " + i);
                        // Wait for resume or cancel
                        String resumeMsg = dis.readUTF();
                        if (MSG_CANCEL.equals(resumeMsg)) {
                            LOGGER.info("❌ Client cancelled download");
                            return;
                        }
                        // Continue if RESUME
                    } else if (MSG_CANCEL.equals(ack)) {
                        LOGGER.info("❌ Client cancelled download");
                        return;
                    }
                    // ACK received, continue
                }
                
                // Send complete
                dos.writeUTF(MSG_COMPLETE);
                dos.flush();
                
                LOGGER.info("✅ Upload complete: " + file.getName());
            }
            
        } catch (Exception e) {
            LOGGER.severe("Error in chunked upload: " + e.getMessage());
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
     * Download file từ peer với chunk-based và resume support
     */
    public void downloadFile(PeerInfo peer, FileInfo fileInfo, String saveDirectory,
                             ChunkedTransferListener listener) {
        
        String fileId = fileInfo.getFileHash() != null ? 
                        fileInfo.getFileHash() : 
                        String.valueOf(System.currentTimeMillis());
        
        executorService.submit(() -> {
            SSLSocket socket = null;
            try {
                LOGGER.info("📥 Chunked download: " + fileInfo.getFileName() + " từ " + peer);
                
                // Check for existing download state (resume)
                DownloadState existingState = activeDownloads.get(fileId);
                int startChunk = 0;
                
                if (existingState != null) {
                    startChunk = existingState.getCurrentChunk();
                    LOGGER.info("📍 Resuming from chunk " + startChunk);
                }
                
                // Connect to peer
                socket = securityManager.createSSLSocket(peer.getIpAddress(), peer.getPort());
                socket.connect(new InetSocketAddress(peer.getIpAddress(), peer.getPort()), 5000);
                socket.setSoTimeout(60000);
                socket.startHandshake();
                
                DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                DataInputStream dis = new DataInputStream(socket.getInputStream());
                
                // Send request
                dos.writeUTF(MSG_REQUEST);
                dos.writeUTF(fileInfo.getFilePath());
                dos.writeInt(startChunk);
                dos.flush();
                
                // Read response
                String msgType = dis.readUTF();
                if (MSG_ERROR.equals(msgType)) {
                    String error = dis.readUTF();
                    throw new IOException("Server error: " + error);
                }
                
                // Parse metadata
                String fileName = dis.readUTF();
                long totalSize = dis.readLong();
                int totalChunks = dis.readInt();
                int serverChunkSize = dis.readInt();
                
                LOGGER.info(String.format("📦 File: %s, Size: %d, Chunks: %d", 
                            fileName, totalSize, totalChunks));
                
                // Setup destination
                File saveDir = new File(saveDirectory);
                if (!saveDir.exists()) saveDir.mkdirs();
                
                File destFile = new File(saveDir, fileName);
                File tempFile = new File(saveDir, fileName + ".part");
                
                // Create or reuse download state
                DownloadState state;
                if (existingState != null && existingState.getFileName().equals(fileName)) {
                    state = existingState;
                    state.setPaused(false);
                    state.setCancelled(false);
                } else {
                    state = new DownloadState(fileId, fileName, totalSize, totalChunks, tempFile, destFile);
                    activeDownloads.put(fileId, state);
                    
                    // If resuming but file changed, start fresh
                    if (startChunk > 0 && tempFile.exists()) {
                        // Calculate expected position
                        long expectedPos = (long) startChunk * serverChunkSize;
                        if (tempFile.length() != expectedPos) {
                            LOGGER.warning("Temp file size mismatch, starting fresh");
                            tempFile.delete();
                            startChunk = 0;
                            state.setCurrentChunk(0);
                            state.setBytesReceived(0);
                        }
                    }
                }
                
                // Open file for writing (append if resuming)
                try (RandomAccessFile raf = new RandomAccessFile(tempFile, "rw")) {
                    // Seek to resume position
                    if (startChunk > 0) {
                        long resumePos = tempFile.length();
                        raf.seek(resumePos);
                        state.setBytesReceived(resumePos);
                    }
                    
                    // Receive chunks
                    while (true) {
                        // Check cancel
                        if (state.isCancelled()) {
                            dos.writeUTF(MSG_CANCEL);
                            dos.flush();
                            LOGGER.info("❌ Download cancelled by user");
                            if (listener != null) listener.onCancelled(fileId);
                            activeDownloads.remove(fileId);
                            return;
                        }
                        
                        // Check pause
                        while (state.isPaused() && !state.isCancelled()) {
                            if (listener != null) {
                                listener.onPaused(fileId, state.getBytesReceived(), totalSize);
                            }
                            Thread.sleep(500);
                        }
                        
                        if (state.isCancelled()) continue;
                        
                        // Read message type
                        String msg = dis.readUTF();
                        
                        if (MSG_COMPLETE.equals(msg)) {
                            break;
                        }
                        
                        if (!MSG_CHUNK.equals(msg)) {
                            throw new IOException("Unexpected message: " + msg);
                        }
                        
                        // Read chunk
                        int chunkIndex = dis.readInt();
                        int encryptedSize = dis.readInt();
                        byte[] encryptedData = new byte[encryptedSize];
                        dis.readFully(encryptedData);
                        
                        // Decrypt chunk
                        byte[] decryptedData = AESEncryption.decrypt(encryptedData, encryptionKey);
                        
                        // Write to file
                        raf.write(decryptedData);
                        
                        // Update state
                        state.setCurrentChunk(chunkIndex + 1);
                        state.setBytesReceived(state.getBytesReceived() + decryptedData.length);
                        state.updateLastTime();
                        
                        // Notify progress
                        if (listener != null) {
                            listener.onProgress(
                                fileId,
                                state.getBytesReceived(),
                                totalSize,
                                chunkIndex + 1,
                                totalChunks,
                                state.getSpeedBytesPerSecond(),
                                state.getEstimatedSecondsRemaining()
                            );
                        }
                        
                        // Send ACK (or PAUSE if paused)
                        if (state.isPaused()) {
                            dos.writeUTF(MSG_PAUSE);
                        } else {
                            dos.writeUTF(MSG_ACK);
                        }
                        dos.flush();
                    }
                }
                
                // Rename temp file to final
                if (tempFile.exists()) {
                    Files.move(tempFile.toPath(), destFile.toPath(), 
                              StandardCopyOption.REPLACE_EXISTING);
                }
                
                // Complete
                activeDownloads.remove(fileId);
                LOGGER.info("✅ Download complete: " + destFile.getAbsolutePath());
                
                if (listener != null) {
                    listener.onComplete(fileId, destFile);
                }
                
            } catch (Exception e) {
                LOGGER.severe("❌ Download error: " + e.getMessage());
                e.printStackTrace();
                if (listener != null) {
                    listener.onError(fileId, e);
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
        });
    }
    
    /**
     * Pause a download
     */
    public void pauseDownload(String fileId) {
        DownloadState state = activeDownloads.get(fileId);
        if (state != null) {
            state.setPaused(true);
            LOGGER.info("⏸ Paused download: " + state.getFileName());
        }
    }
    
    /**
     * Resume a download
     */
    public void resumeDownload(String fileId, PeerInfo peer, FileInfo fileInfo,
                               String saveDirectory, ChunkedTransferListener listener) {
        DownloadState state = activeDownloads.get(fileId);
        if (state != null && state.isPaused()) {
            state.setPaused(false);
            LOGGER.info("▶ Resumed download: " + state.getFileName());
            if (listener != null) {
                listener.onResumed(fileId, state.getBytesReceived(), state.getTotalSize());
            }
        } else {
            // Try to resume from temp file
            downloadFile(peer, fileInfo, saveDirectory, listener);
        }
    }
    
    /**
     * Cancel a download
     */
    public void cancelDownload(String fileId) {
        DownloadState state = activeDownloads.get(fileId);
        if (state != null) {
            state.setCancelled(true);
            state.setPaused(false); // Wake up if paused
            LOGGER.info("❌ Cancelled download: " + state.getFileName());
            
            // Delete temp file
            File tempFile = state.getTempFile();
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }
    
    /**
     * Get active download state
     */
    public DownloadState getDownloadState(String fileId) {
        return activeDownloads.get(fileId);
    }
    
    /**
     * Check if a download is active
     */
    public boolean hasActiveDownload(String fileId) {
        return activeDownloads.containsKey(fileId);
    }
    
    /**
     * Get all active downloads
     */
    public ConcurrentHashMap<String, DownloadState> getActiveDownloads() {
        return activeDownloads;
    }
}
