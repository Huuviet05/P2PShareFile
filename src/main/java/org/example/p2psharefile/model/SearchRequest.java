package org.example.p2psharefile.model;

import java.io.Serializable;
import java.util.UUID;

/**
 * Module 1: SearchRequest - Yêu cầu tìm kiếm file trong mạng P2P
 * Sử dụng flooding search: gửi yêu cầu đến tất cả các peer
 */
public class SearchRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String requestId;       // ID duy nhất của yêu cầu
    private String originPeerId;    // ID của peer gửi yêu cầu ban đầu
    private String searchQuery;     // Từ khóa tìm kiếm
    private int ttl;                // Time To Live - số lần forward tối đa
    
    public SearchRequest(String originPeerId, String searchQuery, int ttl) {
        this.requestId = UUID.randomUUID().toString();
        this.originPeerId = originPeerId;
        this.searchQuery = searchQuery;
        this.ttl = ttl;
    }
    
    public SearchRequest(String requestId, String originPeerId, String searchQuery, int ttl) {
        this.requestId = requestId;
        this.originPeerId = originPeerId;
        this.searchQuery = searchQuery;
        this.ttl = ttl;
    }
    
    // Getters và Setters
    public String getRequestId() {
        return requestId;
    }
    
    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
    
    public String getOriginPeerId() {
        return originPeerId;
    }
    
    public void setOriginPeerId(String originPeerId) {
        this.originPeerId = originPeerId;
    }
    
    public String getSearchQuery() {
        return searchQuery;
    }
    
    public void setSearchQuery(String searchQuery) {
        this.searchQuery = searchQuery;
    }
    
    public int getTtl() {
        return ttl;
    }
    
    public void setTtl(int ttl) {
        this.ttl = ttl;
    }
    
    /**
     * Giảm TTL khi forward request
     */
    public void decrementTTL() {
        this.ttl--;
    }
    
    /**
     * Kiểm tra xem có thể forward tiếp hay không
     */
    public boolean canForward() {
        return ttl > 0;
    }
    
    @Override
    public String toString() {
        return "SearchRequest{" +
                "requestId='" + requestId + '\'' +
                ", query='" + searchQuery + '\'' +
                ", ttl=" + ttl +
                '}';
    }
}
