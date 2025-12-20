package org.example.p2psharefile.service;

import org.example.p2psharefile.model.*;
import org.example.p2psharefile.network.PeerDiscovery;
import org.example.p2psharefile.security.SecurityManager;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.*;

/**
 * PINCodeService - Dịch vụ quản lý mã PIN chia sẻ file (với TLS + Signatures)
 * 
 * Giống Send Anywhere:
 * 1. User chọn file → Tạo mã PIN 6 số
 * 2. Chia sẻ PIN cho người khác (qua TLS channel)
 * 3. Người khác nhập PIN → Download file
 * 4. PIN hết hạn sau 10 phút
 * 
 * Security improvements:
 * - PIN messages được ký bằng ECDSA để chống forgery
 * - TLS encryption cho PIN transmission
 */
public class PINCodeService {
    
    private static final int PIN_LENGTH = 6;
    private static final long DEFAULT_EXPIRY = 600000; // 10 phút
    private static final int PIN_SERVER_PORT = 8887;   // Port để sync PIN giữa peers
    
    private final PeerInfo localPeer;
    private final PeerDiscovery peerDiscovery;
    private final SecurityManager securityManager;
    private final Map<String, ShareSession> localSessions;  // PIN do mình tạo
    private final Map<String, ShareSession> globalSessions; // PIN từ tất cả peers
    
    private org.example.p2psharefile.network.RelayClient relayClient; // Relay client để sync PIN qua Internet
    
    // Connection mode: true = P2P only (LAN), false = Relay only (Internet)
    private volatile boolean p2pOnlyMode = true;
    
    private SSLServerSocket pinServer;
    private ExecutorService executorService;
    private volatile boolean running = false;
    
    // Listeners
    private final List<PINCodeListener> listeners;
    
    public interface PINCodeListener {
        void onPINCreated(ShareSession session);
        void onPINExpired(String pin);
        void onPINReceived(ShareSession session);
    }
    
    public PINCodeService(PeerInfo localPeer, PeerDiscovery peerDiscovery, SecurityManager securityManager) {
        this.localPeer = localPeer;
        this.peerDiscovery = peerDiscovery;
        this.securityManager = securityManager;
        this.localSessions = new ConcurrentHashMap<>();
        this.globalSessions = new ConcurrentHashMap<>();
        this.listeners = new CopyOnWriteArrayList<>();
    }
    
    /**
     * Set RelayClient để sync PIN qua Internet
     */
    public void setRelayClient(org.example.p2psharefile.network.RelayClient relayClient) {
        this.relayClient = relayClient;
    }
    
    /**
     * Khởi động dịch vụ PIN (với TLS)
     */
    public void start() throws IOException {
        if (running) return;
        
        running = true;
        pinServer = securityManager.createSSLServerSocket(PIN_SERVER_PORT);
        executorService = Executors.newCachedThreadPool();
        
        // Thread lắng nghe PIN từ peer khác
        executorService.submit(this::listenForPINs);
        
        // Thread kiểm tra PIN hết hạn
        executorService.submit(this::checkExpiredPINs);
        
        System.out.println("✓ PIN Code Service (TLS) đã khởi động trên port " + PIN_SERVER_PORT);
    }
    
    /**
     * Dừng dịch vụ
     */
    public void stop() {
        running = false;
        
        try {
            if (pinServer != null && !pinServer.isClosed()) {
                pinServer.close();
            }
        } catch (IOException e) {
            System.err.println("Lỗi khi đóng PIN server: " + e.getMessage());
        }
        
        if (executorService != null) {
            executorService.shutdownNow();
        }
        
        System.out.println("✓ PIN Code Service đã dừng");
    }
    
    /**
     * Tạo mã PIN mới cho file
     */
    public ShareSession createPIN(FileInfo fileInfo) {
        return createPIN(fileInfo, DEFAULT_EXPIRY);
    }
    
    /**
     * Tạo mã PIN với thời gian tùy chỉnh
     */
    public ShareSession createPIN(FileInfo fileInfo, long expiryMillis) {
        // Tạo PIN ngẫu nhiên
        String pin = generateUniquePIN();
        
        // Tạo session
        long expiryTime = System.currentTimeMillis() + expiryMillis;
        ShareSession session = new ShareSession(pin, fileInfo, localPeer, expiryTime);
        
        // Lưu local
        localSessions.put(pin, session);
        globalSessions.put(pin, session);
        
        System.out.println("✓ Đã tạo PIN: " + pin + " cho file: " + fileInfo.getFileName() + 
                          " (Mode: " + (p2pOnlyMode ? "P2P" : "Relay") + ")");

        if (p2pOnlyMode) {
            // ===== P2P MODE: Chỉ gửi đến LAN peers =====
            sendPINToAllPeers(session);
        } else {
            // ===== RELAY MODE: Upload và tạo PIN trên relay (sync để đảm bảo PIN được tạo) =====
            if (relayClient != null) {
                // Upload synchronously để PIN có thể được tìm thấy ngay
                uploadAndCreatePINOnRelaySync(session, fileInfo, expiryMillis);
            } else {
                System.err.println("⚠ Relay client chưa được kích hoạt!");
            }
        }
        
        // Thông báo listeners
        notifyPINCreated(session);
        
        return session;
    }
    
    /**
     * Upload file lên relay rồi tạo PIN (SYNC - đợi hoàn thành)
     */
    private void uploadAndCreatePINOnRelaySync(ShareSession session, FileInfo fileInfo, long expiryMillis) {
        try {
            // Nếu file đã có relayFileInfo thì dùng luôn
            if (fileInfo.getRelayFileInfo() != null) {
                System.out.println("📌 File đã có trên relay, tạo PIN ngay...");
                sendPINToRelay(session, expiryMillis);
                return;
            }
            
            // Upload file lên relay trước
            java.io.File file = new java.io.File(fileInfo.getFilePath());
            if (!file.exists()) {
                System.err.println("❌ File không tồn tại: " + fileInfo.getFilePath());
                return;
            }
            
            System.out.println("📤 Upload file lên relay để tạo PIN: " + fileInfo.getFileName());
            
            org.example.p2psharefile.model.RelayUploadRequest request = 
                new org.example.p2psharefile.model.RelayUploadRequest(
                    localPeer.getPeerId(),
                    localPeer.getDisplayName(),
                    fileInfo.getFileName(),
                    file.length(),
                    fileInfo.getChecksum()
                );
            
            // Upload SYNC (không dùng listener callback)
            org.example.p2psharefile.model.RelayFileInfo relayFileInfo = 
                relayClient.uploadFile(file, request, null);
            
            if (relayFileInfo != null) {
                System.out.println("✅ Upload xong: " + relayFileInfo.getUploadId());
                
                // Lưu relayFileInfo vào fileInfo
                fileInfo.setRelayFileInfo(relayFileInfo);
                
                // Đăng ký file để search được
                relayClient.registerFileForSearch(relayFileInfo);
                
                // Tạo PIN trên relay
                sendPINToRelay(session, expiryMillis);
            } else {
                System.err.println("❌ Upload thất bại, không thể tạo PIN trên relay");
            }
            
        } catch (Exception e) {
            System.err.println("❌ Lỗi upload/create PIN: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Gửi PIN lên relay server
     */
    private void sendPINToRelay(ShareSession session, long expiryMillis) {
        try {
            org.example.p2psharefile.model.RelayFileInfo relayFileInfo = session.getFileInfo().getRelayFileInfo();
            if (relayFileInfo == null) {
                System.err.println("⚠ File chưa được upload lên relay, không thể tạo PIN qua relay");
                return;
            }
            
            boolean success = relayClient.createPIN(session.getPin(), relayFileInfo, expiryMillis);
            if (success) {
                System.out.println("✓ Đã gửi PIN lên relay server: " + session.getPin());
            } else {
                System.err.println("⚠ Không thể gửi PIN lên relay server");
            }
        } catch (Exception e) {
            System.err.println("❌ Lỗi gửi PIN lên relay: " + e.getMessage());
        }
    }
    
    /**
     * Tìm session bằng PIN (theo mode: P2P hoặc Relay)
     */
    public ShareSession findByPIN(String pin) {
        System.out.println("🔍 Tìm PIN: " + pin + " (Mode: " + (p2pOnlyMode ? "P2P" : "Relay") + ")");
        
        // Tìm trong local/global cache trước
        ShareSession session = globalSessions.get(pin);
        if (session != null && !session.isExpired()) {
            System.out.println("✓ Tìm thấy PIN trong cache local: " + pin);
            return session;
        }
        
        if (p2pOnlyMode) {
            // ===== P2P MODE: Chỉ tìm local =====
            System.out.println("⚠ Không tìm thấy PIN trong mạng LAN: " + pin);
            return null;
        }
        
        // ===== RELAY MODE: Tìm trên relay server =====
        if (relayClient != null) {
            org.example.p2psharefile.model.RelayFileInfo relayFileInfo = relayClient.findPIN(pin);
            if (relayFileInfo != null) {
                // Tạo session từ relay info
                FileInfo fileInfo = new FileInfo(
                    relayFileInfo.getFileName(),
                    relayFileInfo.getFileSize(),
                    relayFileInfo.getDownloadUrl()
                );
                fileInfo.setChecksum(relayFileInfo.getFileHash());
                fileInfo.setFileHash(relayFileInfo.getFileHash());
                fileInfo.setRelayFileInfo(relayFileInfo);
                
                // Tạo PeerInfo cho sender (giả lập)
                PeerInfo senderPeer = new PeerInfo(
                    relayFileInfo.getSenderId() != null ? relayFileInfo.getSenderId() : "relay-" + relayFileInfo.getUploadId(),
                    "relay",
                    0,
                    relayFileInfo.getSenderName() != null ? relayFileInfo.getSenderName() : "Relay User",
                    null
                );
                
                session = new ShareSession(pin, fileInfo, senderPeer, System.currentTimeMillis() + DEFAULT_EXPIRY);
                
                // Cache lại
                globalSessions.put(pin, session);
                
                System.out.println("✓ Tìm thấy PIN trên relay: " + pin + " -> " + fileInfo.getFileName());
                return session;
            }
        }
        
        System.out.println("⚠ Không tìm thấy PIN trên relay: " + pin);
        return null;
    }
    
    /**
     * Hủy PIN
     */
    public void cancelPIN(String pin) {
        ShareSession session = localSessions.get(pin);
        if (session != null) {
            session.cancel();
            localSessions.remove(pin);
            globalSessions.remove(pin);
            System.out.println("✓ Đã hủy PIN: " + pin);
        }
    }
    
    /**
     * Lấy tất cả PIN đang active (do mình tạo)
     */
    public List<ShareSession> getActiveSessions() {
        return new ArrayList<>(localSessions.values());
    }
    
    /**
     * Tạo PIN ngẫu nhiên duy nhất
     */
    private String generateUniquePIN() {
        String pin;
        do {
            pin = generateRandomPIN();
        } while (globalSessions.containsKey(pin));
        return pin;
    }
    
    /**
     * Tạo PIN ngẫu nhiên 6 số
     */
    private String generateRandomPIN() {
        Random random = new Random();
        int number = random.nextInt(999999);
        return String.format("%0" + PIN_LENGTH + "d", number);
    }
    
    /**
     * Gửi PIN đến tất cả LAN peers (bỏ qua Internet/relay peers)
     */
    private void sendPINToAllPeers(ShareSession session) {
        if (peerDiscovery == null) {
            System.out.println("⚠ Không có PeerDiscovery để gửi PIN: " + session.getPin());
            return;
        }

        List<PeerInfo> peers = peerDiscovery.getDiscoveredPeers();
        
        // Lọc chỉ lấy LAN peers (private IP)
        List<PeerInfo> lanPeers = new ArrayList<>();
        for (PeerInfo peer : peers) {
            if (!peer.getPeerId().equals(localPeer.getPeerId()) && isPrivateIP(peer.getIpAddress())) {
                lanPeers.add(peer);
            }
        }
        
        if (lanPeers.isEmpty()) {
            System.out.println("✓ PIN đã được gửi lên relay server, không có LAN peer nào");
            return;
        }
        
        System.out.println("📡 Gửi PIN: " + session.getPin() + " đến " + lanPeers.size() + " LAN peer(s)");

        for (PeerInfo peer : lanPeers) {
            sendPINToPeerTcp(session, peer);
        }
    }
    
    /**
     * Kiểm tra IP có phải private IP (LAN) không
     */
    private boolean isPrivateIP(String ip) {
        if (ip == null || ip.equals("relay")) return false;
        
        try {
            String[] parts = ip.split("\\.");
            if (parts.length != 4) return false;
            
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            
            // 10.0.0.0/8
            if (first == 10) return true;
            // 172.16.0.0/12
            if (first == 172 && second >= 16 && second <= 31) return true;
            // 192.168.0.0/16
            if (first == 192 && second == 168) return true;
            // localhost
            if (first == 127) return true;
            
            return false;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Thread lắng nghe PIN từ peer khác
     */
    private void listenForPINs() {
        while (running) {
            try {
                Socket socket = pinServer.accept();
                executorService.submit(() -> handlePINMessage(socket));
            } catch (SocketException e) {
                // Server đã đóng
                break;
            } catch (IOException e) {
                if (running) {
                    System.err.println("Lỗi khi accept PIN connection: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Xử lý PIN message từ peer khác (với signature verification)
     */
    private void handlePINMessage(Socket socket) {
        try (ObjectInputStream ois = new ObjectInputStream(socket.getInputStream())) {
            
            // Nhận SignedMessage
            SignedMessage signedMsg = (SignedMessage) ois.readObject();
            ShareSession session = (ShareSession) signedMsg.getPayload();
            
            // Verify signature
            PeerInfo senderPeer = session.getOwnerPeer();
            if (!verifyPINSignature(signedMsg, senderPeer)) {
                System.err.println("❌ [Security] Invalid PIN signature from: " + senderPeer.getDisplayName());
                return;
            }
            
            // Lưu vào global sessions
            globalSessions.put(session.getPin(), session);
            
            System.out.println("📥 Nhận PIN: " + session.getPin() + 
                             " từ " + session.getOwnerPeer().getDisplayName() + " ✅ Verified");
            
            // Thông báo listeners
            notifyPINReceived(session);
            
        } catch (Exception e) {
            System.err.println("Lỗi khi xử lý PIN message: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }
    
    /**
     * Thread kiểm tra PIN hết hạn
     */
    private void checkExpiredPINs() {
        while (running) {
            try {
                Thread.sleep(5000); // Check mỗi 5 giây
                
                List<String> expiredPins = new ArrayList<>();
                
                // Kiểm tra local sessions
                for (ShareSession session : localSessions.values()) {
                    if (session.isExpired()) {
                        expiredPins.add(session.getPin());
                    }
                }
                
                // Xóa expired PINs
                for (String pin : expiredPins) {
                    localSessions.remove(pin);
                    globalSessions.remove(pin);
                    System.out.println("⏰ PIN đã hết hạn: " + pin);
                    notifyPINExpired(pin);
                }
                
            } catch (InterruptedException e) {
                break;
            }
        }
    }
    
    /**
     * Gửi PIN đến một peer cụ thể (với TLS + signature)
     */
    public void sendPINToPeerTcp(ShareSession session, PeerInfo peer) {
        try {
            SSLSocket socket = securityManager.createSSLSocket(peer.getIpAddress(), PIN_SERVER_PORT);
            socket.connect(new InetSocketAddress(peer.getIpAddress(), PIN_SERVER_PORT), 3000);
            socket.setSoTimeout(5000);
            socket.startHandshake();
            
            try (ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream())) {
                
                // Tạo signed message
                SignedMessage signedMsg = createSignedPINMessage(session);
                
                oos.writeObject(signedMsg);
                oos.flush();

                System.out.println("📤 Đã gửi PIN (signed) đến " + peer.getDisplayName());

            } finally {
                socket.close();
            }

        } catch (IOException e) {
            System.err.println("Lỗi khi gửi PIN: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("❌ Lỗi tạo signed message: " + e.getMessage());
        }
    }
    
    // ========== Security Helper Methods ==========
    
    /**
     * Tạo signed PIN message
     */
    private SignedMessage createSignedPINMessage(ShareSession session) throws Exception {
        String message = "PIN:" + session.getPin() + ":" + session.getFileInfo().getFileName();
        String signature = securityManager.signMessage(message);
        return new SignedMessage("PIN", localPeer.getPeerId(), signature, session);
    }
    
    /**
     * Verify signature của PIN message
     */
    private boolean verifyPINSignature(SignedMessage signedMsg, PeerInfo senderPeer) {
        try {
            if (senderPeer.getPublicKey() == null) {
                System.err.println("❌ [Security] Sender has no public key");
                return false;
            }
            
            PublicKey publicKey = SecurityManager.decodePublicKey(senderPeer.getPublicKey());
            ShareSession session = (ShareSession) signedMsg.getPayload();
            
            String message = "PIN:" + session.getPin() + ":" + session.getFileInfo().getFileName();
            
            return securityManager.verifySignature(message, signedMsg.getSignature(), publicKey);
            
        } catch (Exception e) {
            System.err.println("❌ [Security] Error verifying PIN signature: " + e.getMessage());
            return false;
        }
    }
    
    // ========== Listener Management ==========
    
    public void addListener(PINCodeListener listener) {
        listeners.add(listener);
    }
    
    public void removeListener(PINCodeListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * Set connection mode
     * @param p2pOnly true = P2P only (LAN), false = Relay only (Internet)
     */
    public void setP2POnlyMode(boolean p2pOnly) {
        this.p2pOnlyMode = p2pOnly;
        System.out.println("🔧 PINCodeService mode: " + (p2pOnly ? "P2P (LAN)" : "Relay (Internet)"));
    }
    
    /**
     * Get current connection mode
     */
    public boolean isP2POnlyMode() {
        return p2pOnlyMode;
    }
    
    private void notifyPINCreated(ShareSession session) {
        for (PINCodeListener listener : listeners) {
            try {
                listener.onPINCreated(session);
            } catch (Exception e) {
                System.err.println("Lỗi trong listener: " + e.getMessage());
            }
        }
    }
    
    private void notifyPINExpired(String pin) {
        for (PINCodeListener listener : listeners) {
            try {
                listener.onPINExpired(pin);
            } catch (Exception e) {
                System.err.println("Lỗi trong listener: " + e.getMessage());
            }
        }
    }
    
    private void notifyPINReceived(ShareSession session) {
        for (PINCodeListener listener : listeners) {
            try {
                listener.onPINReceived(session);
            } catch (Exception e) {
                System.err.println("Lỗi trong listener: " + e.getMessage());
            }
        }
    }
}
