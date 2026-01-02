package org.example.p2psharefile.model;

import java.util.BitSet;
import java.util.UUID;

/**
 * TransferState - Quản lý trạng thái transfer với hỗ trợ pause/resume
 * 
 * Đặc điểm:
 * - Theo dõi từng chunk đã nhận/chưa nhận
 * - Tính toán progress, speed, ETA
 * - Hỗ trợ pause/resume/cancel
 * 
 * @author P2PShareFile Team
 * @version 2.0
 */
public class TransferState {
    
    // Default chunk size: 64KB (nhỏ hơn để dễ kiểm soát pause/resume)
    public static final int DEFAULT_CHUNK_SIZE = 64 * 1024;
    
    /**
     * Enum cho trạng thái transfer
     */
    public enum TransferStatus {
        PENDING,        // Chưa bắt đầu
        IN_PROGRESS,    // Đang truyền
        PAUSED,         // Tạm dừng
        COMPLETED,      // Hoàn thành
        CANCELLED,      // Đã hủy
        FAILED          // Lỗi
    }
    
    // Thông tin cơ bản
    private final String transferId;
    private String fileName;
    private String filePath;
    private long fileSize;
    private int chunkSize;
    
    // Trạng thái
    private volatile TransferStatus status;
    private String errorMessage;
    
    // Tracking chunks
    private BitSet receivedChunks;
    private int totalChunks;
    private long bytesTransferred;
    
    // Tracking thời gian
    private long startTime;
    private long lastUpdateTime;
    private long pausedTime;
    private long totalPausedDuration;
    
    // Network info (cho resume)
    private String peerIp;
    private int peerPort;
    private String saveDirectory;
    
    /**
     * Constructor
     */
    public TransferState(String fileName, String filePath, long fileSize) {
        this.transferId = UUID.randomUUID().toString();
        this.fileName = fileName;
        this.filePath = filePath;
        this.fileSize = fileSize;
        this.chunkSize = DEFAULT_CHUNK_SIZE;
        this.status = TransferStatus.PENDING;
        this.bytesTransferred = 0;
        this.totalPausedDuration = 0;
        
        // Tính số chunks
        if (fileSize > 0) {
            this.totalChunks = (int) Math.ceil((double) fileSize / chunkSize);
            this.receivedChunks = new BitSet(totalChunks);
        } else {
            this.totalChunks = 0;
            this.receivedChunks = new BitSet();
        }
    }
    
    // ========== Status Management ==========
    
    /**
     * Bắt đầu transfer
     */
    public void start() {
        this.status = TransferStatus.IN_PROGRESS;
        this.startTime = System.currentTimeMillis();
        this.lastUpdateTime = startTime;
    }
    
    /**
     * Tạm dừng transfer
     */
    public void pause() {
        if (status == TransferStatus.IN_PROGRESS) {
            this.status = TransferStatus.PAUSED;
            this.pausedTime = System.currentTimeMillis();
        }
    }
    
    /**
     * Tiếp tục transfer
     */
    public void resume() {
        if (status == TransferStatus.PAUSED) {
            this.status = TransferStatus.IN_PROGRESS;
            this.totalPausedDuration += System.currentTimeMillis() - pausedTime;
            this.pausedTime = 0;
        }
    }
    
    /**
     * Hủy transfer
     */
    public void cancel() {
        this.status = TransferStatus.CANCELLED;
    }
    
    /**
     * Hoàn thành transfer
     */
    public void complete() {
        this.status = TransferStatus.COMPLETED;
    }
    
    /**
     * Đánh dấu lỗi
     */
    public void fail(String errorMessage) {
        this.status = TransferStatus.FAILED;
        this.errorMessage = errorMessage;
    }
    
    // ========== Chunk Tracking ==========
    
    /**
     * Đánh dấu chunk đã nhận
     */
    public void markChunkReceived(int chunkIndex, int chunkBytes) {
        if (chunkIndex >= 0 && chunkIndex < totalChunks) {
            if (!receivedChunks.get(chunkIndex)) {
                receivedChunks.set(chunkIndex);
                bytesTransferred += chunkBytes;
                lastUpdateTime = System.currentTimeMillis();
            }
        }
    }
    
    /**
     * Kiểm tra chunk đã nhận chưa
     */
    public boolean isChunkReceived(int chunkIndex) {
        return chunkIndex >= 0 && chunkIndex < totalChunks && receivedChunks.get(chunkIndex);
    }
    
    /**
     * Lấy chunk tiếp theo cần download
     */
    public int getNextMissingChunk() {
        return receivedChunks.nextClearBit(0);
    }
    
    /**
     * Số chunks đã nhận
     */
    public int getReceivedChunkCount() {
        return receivedChunks.cardinality();
    }
    
    /**
     * Kiểm tra đã hoàn thành chưa
     */
    public boolean isComplete() {
        return receivedChunks.cardinality() >= totalChunks;
    }
    
    /**
     * Lấy offset của chunk trong file
     */
    public long getChunkOffset(int chunkIndex) {
        return (long) chunkIndex * chunkSize;
    }
    
    // ========== Progress Calculations ==========
    
    /**
     * Lấy phần trăm hoàn thành
     */
    public int getProgressPercent() {
        if (fileSize <= 0) return 0;
        return (int) (bytesTransferred * 100 / fileSize);
    }
    
    /**
     * Lấy progress (0.0 - 1.0)
     */
    public double getProgress() {
        if (fileSize <= 0) return 0;
        return (double) bytesTransferred / fileSize;
    }
    
    /**
     * Tính tốc độ (bytes/giây)
     */
    public double getSpeedBytesPerSecond() {
        long activeTime = getActiveTransferTime();
        if (activeTime <= 0) return 0;
        return (bytesTransferred * 1000.0) / activeTime;
    }
    
    /**
     * Lấy thời gian đã truyền (không tính pause)
     */
    public long getActiveTransferTime() {
        if (startTime == 0) return 0;
        long elapsed = System.currentTimeMillis() - startTime;
        
        // Trừ đi thời gian pause
        long pauseDuration = totalPausedDuration;
        if (status == TransferStatus.PAUSED && pausedTime > 0) {
            pauseDuration += System.currentTimeMillis() - pausedTime;
        }
        
        return elapsed - pauseDuration;
    }
    
    /**
     * Ước tính thời gian còn lại (giây)
     */
    public long getEstimatedTimeRemaining() {
        double speed = getSpeedBytesPerSecond();
        if (speed <= 0) return -1;
        
        long remaining = fileSize - bytesTransferred;
        return (long) (remaining / speed);
    }
    
    // ========== Getters & Setters ==========
    
    public String getTransferId() {
        return transferId;
    }
    
    public String getFileName() {
        return fileName;
    }
    
    public void setFileName(String fileName) {
        this.fileName = fileName;
    }
    
    public String getFilePath() {
        return filePath;
    }
    
    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }
    
    public long getFileSize() {
        return fileSize;
    }
    
    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
        // Recalculate chunks
        if (fileSize > 0) {
            this.totalChunks = (int) Math.ceil((double) fileSize / chunkSize);
            this.receivedChunks = new BitSet(totalChunks);
        }
    }
    
    public int getChunkSize() {
        return chunkSize;
    }
    
    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
        // Recalculate chunks
        if (fileSize > 0) {
            this.totalChunks = (int) Math.ceil((double) fileSize / chunkSize);
            this.receivedChunks = new BitSet(totalChunks);
        }
    }
    
    public TransferStatus getStatus() {
        return status;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public int getTotalChunks() {
        return totalChunks;
    }
    
    public long getBytesTransferred() {
        return bytesTransferred;
    }
    
    public void setBytesTransferred(long bytesTransferred) {
        this.bytesTransferred = bytesTransferred;
    }
    
    public String getPeerIp() {
        return peerIp;
    }
    
    public void setPeerIp(String peerIp) {
        this.peerIp = peerIp;
    }
    
    public int getPeerPort() {
        return peerPort;
    }
    
    public void setPeerPort(int peerPort) {
        this.peerPort = peerPort;
    }
    
    public String getSaveDirectory() {
        return saveDirectory;
    }
    
    public void setSaveDirectory(String saveDirectory) {
        this.saveDirectory = saveDirectory;
    }
    
    public long getStartTime() {
        return startTime;
    }
    
    @Override
    public String toString() {
        return String.format("TransferState{id=%s, file=%s, status=%s, progress=%d%%, chunks=%d/%d}",
            transferId.substring(0, 8), fileName, status, getProgressPercent(),
            getReceivedChunkCount(), totalChunks);
    }
}
