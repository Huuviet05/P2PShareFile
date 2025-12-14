package org.example.p2psharefile.model;

import java.io.Serializable;

/**
 * PreviewContent - Nội dung preview thực tế (thumbnail, sample, snippet, etc)
 * 
 * Chứa dữ liệu preview nhỏ gọn để peer khác có thể fetch và hiển thị
 */
public class PreviewContent implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String fileHash;                        // Hash của file gốc
    private PreviewManifest.PreviewType type;       // Loại preview
    private byte[] data;                            // Dữ liệu preview (thumbnail, sample, etc)
    private String dataHash;                        // Hash của preview data
    private String format;                          // Format: jpg, png, mp3, txt, etc
    private long timestamp;                         // Thời điểm tạo preview
    
    // Metadata bổ sung tùy theo loại preview
    private int width;                              // Width (cho thumbnail/video)
    private int height;                             // Height (cho thumbnail/video)
    private int duration;                           // Duration (cho audio/video) - seconds
    private String encoding;                        // Encoding (cho text snippet)
    
    public PreviewContent(String fileHash, PreviewManifest.PreviewType type, byte[] data, String format) {
        this.fileHash = fileHash;
        this.type = type;
        this.data = data;
        this.format = format;
        this.timestamp = System.currentTimeMillis();
    }
    
    // ========== Getters & Setters ==========
    
    public String getFileHash() {
        return fileHash;
    }
    
    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }
    
    public PreviewManifest.PreviewType getType() {
        return type;
    }
    
    public void setType(PreviewManifest.PreviewType type) {
        this.type = type;
    }
    
    public byte[] getData() {
        return data;
    }
    
    public void setData(byte[] data) {
        this.data = data;
    }
    
    public String getDataHash() {
        return dataHash;
    }
    
    public void setDataHash(String dataHash) {
        this.dataHash = dataHash;
    }
    
    public String getFormat() {
        return format;
    }
    
    public void setFormat(String format) {
        this.format = format;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    public int getWidth() {
        return width;
    }
    
    public void setWidth(int width) {
        this.width = width;
    }
    
    public int getHeight() {
        return height;
    }
    
    public void setHeight(int height) {
        this.height = height;
    }
    
    public int getDuration() {
        return duration;
    }
    
    public void setDuration(int duration) {
        this.duration = duration;
    }
    
    public String getEncoding() {
        return encoding;
    }
    
    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }
    
    /**
     * Lấy kích thước dữ liệu preview
     */
    public long getSize() {
        return data != null ? data.length : 0;
    }
    
    /**
     * Lấy kích thước dạng đọc được
     */
    public String getFormattedSize() {
        long size = getSize();
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.2f KB", size / 1024.0);
        return String.format("%.2f MB", size / (1024.0 * 1024));
    }
    
    @Override
    public String toString() {
        return "PreviewContent{" +
                "type=" + type +
                ", format='" + format + '\'' +
                ", size=" + getFormattedSize() +
                ", timestamp=" + timestamp +
                '}';
    }
}
