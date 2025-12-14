package org.example.p2psharefile.model;

import java.io.Serializable;
import java.util.*;

/**
 * PreviewManifest - Manifest mô tả các preview có sẵn cho một file
 * 
 * Chứa metadata về file và các loại preview đã được sinh ra.
 * Manifest này được ký (signed) để đảm bảo tính xác thực.
 */
public class PreviewManifest implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /**
     * Enum các loại preview hỗ trợ
     */
    public enum PreviewType {
        THUMBNAIL,          // Ảnh thu nhỏ (images)
        TEXT_SNIPPET,       // Trích xuất text (text/doc/pdf)
        AUDIO_SAMPLE,       // Audio sample (mp3/wav)
        VIDEO_PREVIEW,      // Video preview (gif/low-res mp4)
        PDF_PAGES,          // PDF pages thumbnails
        ARCHIVE_LISTING,    // Danh sách file trong archive (zip/rar)
        METADATA_ONLY,      // Chỉ metadata (size, mime, hash)
        FIRST_CHUNK         // Stream first N KB để render
    }
    
    private String fileHash;                    // SHA-256 hash của file gốc
    private String fileName;                    // Tên file
    private long fileSize;                      // Kích thước file (bytes)
    private String mimeType;                    // MIME type
    private long lastModified;                  // Timestamp last modified
    
    private Set<PreviewType> availableTypes;    // Các loại preview có sẵn
    private Map<PreviewType, String> previewHashes; // Hash của từng preview content
    private Map<PreviewType, Long> previewSizes;    // Size của từng preview
    
    private String snippet;                     // Text snippet (nếu có)
    private List<String> archiveListing;        // Danh sách file trong archive (nếu có)
    private Map<String, String> metadata;       // Metadata bổ sung (tags, rating, etc)
    
    private boolean allowPreview;               // Owner có cho phép preview không
    private Set<String> trustedPeersOnly;       // Chỉ cho phép preview với peer tin cậy (null = all)
    
    private long timestamp;                     // Thời điểm tạo manifest
    private String ownerPeerId;                 // Peer ID của owner
    private String signature;                   // Chữ ký ECDSA của manifest
    
    public PreviewManifest(String fileHash, String fileName, long fileSize, String mimeType) {
        this.fileHash = fileHash;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.mimeType = mimeType;
        this.lastModified = System.currentTimeMillis();
        this.availableTypes = new HashSet<>();
        this.previewHashes = new HashMap<>();
        this.previewSizes = new HashMap<>();
        this.metadata = new HashMap<>();
        this.allowPreview = true;
        this.timestamp = System.currentTimeMillis();
    }
    
    // ========== Getters & Setters ==========
    
    public String getFileHash() {
        return fileHash;
    }
    
    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
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
    
    public String getMimeType() {
        return mimeType;
    }
    
    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }
    
    public long getLastModified() {
        return lastModified;
    }
    
    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }
    
    public Set<PreviewType> getAvailableTypes() {
        return availableTypes;
    }
    
    public void setAvailableTypes(Set<PreviewType> availableTypes) {
        this.availableTypes = availableTypes;
    }
    
    public void addPreviewType(PreviewType type, String hash, long size) {
        this.availableTypes.add(type);
        this.previewHashes.put(type, hash);
        this.previewSizes.put(type, size);
    }
    
    public Map<PreviewType, String> getPreviewHashes() {
        return previewHashes;
    }
    
    public Map<PreviewType, Long> getPreviewSizes() {
        return previewSizes;
    }
    
    public String getSnippet() {
        return snippet;
    }
    
    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }
    
    public List<String> getArchiveListing() {
        return archiveListing;
    }
    
    public void setArchiveListing(List<String> archiveListing) {
        this.archiveListing = archiveListing;
    }
    
    public Map<String, String> getMetadata() {
        return metadata;
    }
    
    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }
    
    public void addMetadata(String key, String value) {
        this.metadata.put(key, value);
    }
    
    public boolean isAllowPreview() {
        return allowPreview;
    }
    
    public void setAllowPreview(boolean allowPreview) {
        this.allowPreview = allowPreview;
    }
    
    public Set<String> getTrustedPeersOnly() {
        return trustedPeersOnly;
    }
    
    public void setTrustedPeersOnly(Set<String> trustedPeersOnly) {
        this.trustedPeersOnly = trustedPeersOnly;
    }
    
    public boolean isPreviewAllowedForPeer(String peerId) {
        if (!allowPreview) return false;
        if (trustedPeersOnly == null) return true;
        return trustedPeersOnly.contains(peerId);
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    public String getOwnerPeerId() {
        return ownerPeerId;
    }
    
    public void setOwnerPeerId(String ownerPeerId) {
        this.ownerPeerId = ownerPeerId;
    }
    
    public String getSignature() {
        return signature;
    }
    
    public void setSignature(String signature) {
        this.signature = signature;
    }
    
    /**
     * Kiểm tra xem có preview type nào không
     */
    public boolean hasPreview() {
        return !availableTypes.isEmpty();
    }
    
    /**
     * Kiểm tra xem có preview type cụ thể không
     */
    public boolean hasPreviewType(PreviewType type) {
        return availableTypes.contains(type);
    }
    
    /**
     * Lấy dữ liệu để ký (signature)
     * Format: fileHash|fileName|fileSize|mimeType|timestamp|ownerPeerId
     */
    public String getDataToSign() {
        return String.format("%s|%s|%d|%s|%d|%s",
            fileHash, fileName, fileSize, mimeType, timestamp, ownerPeerId);
    }
    
    @Override
    public String toString() {
        return "PreviewManifest{" +
                "fileName='" + fileName + '\'' +
                ", fileSize=" + fileSize +
                ", mimeType='" + mimeType + '\'' +
                ", availableTypes=" + availableTypes +
                ", allowPreview=" + allowPreview +
                '}';
    }
}
