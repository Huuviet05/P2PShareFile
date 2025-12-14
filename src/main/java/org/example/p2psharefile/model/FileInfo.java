package org.example.p2psharefile.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * Module 1: FileInfo - Lưu thông tin về một file được chia sẻ
 * Mỗi file có: tên, kích thước, đường dẫn, checksum
 */
public class FileInfo implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String fileName;        // Tên file
    private long fileSize;          // Kích thước file (bytes)
    private String filePath;        // Đường dẫn đầy đủ trên máy
    private String checksum;        // MD5 checksum để kiểm tra tính toàn vẹn
    private String ownerId;         // ID của peer sở hữu file này
    private String fileHash;        // SHA-256 hash cho UltraView preview
    
    public FileInfo(String fileName, long fileSize, String filePath) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.filePath = filePath;
    }
    
    public FileInfo(String fileName, long fileSize, String filePath, String checksum, String ownerId) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.filePath = filePath;
        this.checksum = checksum;
        this.ownerId = ownerId;
    }
    
    // Getters và Setters
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
    
    public String getFilePath() {
        return filePath;
    }
    
    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }
    
    public String getChecksum() {
        return checksum;
    }
    
    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }
    
    public String getOwnerId() {
        return ownerId;
    }
    
    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }
    
    public String getFileHash() {
        return fileHash;
    }
    
    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }
    
    /**
     * Chuyển đổi kích thước file sang định dạng dễ đọc
     */
    public String getFormattedSize() {
        if (fileSize < 1024) return fileSize + " B";
        if (fileSize < 1024 * 1024) return String.format("%.2f KB", fileSize / 1024.0);
        if (fileSize < 1024 * 1024 * 1024) return String.format("%.2f MB", fileSize / (1024.0 * 1024));
        return String.format("%.2f GB", fileSize / (1024.0 * 1024 * 1024));
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileInfo fileInfo = (FileInfo) o;
        return Objects.equals(fileName, fileInfo.fileName) && 
               Objects.equals(checksum, fileInfo.checksum);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(fileName, checksum);
    }
    
    @Override
    public String toString() {
        return fileName + " (" + getFormattedSize() + ")";
    }
}
