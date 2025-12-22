package org.example.p2psharefile.model;

import java.io.Serializable;

/**
 * RelayFileInfo - Thông tin file đã upload lên Relay Server
 * 
 * Được trả về sau khi upload thành công, chứa:
 * - uploadId: ID duy nhất của file trên server
 * - downloadUrl: URL để download file
 * - expiryTime: Thời gian hết hạn
 * 
 * Sender sẽ gửi RelayFileInfo này cho recipient qua signaling channel
 * (hoặc PIN code) để recipient có thể download file.
 * 
 * LƯU Ý: Relay mode KHÔNG mã hóa file, chỉ dựa vào HTTPS của hosting provider.
 * 
 * @author P2PShareFile Team
 * @version 1.0
 */
public class RelayFileInfo implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private String uploadId;           // ID duy nhất của file trên relay server
    private String fileName;           // Tên file gốc
    private long fileSize;             // Kích thước file (bytes)
    private String fileHash;           // Hash SHA-256 để verify
    private String downloadUrl;        // URL để download (có thể là presigned URL)
    private String senderId;           // ID của peer đã upload
    private String senderName;         // Tên người gửi
    private String recipientId;        // ID của peer được phép download (null = public)
    private long uploadTime;           // Thời gian upload (unix timestamp)
    private long expiryTime;           // Thời gian hết hạn (unix timestamp), 0 = không hết hạn
    
    // UNUSED: Các trường encryption không sử dụng (giữ lại để tương thích serialize)
    private boolean encrypted;         // KHÔNG SỬ DỤNG - Relay mode không mã hóa file
    private String encryptionAlgorithm; // KHÔNG SỬ DỤNG
    private String encryptionKey;      // KHÔNG SỬ DỤNG
    
    // Metadata
    private String mimeType;           // MIME type
    private String description;        // Mô tả file
    private int downloadCount;         // Số lần đã download
    private int maxDownloads;          // Số lần download tối đa (-1 = không giới hạn)
    
    public RelayFileInfo() {
        this.uploadTime = System.currentTimeMillis();
        this.downloadCount = 0;
        this.maxDownloads = -1;
        this.encrypted = false;
        this.expiryTime = 0;
    }
    
    public RelayFileInfo(String uploadId, String fileName, long fileSize, String fileHash, String downloadUrl) {
        this();
        this.uploadId = uploadId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.fileHash = fileHash;
        this.downloadUrl = downloadUrl;
    }
    
    // Kiểm tra file có hết hạn chưa
    public boolean isExpired() {
        if (expiryTime == 0) return false;
        return System.currentTimeMillis() > expiryTime;
    }
    
    // Kiểm tra đã vượt quá số lần download cho phép
    public boolean isDownloadLimitReached() {
        if (maxDownloads < 0) return false;
        return downloadCount >= maxDownloads;
    }
    
    // Tăng download count
    public void incrementDownloadCount() {
        this.downloadCount++;
    }
    
    // Getters and Setters
    
    public String getUploadId() {
        return uploadId;
    }
    
    public void setUploadId(String uploadId) {
        this.uploadId = uploadId;
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
    
    public String getDownloadUrl() {
        return downloadUrl;
    }
    
    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }
    
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
    
    public long getUploadTime() {
        return uploadTime;
    }
    
    public void setUploadTime(long uploadTime) {
        this.uploadTime = uploadTime;
    }
    
    public long getExpiryTime() {
        return expiryTime;
    }
    
    public void setExpiryTime(long expiryTime) {
        this.expiryTime = expiryTime;
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
    
    public String getEncryptionKey() {
        return encryptionKey;
    }
    
    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }
    
    public String getMimeType() {
        return mimeType;
    }
    
    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public int getDownloadCount() {
        return downloadCount;
    }
    
    public void setDownloadCount(int downloadCount) {
        this.downloadCount = downloadCount;
    }
    
    public int getMaxDownloads() {
        return maxDownloads;
    }
    
    public void setMaxDownloads(int maxDownloads) {
        this.maxDownloads = maxDownloads;
    }
    
    @Override
    public String toString() {
        return String.format("RelayFileInfo{uploadId='%s', fileName='%s', fileSize=%d, " +
                           "encrypted=%b, expired=%b, downloadCount=%d/%d}", 
                           uploadId, fileName, fileSize, encrypted, isExpired(), 
                           downloadCount, maxDownloads);
    }
}
