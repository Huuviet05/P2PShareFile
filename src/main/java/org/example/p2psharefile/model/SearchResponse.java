package org.example.p2psharefile.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Module 1: SearchResponse - Kết quả tìm kiếm file
 * Chứa danh sách các file tìm thấy và thông tin peer có file đó
 * 
 * UltraView Extension: Hỗ trợ preview metadata
 */
public class SearchResponse implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String requestId;           // ID của yêu cầu tìm kiếm
    private PeerInfo sourcePeer;        // Peer có file
    private List<FileInfo> foundFiles;  // Danh sách file tìm thấy
    
    // UltraView: Preview support
    private Map<String, PreviewManifest> previewManifests;  // FileHash -> PreviewManifest
    
    public SearchResponse(String requestId, PeerInfo sourcePeer) {
        this.requestId = requestId;
        this.sourcePeer = sourcePeer;
        this.foundFiles = new ArrayList<>();
        this.previewManifests = new HashMap<>();
    }
    
    public SearchResponse(String requestId, PeerInfo sourcePeer, List<FileInfo> foundFiles) {
        this.requestId = requestId;
        this.sourcePeer = sourcePeer;
        this.foundFiles = foundFiles;
        this.previewManifests = new HashMap<>();
    }
    
    // Getters và Setters
    public String getRequestId() {
        return requestId;
    }
    
    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
    
    public PeerInfo getSourcePeer() {
        return sourcePeer;
    }
    
    public void setSourcePeer(PeerInfo sourcePeer) {
        this.sourcePeer = sourcePeer;
    }
    
    public List<FileInfo> getFoundFiles() {
        return foundFiles;
    }
    
    public void setFoundFiles(List<FileInfo> foundFiles) {
        this.foundFiles = foundFiles;
    }
    
    public void addFile(FileInfo file) {
        this.foundFiles.add(file);
    }
    
    public boolean hasFiles() {
        return foundFiles != null && !foundFiles.isEmpty();
    }
    
    // ========== UltraView Preview Methods ==========
    
    public Map<String, PreviewManifest> getPreviewManifests() {
        return previewManifests;
    }
    
    public void setPreviewManifests(Map<String, PreviewManifest> previewManifests) {
        this.previewManifests = previewManifests;
    }
    
    public void addPreviewManifest(String fileHash, PreviewManifest manifest) {
        this.previewManifests.put(fileHash, manifest);
    }
    
    public PreviewManifest getPreviewManifest(String fileHash) {
        return previewManifests.get(fileHash);
    }
    
    public boolean hasPreview(String fileHash) {
        return previewManifests.containsKey(fileHash) && 
               previewManifests.get(fileHash).hasPreview();
    }
    
    public boolean hasAnyPreview() {
        return !previewManifests.isEmpty();
    }
    
    @Override
    public String toString() {
        return "SearchResponse{" +
                "requestId='" + requestId + '\'' +
                ", sourcePeer=" + sourcePeer +
                ", filesCount=" + foundFiles.size() +
                ", previewsCount=" + previewManifests.size() +
                '}';
    }
}
