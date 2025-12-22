package org.example.p2psharefile.model;

import java.io.Serializable;

/**
 * RelayUploadRequest - Yêu cầu upload file lên Relay Server
 * 
 * Sử dụng khi:
 * - Kết nối P2P LAN thất bại
 * - Peer ở các mạng khác nhau (qua Internet)
 * - Người dùng chọn force relay mode
 * 
 * Flow:
 * 1. Client tạo RelayUploadRequest với thông tin file
 * 2. Upload file lên relay server (chunked, không mã hóa)
 * 3. Server trả về RelayFileInfo với uploadId và downloadUrl
 * 4. Sender gửi RelayFileInfo cho recipient qua PIN code
 * 
 * LƯU Ý: Relay mode KHÔNG mã hóa file, chỉ dựa vào HTTPS của hosting provider.
 * 
 * @author P2PShareFile Team
 * @version 1.0
 */
public class RelayUploadRequest implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private String senderId;           // ID của peer gửi file
    private String senderName;         // Tên hiển thị của sender
    private String recipientId;        // ID của peer nhận file (optional, có thể null nếu share public)
    private String fileName;           // Tên file gốc
    private long fileSize;             // Kích thước file (bytes)
    private String fileHash;           // Hash SHA-256 của file (metadata, không verify)
    
    // UNUSED: Các trường encryption không sử dụng (giữ lại để tương thích serialize)
    private boolean encrypted;         // KHÔNG SỬ DỤNG - Relay mode không mã hóa file
    private String encryptionAlgorithm; // KHÔNG SỬ DỤNG
    private long expiryTime;           // Thời gian hết hạn (unix timestamp), 0 = không hết hạn
    private String description;        // Mô tả file (optional)
    
    // Metadata bổ sung
    private String mimeType;           // MIME type của file
    private int chunkSize;             // Kích thước chunk để upload (bytes)
    
    public RelayUploadRequest() {
        this.encrypted = false;
        this.expiryTime = 0;
        this.chunkSize = 1024 * 1024; // Default 1MB chunks
    }
    
    public RelayUploadRequest(String senderId, String senderName, String fileName, 
                              long fileSize, String fileHash) {
        this();
        this.senderId = senderId;
        this.senderName = senderName;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.fileHash = fileHash;
    }
    
    // Getters and Setters
    
    public String getSenderId() {
        return senderId;
    }
    
    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }
    
    public String getSenderName() {
        return senderName;
    }
    
    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }
    
    public String getRecipientId() {
        return recipientId;
    }
    
    public void setRecipientId(String recipientId) {
        this.recipientId = recipientId;
    }
    
    public String getFileName() {
        return fileName;
    }
    
    public void setFileName(String fileName) {
        this.fileName = fileName;
    }
    
    public long getFileSize() {
        return fileSize;
    }
    
    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }
    
    public String getFileHash() {
        return fileHash;
    }
    
    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }
    
    public boolean isEncrypted() {
        return encrypted;
    }
    
    public void setEncrypted(boolean encrypted) {
        this.encrypted = encrypted;
    }
    
    public String getEncryptionAlgorithm() {
        return encryptionAlgorithm;
    }
    
    public void setEncryptionAlgorithm(String encryptionAlgorithm) {
        this.encryptionAlgorithm = encryptionAlgorithm;
    }
    
    public long getExpiryTime() {
        return expiryTime;
    }
    
    public void setExpiryTime(long expiryTime) {
        this.expiryTime = expiryTime;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public String getMimeType() {
        return mimeType;
    }
    
    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }
    
    public int getChunkSize() {
        return chunkSize;
    }
    
    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }
    
    @Override
    public String toString() {
        return String.format("RelayUploadRequest{senderId='%s', senderName='%s', fileName='%s', " +
                           "fileSize=%d, encrypted=%b, recipientId='%s'}", 
                           senderId, senderName, fileName, fileSize, encrypted, recipientId);
    }
}
