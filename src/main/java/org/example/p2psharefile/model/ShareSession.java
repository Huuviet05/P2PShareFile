package org.example.p2psharefile.model;

import java.io.Serializable;

/**
 * ShareSession - Phiên chia sẻ file với PIN code
 * Giống Send Anywhere: tạo mã 6 số để chia sẻ file
 */
public class ShareSession implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String pin;              // Mã PIN 6 số
    private FileInfo fileInfo;       // File được chia sẻ
    private PeerInfo ownerPeer;      // Peer sở hữu file
    private long createdTime;        // Thời gian tạo
    private long expiryTime;         // Thời gian hết hạn
    private boolean active;          // Còn hoạt động không
    
    public ShareSession(String pin, FileInfo fileInfo, PeerInfo ownerPeer, long expiryTime) {
        this.pin = pin;
        this.fileInfo = fileInfo;
        this.ownerPeer = ownerPeer;
        this.createdTime = System.currentTimeMillis();
        this.expiryTime = expiryTime;
        this.active = true;
    }
    
    // Getters và Setters
    public String getPin() {
        return pin;
    }
    
    public void setPin(String pin) {
        this.pin = pin;
    }
    
    public FileInfo getFileInfo() {
        return fileInfo;
    }
    
    public void setFileInfo(FileInfo fileInfo) {
        this.fileInfo = fileInfo;
    }
    
    public PeerInfo getOwnerPeer() {
        return ownerPeer;
    }
    
    public void setOwnerPeer(PeerInfo ownerPeer) {
        this.ownerPeer = ownerPeer;
    }
    
    public long getCreatedTime() {
        return createdTime;
    }
    
    public void setCreatedTime(long createdTime) {
        this.createdTime = createdTime;
    }
    
    public long getExpiryTime() {
        return expiryTime;
    }
    
    public void setExpiryTime(long expiryTime) {
        this.expiryTime = expiryTime;
    }
    
    public boolean isActive() {
        return active;
    }
    
    public void setActive(boolean active) {
        this.active = active;
    }
    
    /**
     * Kiểm tra session đã hết hạn chưa
     */
    public boolean isExpired() {
        return System.currentTimeMillis() > expiryTime || !active;
    }
    
    /**
     * Lấy thời gian còn lại (giây)
     */
    public long getRemainingSeconds() {
        if (isExpired()) return 0;
        return (expiryTime - System.currentTimeMillis()) / 1000;
    }
    
    /**
     * Lấy thời gian còn lại định dạng MM:SS
     */
    public String getRemainingTimeFormatted() {
        long seconds = getRemainingSeconds();
        long minutes = seconds / 60;
        long secs = seconds % 60;
        return String.format("%02d:%02d", minutes, secs);
    }
    
    /**
     * Hủy session
     */
    public void cancel() {
        this.active = false;
    }
    
    @Override
    public String toString() {
        return "ShareSession{" +
                "pin='" + pin + '\'' +
                ", file=" + fileInfo.getFileName() +
                ", owner=" + ownerPeer.getDisplayName() +
                ", remaining=" + getRemainingTimeFormatted() +
                '}';
    }
}
