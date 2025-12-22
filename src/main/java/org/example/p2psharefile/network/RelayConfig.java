package org.example.p2psharefile.network;

import java.io.Serializable;

/**
 * RelayConfig - Cấu hình Relay Server
 * 
 * Chứa thông tin cấu hình để kết nối và sử dụng relay server.
 * Có thể load từ file config hoặc environment variables.
 * 
 * Các tham số quan trọng:
 * - serverUrl: URL của relay server (HTTP/HTTPS)
 * - timeouts: các timeout cho kết nối, upload, download
 * - chunking: kích thước chunk, số lần retry
 * - fallback: khi nào dùng relay thay vì P2P
 * 
 * @author P2PShareFile Team
 * @version 1.0
 */
public class RelayConfig implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    // Server endpoints
    private String serverUrl;              // URL của relay server (VD: "https://relay.p2pshare.com")
    private String uploadEndpoint;         // Endpoint upload (default: "/api/upload")
    private String downloadEndpoint;       // Endpoint download (default: "/api/download")
    private String statusEndpoint;         // Endpoint kiểm tra status (default: "/api/status")
    
    // Authentication (nếu server yêu cầu)
    private String apiKey;                 // API key để authenticate
    private String authToken;              // Bearer token
    
    // Timeouts (milliseconds)
    private int connectionTimeout;         // Timeout kết nối (default: 10000 = 10s)
    private int readTimeout;               // Timeout đọc dữ liệu (default: 30000 = 30s)
    private int uploadTimeout;             // Timeout upload per chunk (default: 60000 = 60s)
    private int downloadTimeout;           // Timeout download per chunk (default: 60000 = 60s)
    
    // Chunking & Resume
    private int chunkSize;                 // Kích thước mỗi chunk (bytes, default: 1MB)
    private int maxRetries;                // Số lần retry tối đa (default: 3)
    private int retryDelayMs;              // Delay giữa các retry (ms, default: 1000)
    private boolean enableResume;          // Có hỗ trợ resume không (default: true)
    
    // Fallback strategy
    private boolean preferP2P;             // Ưu tiên P2P trước relay (default: true)
    private int p2pTimeoutMs;              // Timeout thử P2P trước khi fallback (default: 5000 = 5s)
    private boolean forceRelay;            // Bắt buộc dùng relay (bỏ qua P2P, default: false)
    
    // File limits
    private long maxFileSize;              // Kích thước file tối đa (bytes, 0 = không giới hạn)
    private long defaultExpiryTime;        // Thời gian hết hạn mặc định (ms, 0 = không hết hạn)
    
    // Security (UNUSED - chỉ để tương thích)
    private boolean enableEncryption;      // KHÔNG SỬ DỤNG - Relay mode không mã hóa file
    private boolean verifyCertificate;     // KHÔNG SỬ DỤNG - Chỉ dùng HTTP
    
    // Logging
    private boolean enableLogging;         // Có log không (default: true)
    private String logLevel;               // Log level: "FINEST", "FINE", "INFO", "WARNING", "SEVERE" hoặc "DEBUG", "WARN", "ERROR" (sẽ được map)
    
    /**
     * Constructor với giá trị mặc định
     */
    public RelayConfig() {
        // Server defaults
        this.serverUrl = "http://localhost:8080";
        this.uploadEndpoint = "/api/relay/upload";
        this.downloadEndpoint = "/api/relay/download";
        this.statusEndpoint = "/api/relay/status";
        
        // Timeouts
        this.connectionTimeout = 10000;
        this.readTimeout = 30000;
        this.uploadTimeout = 60000;
        this.downloadTimeout = 60000;
        
        // Chunking
        this.chunkSize = 1024 * 1024; // 1MB
        this.maxRetries = 3;
        this.retryDelayMs = 1000;
        this.enableResume = true;
        
        // Fallback
        this.preferP2P = true;
        this.p2pTimeoutMs = 5000;
        this.forceRelay = false;
        
        // Limits
        this.maxFileSize = 0; // No limit
        this.defaultExpiryTime = 24 * 60 * 60 * 1000; // 24 hours
        
        // Security - UNUSED: Relay mode KHÔNG mã hóa file (chỉ dựa vào Render HTTPS)
        this.enableEncryption = false;
        this.verifyCertificate = true;
        
        // Logging
        this.enableLogging = true;
        this.logLevel = "INFO";
    }
    
    /**
     * Tạo config từ server URL
     */
    public static RelayConfig fromServerUrl(String serverUrl) {
        RelayConfig config = new RelayConfig();
        config.setServerUrl(serverUrl);
        return config;
    }
    
    /**
     * Tạo config cho development (local server)
     */
    public static RelayConfig forDevelopment() {
        RelayConfig config = new RelayConfig();
        config.setServerUrl("http://localhost:8080");
        config.setVerifyCertificate(false);
        config.setLogLevel("FINE"); // FINE = Debug level trong java.util.logging
        return config;
    }
    
    /**
     * Tạo config cho production
     */
    public static RelayConfig forProduction(String serverUrl, String apiKey) {
        RelayConfig config = new RelayConfig();
        config.setServerUrl(serverUrl);
        config.setApiKey(apiKey);
        config.setVerifyCertificate(true);
        config.setLogLevel("INFO");
        return config;
    }
    
    /**
     * Validate config
     */
    public boolean isValid() {
        if (serverUrl == null || serverUrl.trim().isEmpty()) {
            return false;
        }
        if (chunkSize <= 0 || chunkSize > 10 * 1024 * 1024) { // Max 10MB chunk
            return false;
        }
        if (maxRetries < 0 || maxRetries > 10) {
            return false;
        }
        return true;
    }
    
    // Getters and Setters
    
    public String getServerUrl() {
        return serverUrl;
    }
    
    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }
    
    public String getUploadEndpoint() {
        return uploadEndpoint;
    }
    
    public void setUploadEndpoint(String uploadEndpoint) {
        this.uploadEndpoint = uploadEndpoint;
    }
    
    public String getDownloadEndpoint() {
        return downloadEndpoint;
    }
    
    public void setDownloadEndpoint(String downloadEndpoint) {
        this.downloadEndpoint = downloadEndpoint;
    }
    
    public String getStatusEndpoint() {
        return statusEndpoint;
    }
    
    public void setStatusEndpoint(String statusEndpoint) {
        this.statusEndpoint = statusEndpoint;
    }
    
    public String getApiKey() {
        return apiKey;
    }
    
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
    
    public String getAuthToken() {
        return authToken;
    }
    
    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }
    
    public int getConnectionTimeout() {
        return connectionTimeout;
    }
    
    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }
    
    public int getConnectTimeoutMs() {
        return connectionTimeout;
    }
    
    public int getReadTimeoutMs() {
        return readTimeout;
    }
    
    public int getReadTimeout() {
        return readTimeout;
    }
    
    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }
    
    public int getUploadTimeout() {
        return uploadTimeout;
    }
    
    public void setUploadTimeout(int uploadTimeout) {
        this.uploadTimeout = uploadTimeout;
    }
    
    public int getDownloadTimeout() {
        return downloadTimeout;
    }
    
    public void setDownloadTimeout(int downloadTimeout) {
        this.downloadTimeout = downloadTimeout;
    }
    
    public int getChunkSize() {
        return chunkSize;
    }
    
    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }
    
    public int getMaxRetries() {
        return maxRetries;
    }
    
    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }
    
    public int getRetryDelayMs() {
        return retryDelayMs;
    }
    
    public void setRetryDelayMs(int retryDelayMs) {
        this.retryDelayMs = retryDelayMs;
    }
    
    public boolean isEnableResume() {
        return enableResume;
    }
    
    public void setEnableResume(boolean enableResume) {
        this.enableResume = enableResume;
    }
    
    public boolean isPreferP2P() {
        return preferP2P;
    }
    
    public void setPreferP2P(boolean preferP2P) {
        this.preferP2P = preferP2P;
    }
    
    public int getP2pTimeoutMs() {
        return p2pTimeoutMs;
    }
    
    public void setP2pTimeoutMs(int p2pTimeoutMs) {
        this.p2pTimeoutMs = p2pTimeoutMs;
    }
    
    public boolean isForceRelay() {
        return forceRelay;
    }
    
    public void setForceRelay(boolean forceRelay) {
        this.forceRelay = forceRelay;
    }
    
    public long getMaxFileSize() {
        return maxFileSize;
    }
    
    public void setMaxFileSize(long maxFileSize) {
        this.maxFileSize = maxFileSize;
    }
    
    public long getDefaultExpiryTime() {
        return defaultExpiryTime;
    }
    
    public void setDefaultExpiryTime(long defaultExpiryTime) {
        this.defaultExpiryTime = defaultExpiryTime;
    }
    
    public boolean isEnableEncryption() {
        return enableEncryption;
    }
    
    public void setEnableEncryption(boolean enableEncryption) {
        this.enableEncryption = enableEncryption;
    }
    
    public boolean isVerifyCertificate() {
        return verifyCertificate;
    }
    
    public void setVerifyCertificate(boolean verifyCertificate) {
        this.verifyCertificate = verifyCertificate;
    }
    
    public boolean isEnableLogging() {
        return enableLogging;
    }
    
    public void setEnableLogging(boolean enableLogging) {
        this.enableLogging = enableLogging;
    }
    
    public String getLogLevel() {
        return logLevel;
    }
    
    public void setLogLevel(String logLevel) {
        this.logLevel = logLevel;
    }
    
    @Override
    public String toString() {
        return String.format("RelayConfig{serverUrl='%s', preferP2P=%b, forceRelay=%b, " +
                           "chunkSize=%d, enableEncryption=%b}", 
                           serverUrl, preferP2P, forceRelay, chunkSize, enableEncryption);
    }
}
