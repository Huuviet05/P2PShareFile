package org.example.p2psharefile.model;

import java.io.Serializable;

/**
 * RelayTransferProgress - Thông tin tiến độ upload/download qua relay
 * 
 * Dùng để:
 * - Hiển thị progress bar trong UI
 * - Log thông tin transfer
 * - Tính toán tốc độ truyền
 * - Hỗ trợ resume khi bị ngắt
 * 
 * @author P2PShareFile Team
 * @version 1.0
 */
public class RelayTransferProgress implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    public enum TransferType {
        UPLOAD,
        DOWNLOAD
    }
    
    public enum TransferStatus {
        PENDING,        // Đang chờ
        IN_PROGRESS,    // Đang truyền
        PAUSED,         // Tạm dừng
        COMPLETED,      // Hoàn thành
        FAILED,         // Thất bại
        CANCELLED       // Bị hủy
    }
    
    private String transferId;          // ID duy nhất của transfer session
    private TransferType type;          // UPLOAD hoặc DOWNLOAD
    private TransferStatus status;      // Trạng thái hiện tại
    private String fileName;            // Tên file
    private long totalBytes;            // Tổng số bytes
    private long transferredBytes;      // Số bytes đã truyền
    private long startTime;             // Thời gian bắt đầu (unix timestamp)
    private long lastUpdateTime;        // Thời gian update cuối
    private double speed;               // Tốc độ hiện tại (bytes/second)
    private long estimatedTimeRemaining; // Thời gian còn lại ước tính (milliseconds)
    private String errorMessage;        // Thông báo lỗi nếu failed
    
    // Resume support
    private int currentChunk;           // Chunk hiện tại (bắt đầu từ 0)
    private int totalChunks;            // Tổng số chunks
    private String resumeToken;         // Token để resume transfer
    
    public RelayTransferProgress() {
        this.status = TransferStatus.PENDING;
        this.startTime = System.currentTimeMillis();
        this.lastUpdateTime = startTime;
        this.transferredBytes = 0;
        this.speed = 0;
        this.currentChunk = 0;
    }
    
    public RelayTransferProgress(String transferId, TransferType type, String fileName, long totalBytes) {
        this();
        this.transferId = transferId;
        this.type = type;
        this.fileName = fileName;
        this.totalBytes = totalBytes;
    }
    
    /**
     * Cập nhật tiến độ transfer
     * @param bytesTransferred Số bytes đã truyền
     */
    public void updateProgress(long bytesTransferred) {
        long now = System.currentTimeMillis();
        long timeDelta = now - lastUpdateTime;
        
        if (timeDelta > 0) {
            long bytesDelta = bytesTransferred - this.transferredBytes;
            this.speed = (bytesDelta * 1000.0) / timeDelta; // bytes/second
            
            // Tính thời gian còn lại
            long bytesRemaining = totalBytes - bytesTransferred;
            if (speed > 0) {
                this.estimatedTimeRemaining = (long) ((bytesRemaining / speed) * 1000);
            }
        }
        
        this.transferredBytes = bytesTransferred;
        this.lastUpdateTime = now;
        
        // Tự động chuyển sang IN_PROGRESS nếu đang PENDING
        if (this.status == TransferStatus.PENDING && bytesTransferred > 0) {
            this.status = TransferStatus.IN_PROGRESS;
        }
        
        // Tự động chuyển sang COMPLETED nếu đã truyền hết
        if (bytesTransferred >= totalBytes && this.status == TransferStatus.IN_PROGRESS) {
            this.status = TransferStatus.COMPLETED;
        }
    }
    
    /**
     * Tính phần trăm hoàn thành
     * @return Phần trăm (0-100)
     */
    public double getPercentage() {
        if (totalBytes == 0) return 0;
        return (transferredBytes * 100.0) / totalBytes;
    }
    
    /**
     * Format tốc độ thành string dễ đọc
     * @return VD: "1.5 MB/s"
     */
    public String getFormattedSpeed() {
        return formatBytes((long) speed) + "/s";
    }
    
    /**
     * Format thời gian còn lại thành string dễ đọc
     * @return VD: "2m 30s"
     */
    public String getFormattedTimeRemaining() {
        if (estimatedTimeRemaining <= 0) return "Calculating...";
        
        long seconds = estimatedTimeRemaining / 1000;
        if (seconds < 60) return seconds + "s";
        
        long minutes = seconds / 60;
        seconds = seconds % 60;
        if (minutes < 60) return minutes + "m " + seconds + "s";
        
        long hours = minutes / 60;
        minutes = minutes % 60;
        return hours + "h " + minutes + "m";
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
    
    // Getters and Setters
    
    public String getTransferId() {
        return transferId;
    }
    
    public void setTransferId(String transferId) {
        this.transferId = transferId;
    }
    
    public TransferType getType() {
        return type;
    }
    
    public void setType(TransferType type) {
        this.type = type;
    }
    
    public TransferStatus getStatus() {
        return status;
    }
    
    public void setStatus(TransferStatus status) {
        this.status = status;
    }
    
    public String getFileName() {
        return fileName;
    }
    
    public void setFileName(String fileName) {
        this.fileName = fileName;
    }
    
    public long getTotalBytes() {
        return totalBytes;
    }
    
    public void setTotalBytes(long totalBytes) {
        this.totalBytes = totalBytes;
    }
    
    public long getTransferredBytes() {
        return transferredBytes;
    }
    
    public void setTransferredBytes(long transferredBytes) {
        this.transferredBytes = transferredBytes;
    }
    
    public long getStartTime() {
        return startTime;
    }
    
    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }
    
    public long getLastUpdateTime() {
        return lastUpdateTime;
    }
    
    public void setLastUpdateTime(long lastUpdateTime) {
        this.lastUpdateTime = lastUpdateTime;
    }
    
    public double getSpeed() {
        return speed;
    }
    
    public void setSpeed(double speed) {
        this.speed = speed;
    }
    
    public long getEstimatedTimeRemaining() {
        return estimatedTimeRemaining;
    }
    
    public void setEstimatedTimeRemaining(long estimatedTimeRemaining) {
        this.estimatedTimeRemaining = estimatedTimeRemaining;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
    
    public int getCurrentChunk() {
        return currentChunk;
    }
    
    public void setCurrentChunk(int currentChunk) {
        this.currentChunk = currentChunk;
    }
    
    public int getTotalChunks() {
        return totalChunks;
    }
    
    public void setTotalChunks(int totalChunks) {
        this.totalChunks = totalChunks;
    }
    
    public String getResumeToken() {
        return resumeToken;
    }
    
    public void setResumeToken(String resumeToken) {
        this.resumeToken = resumeToken;
    }
    
    @Override
    public String toString() {
        return String.format("RelayTransferProgress{type=%s, fileName='%s', status=%s, " +
                           "progress=%.1f%%, speed=%s, timeRemaining=%s}", 
                           type, fileName, status, getPercentage(), getFormattedSpeed(), 
                           getFormattedTimeRemaining());
    }
}
