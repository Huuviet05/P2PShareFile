package org.example.p2psharefile.relay;

import org.example.p2psharefile.model.PeerInfo;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * PeerRegistry - Quản lý danh sách peers đăng ký với relay server
 * 
 * Chức năng:
 * - Lưu thông tin peers online (IP công khai, port, tên)
 * - Tự động xóa peer hết hạn (không heartbeat)
 * - Cung cấp danh sách peers cho discovery
 */
public class PeerRegistry {
    
    private static final long PEER_TIMEOUT_MS = 60_000; // 1 phút không heartbeat = offline
    
    // Map: peerId -> RegisteredPeer
    private final Map<String, RegisteredPeer> peers = new ConcurrentHashMap<>();
    
    /**
     * Thông tin peer đăng ký
     */
    public static class RegisteredPeer implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private String peerId;
        private String displayName;
        private String publicIp;        // IP công khai (để peers khác kết nối)
        private int port;               // Port TCP transfer
        private String publicKey;       // RSA public key
        private long lastHeartbeat;     // Thời gian heartbeat cuối
        private long registeredAt;      // Thời gian đăng ký
        
        public RegisteredPeer(String peerId, String displayName, String publicIp, 
                            int port, String publicKey) {
            this.peerId = peerId;
            this.displayName = displayName;
            this.publicIp = publicIp;
            this.port = port;
            this.publicKey = publicKey;
            this.registeredAt = System.currentTimeMillis();
            this.lastHeartbeat = System.currentTimeMillis();
        }
        
        public String getPeerId() { return peerId; }
        public String getDisplayName() { return displayName; }
        public String getPublicIp() { return publicIp; }
        public int getPort() { return port; }
        public String getPublicKey() { return publicKey; }
        public long getLastHeartbeat() { return lastHeartbeat; }
        public long getRegisteredAt() { return registeredAt; }
        
        public void updateHeartbeat() {
            this.lastHeartbeat = System.currentTimeMillis();
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() - lastHeartbeat > PEER_TIMEOUT_MS;
        }
        
        /**
         * Chuyển sang PeerInfo để gửi cho client
         */
        public PeerInfo toPeerInfo() {
            return new PeerInfo(peerId, publicIp, port, displayName, publicKey);
        }
    }
    
    private static final Logger LOGGER = Logger.getLogger(PeerRegistry.class.getName());

    /**
     * Đăng ký hoặc cập nhật peer
     */
    public synchronized void registerPeer(String peerId, String displayName, String publicIp,
                                         int port, String publicKey) {
        RegisteredPeer peer = peers.get(peerId);
        
        if (peer == null) {
            // Peer mới
            peer = new RegisteredPeer(peerId, displayName, publicIp, port, publicKey);
            peers.put(peerId, peer);
            LOGGER.info("📝 Peer registered: " + displayName + " (" + publicIp + ":" + port + ")");
        } else {
            // Cập nhật heartbeat
            peer.updateHeartbeat();
        }
    }
    
    /**
     * Cập nhật heartbeat
     */
    public synchronized void heartbeat(String peerId) {
        RegisteredPeer peer = peers.get(peerId);
        if (peer != null) {
            peer.updateHeartbeat();
        }
    }
    
    /**
     * Hủy đăng ký peer
     */
    public synchronized void unregisterPeer(String peerId) {
        RegisteredPeer peer = peers.remove(peerId);
        if (peer != null) {
            LOGGER.info("👋 Peer unregistered: " + peer.getDisplayName());
        }
    }
    
    /**
     * Lấy danh sách tất cả peers online
     */
    public synchronized List<PeerInfo> getAllPeers() {
        List<PeerInfo> result = new ArrayList<>();
        for (RegisteredPeer peer : peers.values()) {
            if (!peer.isExpired()) {
                result.add(peer.toPeerInfo());
            }
        }
        return result;
    }
    
    /**
     * Lấy danh sách peers ngoại trừ peer đang request
     */
    public synchronized List<PeerInfo> getPeersExcluding(String excludePeerId) {
        List<PeerInfo> result = new ArrayList<>();
        for (RegisteredPeer peer : peers.values()) {
            if (!peer.isExpired() && !peer.getPeerId().equals(excludePeerId)) {
                result.add(peer.toPeerInfo());
            }
        }
        return result;
    }
    
    /**
     * Xóa peers hết hạn
     */
    public synchronized void cleanupExpiredPeers() {
        List<String> expiredIds = new ArrayList<>();
        
        for (Map.Entry<String, RegisteredPeer> entry : peers.entrySet()) {
            if (entry.getValue().isExpired()) {
                expiredIds.add(entry.getKey());
            }
        }
        
        for (String peerId : expiredIds) {
            RegisteredPeer peer = peers.remove(peerId);
            if (peer != null) {
                LOGGER.info("🕒 Peer timeout: " + peer.getDisplayName());
            }
        }
        
        if (!expiredIds.isEmpty()) {
            LOGGER.info("🧹 Removed " + expiredIds.size() + " expired peer(s)");
        }
    }
    
    /**
     * Số lượng peers online
     */
    public synchronized int getOnlineCount() {
        int count = 0;
        for (RegisteredPeer peer : peers.values()) {
            if (!peer.isExpired()) {
                count++;
            }
        }
        return count;
    }
    
    /**
     * Alias cho getOnlineCount() - để dùng trong HealthCheck
     */
    public int getActivePeerCount() {
        return getOnlineCount();
    }
    
    /**
     * Kiểm tra peer có online không
     */
    public synchronized boolean isOnline(String peerId) {
        RegisteredPeer peer = peers.get(peerId);
        return peer != null && !peer.isExpired();
    }
}
