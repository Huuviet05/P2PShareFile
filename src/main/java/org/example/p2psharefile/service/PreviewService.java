package org.example.p2psharefile.service;

import org.example.p2psharefile.model.*;
import org.example.p2psharefile.security.SecurityManager;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/**
 * PreviewService - Service xử lý preview requests (P2P)
 * 
 * Cung cấp preview content cho peer khác qua TLS
 * Request types:
 * - GET_MANIFEST: Lấy PreviewManifest
 * - GET_CONTENT: Lấy PreviewContent (thumbnail, snippet, etc)
 */
public class PreviewService {
    
    private static final int PREVIEW_PORT_OFFSET = 10;  // Preview port = transfer port + 10 (tránh vượt quá 65535)
    
    private final PeerInfo localPeer;
    private final SecurityManager securityManager;
    private final PreviewCacheService cacheService;
    
    private SSLServerSocket previewServer;
    private ExecutorService executorService;
    private volatile boolean running = false;
    
    /**
     * Request type cho preview
     */
    public enum RequestType {
        GET_MANIFEST,       // Lấy manifest
        GET_CONTENT         // Lấy content
    }
    
    /**
     * Preview request
     */
    public static class PreviewRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private RequestType type;
        private String fileHash;
        private PreviewManifest.PreviewType previewType;
        private String requesterId;     // Peer ID của requester
        
        public PreviewRequest(RequestType type, String fileHash, String requesterId) {
            this.type = type;
            this.fileHash = fileHash;
            this.requesterId = requesterId;
        }
        
        public RequestType getType() { return type; }
        public void setType(RequestType type) { this.type = type; }
        
        public String getFileHash() { return fileHash; }
        public void setFileHash(String fileHash) { this.fileHash = fileHash; }
        
        public PreviewManifest.PreviewType getPreviewType() { return previewType; }
        public void setPreviewType(PreviewManifest.PreviewType previewType) { 
            this.previewType = previewType; 
        }
        
        public String getRequesterId() { return requesterId; }
        public void setRequesterId(String requesterId) { this.requesterId = requesterId; }
    }
    
    /**
     * Preview response
     */
    public static class PreviewResponse implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private boolean success;
        private String errorMessage;
        private PreviewManifest manifest;
        private PreviewContent content;
        
        public PreviewResponse(boolean success) {
            this.success = success;
        }
        
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        
        public PreviewManifest getManifest() { return manifest; }
        public void setManifest(PreviewManifest manifest) { this.manifest = manifest; }
        
        public PreviewContent getContent() { return content; }
        public void setContent(PreviewContent content) { this.content = content; }
    }
    
    public PreviewService(PeerInfo localPeer, SecurityManager securityManager, 
                         PreviewCacheService cacheService) {
        this.localPeer = localPeer;
        this.securityManager = securityManager;
        this.cacheService = cacheService;
    }
    
    /**
     * Khởi động preview service
     */
    public void start() throws IOException {
        if (running) return;
        
        running = true;
        
        // Preview port = transfer port + offset (nếu hợp lệ, nếu không thì auto-assign)
        int previewPort = localPeer.getPort() + PREVIEW_PORT_OFFSET;
        if (previewPort > 65535) {
            previewPort = 0; // Auto-assign nếu vượt quá giới hạn
            System.out.println("⚠ Port preview vượt quá 65535, sử dụng auto-assign");
        }
        
        try {
            previewServer = securityManager.createSSLServerSocket(previewPort);
            executorService = Executors.newCachedThreadPool();
            
            // Thread lắng nghe preview requests
            executorService.submit(this::listenForPreviewRequests);
            
            System.out.println("✓ Preview Service (TLS) đã khởi động trên port " + previewServer.getLocalPort());
            
        } catch (IOException e) {
            System.err.println("❌ Lỗi khởi động Preview Service: " + e.getMessage());
            throw e;
        }
    }
    
    /**
     * Dừng preview service
     */
    public void stop() {
        running = false;
        
        try {
            if (previewServer != null && !previewServer.isClosed()) {
                previewServer.close();
            }
        } catch (IOException e) {
            System.err.println("Lỗi khi đóng preview server: " + e.getMessage());
        }
        
        if (executorService != null) {
            executorService.shutdownNow();
        }
        
        System.out.println("✓ Preview Service đã dừng");
    }
    
    /**
     * Thread lắng nghe preview requests
     */
    private void listenForPreviewRequests() {
        while (running) {
            try {
                Socket clientSocket = previewServer.accept();
                executorService.submit(() -> handlePreviewRequest(clientSocket));
            } catch (SocketException e) {
                // Server socket đã đóng
                break;
            } catch (IOException e) {
                if (running) {
                    System.err.println("Lỗi khi accept preview connection: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Xử lý preview request từ peer
     */
    private void handlePreviewRequest(Socket socket) {
        try (ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
             ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream())) {
            
            // Nhận request
            PreviewRequest request = (PreviewRequest) ois.readObject();
            
            System.out.println("📥 Preview request: " + request.getType() + 
                             " for hash: " + request.getFileHash().substring(0, 16) + "...");
            
            PreviewResponse response;
            
            switch (request.getType()) {
                case GET_MANIFEST:
                    response = handleGetManifest(request);
                    break;
                    
                case GET_CONTENT:
                    response = handleGetContent(request);
                    break;
                    
                default:
                    response = new PreviewResponse(false);
                    response.setErrorMessage("Unknown request type");
            }
            
            // Gửi response
            oos.writeObject(response);
            oos.flush();
            
        } catch (Exception e) {
            System.err.println("Lỗi khi xử lý preview request: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }
    
    /**
     * Xử lý GET_MANIFEST request
     */
    private PreviewResponse handleGetManifest(PreviewRequest request) {
        PreviewResponse response = new PreviewResponse(true);
        
        try {
            PreviewManifest manifest = cacheService.getManifest(request.getFileHash());
            
            if (manifest == null) {
                response.setSuccess(false);
                response.setErrorMessage("Manifest not found for hash: " + request.getFileHash());
                return response;
            }
            
            // Kiểm tra permission (nếu có trusted peers only)
            if (!manifest.isPreviewAllowedForPeer(request.getRequesterId())) {
                response.setSuccess(false);
                response.setErrorMessage("Preview not allowed for this peer");
                return response;
            }
            
            response.setManifest(manifest);
            System.out.println("  ✓ Đã gửi manifest");
            
        } catch (Exception e) {
            response.setSuccess(false);
            response.setErrorMessage("Error: " + e.getMessage());
        }
        
        return response;
    }
    
    /**
     * Xử lý GET_CONTENT request
     */
    private PreviewResponse handleGetContent(PreviewRequest request) {
        PreviewResponse response = new PreviewResponse(true);
        
        try {
            // Kiểm tra manifest trước
            PreviewManifest manifest = cacheService.getManifest(request.getFileHash());
            
            if (manifest == null) {
                response.setSuccess(false);
                response.setErrorMessage("Manifest not found");
                return response;
            }
            
            // Kiểm tra permission
            if (!manifest.isPreviewAllowedForPeer(request.getRequesterId())) {
                response.setSuccess(false);
                response.setErrorMessage("Preview not allowed");
                return response;
            }
            
            // Kiểm tra preview type có hỗ trợ không
            if (!manifest.hasPreviewType(request.getPreviewType())) {
                response.setSuccess(false);
                response.setErrorMessage("Preview type not available: " + request.getPreviewType());
                return response;
            }
            
            // Lấy preview content
            PreviewContent content = cacheService.getOrCreateContent(
                request.getFileHash(),
                request.getPreviewType()
            );
            
            if (content == null) {
                response.setSuccess(false);
                response.setErrorMessage("Failed to generate preview content");
                return response;
            }
            
            response.setContent(content);
            System.out.println("  ✓ Đã gửi content: " + request.getPreviewType() + 
                             " (" + content.getFormattedSize() + ")");
            
        } catch (Exception e) {
            response.setSuccess(false);
            response.setErrorMessage("Error: " + e.getMessage());
        }
        
        return response;
    }
    
    /**
     * Client method: Request manifest từ peer
     */
    public PreviewManifest requestManifest(PeerInfo peer, String fileHash) {
        try {
            int previewPort = peer.getPort() + PREVIEW_PORT_OFFSET;
            
            SSLSocket socket = securityManager.createSSLSocket(peer.getIpAddress(), previewPort);
            socket.connect(new InetSocketAddress(peer.getIpAddress(), previewPort), 5000);
            socket.setSoTimeout(10000);
            socket.startHandshake();
            
            try (ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
                 ObjectInputStream ois = new ObjectInputStream(socket.getInputStream())) {
                
                // Gửi request
                PreviewRequest request = new PreviewRequest(
                    RequestType.GET_MANIFEST,
                    fileHash,
                    localPeer.getPeerId()
                );
                oos.writeObject(request);
                oos.flush();
                
                // Nhận response
                PreviewResponse response = (PreviewResponse) ois.readObject();
                
                if (response.isSuccess()) {
                    PreviewManifest manifest = response.getManifest();
                    
                    // Verify signature nếu có
                    if (manifest.getSignature() != null) {
                        try {
                            // Lấy public key của peer
                            java.security.PublicKey peerPublicKey = 
                                securityManager.getTrustedPeerKey(peer.getPeerId());
                            
                            if (peerPublicKey == null) {
                                // Nếu chưa có trong trust list, decode từ PeerInfo
                                peerPublicKey = securityManager.decodePublicKey(peer.getPublicKey());
                            }
                            
                            // Verify signature
                            boolean valid = securityManager.verifySignature(
                                manifest.getDataToSign(),
                                manifest.getSignature(),
                                peerPublicKey
                            );
                            
                            if (!valid) {
                                System.err.println("❌ Signature không hợp lệ cho manifest!");
                                return null;
                            }
                            
                            System.out.println("✓ Đã verify signature manifest từ " + peer.getDisplayName());
                            
                        } catch (Exception e) {
                            System.err.println("❌ Lỗi khi verify signature: " + e.getMessage());
                            return null;
                        }
                    }
                    
                    System.out.println("✓ Đã nhận manifest từ " + peer.getDisplayName());
                    return manifest;
                } else {
                    System.err.println("❌ Preview request failed: " + response.getErrorMessage());
                    return null;
                }
                
            } finally {
                socket.close();
            }
            
        } catch (Exception e) {
            System.err.println("Lỗi khi request manifest: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Client method: Request preview content từ peer
     */
    public PreviewContent requestContent(PeerInfo peer, String fileHash, 
                                        PreviewManifest.PreviewType type) {
        try {
            int previewPort = peer.getPort() + PREVIEW_PORT_OFFSET;
            
            SSLSocket socket = securityManager.createSSLSocket(peer.getIpAddress(), previewPort);
            socket.connect(new InetSocketAddress(peer.getIpAddress(), previewPort), 5000);
            socket.setSoTimeout(10000);
            socket.startHandshake();
            
            try (ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
                 ObjectInputStream ois = new ObjectInputStream(socket.getInputStream())) {
                
                // Gửi request
                PreviewRequest request = new PreviewRequest(
                    RequestType.GET_CONTENT,
                    fileHash,
                    localPeer.getPeerId()
                );
                request.setPreviewType(type);
                oos.writeObject(request);
                oos.flush();
                
                // Nhận response
                PreviewResponse response = (PreviewResponse) ois.readObject();
                
                if (response.isSuccess()) {
                    System.out.println("✓ Đã nhận preview content: " + type + 
                                     " (" + response.getContent().getFormattedSize() + ")");
                    return response.getContent();
                } else {
                    System.err.println("❌ Preview request failed: " + response.getErrorMessage());
                    return null;
                }
                
            } finally {
                socket.close();
            }
            
        } catch (Exception e) {
            System.err.println("Lỗi khi request preview content: " + e.getMessage());
            return null;
        }
    }
    
    public int getPreviewPort() {
        return previewServer != null ? previewServer.getLocalPort() : -1;
    }
}
