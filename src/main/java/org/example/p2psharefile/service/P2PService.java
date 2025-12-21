package org.example.p2psharefile.service;

import org.example.p2psharefile.model.*;
import org.example.p2psharefile.network.*;
import org.example.p2psharefile.security.SecurityManager;
import org.example.p2psharefile.security.FileHashUtil;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * P2PService - Service chính quản lý toàn bộ ứng dụng P2P (với TLS + Peer Authentication)
 *
 * Đây là lớp "facade" tổng hợp tất cả các module:
 * - Security Manager (Keypair + TLS)
 * - Peer Discovery (TLS + Signatures)
 * - File Search (TLS)
 * - File Transfer (TLS + AES)
 * - PIN Code Service (TLS + Signatures)
 *
 * UI chỉ cần gọi P2PService, không cần biết chi tiết các module bên trong
 * 
 * Security features:
 * - ECDSA keypair cho mỗi peer
 * - TLS cho tất cả network channels
 * - Signed control messages (JOIN/HEARTBEAT/PIN)
 * - Public key distribution qua PeerInfo
 */
public class P2PService {

    private final PeerInfo localPeer;
    private final SecurityManager securityManager;
    private final PeerDiscovery peerDiscovery;
    private final FileSearchService fileSearchService;
    private final FileTransferService fileTransferService;
    private final PINCodeService pinCodeService;
    
    // UltraView Preview Services
    private final PreviewCacheService previewCacheService;
    private final PreviewService previewService;

    private final List<P2PServiceListener> listeners;

    /**
     * Interface để UI nhận thông báo từ P2P Service
     */
    public interface P2PServiceListener {
        void onPeerDiscovered(PeerInfo peer);
        void onPeerLost(PeerInfo peer);
        void onSearchResult(SearchResponse response);
        void onSearchComplete();
        void onTransferProgress(String fileName, long bytesTransferred, long totalBytes);
        void onTransferComplete(String fileName, File file);
        void onTransferError(String fileName, Exception e);
        void onServiceStarted();
        void onServiceStopped();
    }

    private volatile boolean running = false;

    /**
     * Constructor
     *
     * @param displayName Tên hiển thị của peer này
     * @param tcpPort Port TCP để nhận file
     */
    public P2PService(String displayName, int tcpPort) {
        try {
            // Tạo peer info cho local peer
            String peerId = UUID.randomUUID().toString();
            
            // ⭐ BƯỚC 1: Khởi tạo SecurityManager TRƯỚC (để có keypair)
            System.out.println("🔐 Initializing Security Manager...");
            this.securityManager = new SecurityManager(peerId, displayName);
            
            // ⭐ BƯỚC 2: Tạo PeerInfo với public key
            String publicKeyEncoded = securityManager.getPublicKeyEncoded();
            this.localPeer = new PeerInfo(peerId, getLocalIPAddress(), tcpPort, displayName, publicKeyEncoded);
            
            System.out.println("✓ Đã tạo Peer cục bộ với khóa công khai");
            System.out.println("  → Peer ID: " + peerId);
            System.out.println("  → Tên hiển thị: " + displayName);
            System.out.println("  → Khóa công khai: " + publicKeyEncoded.substring(0, 40) + "...");

            // Khởi tạo các service (với SecurityManager)
            this.peerDiscovery = new PeerDiscovery(localPeer, securityManager);
            this.fileSearchService = new FileSearchService(localPeer, peerDiscovery, securityManager);
            this.fileTransferService = new FileTransferService(localPeer, securityManager);
            this.pinCodeService = new PINCodeService(localPeer, peerDiscovery, securityManager);
            
            // UltraView: Khởi tạo preview services
            this.previewCacheService = new PreviewCacheService(peerId, securityManager);
            this.previewService = new PreviewService(localPeer, securityManager, previewCacheService);

            this.listeners = new CopyOnWriteArrayList<>();

            // Đăng ký listener cho peer discovery
            setupPeerDiscoveryListener();
            
        } catch (Exception e) {
            System.err.println("❌ Fatal error initializing P2PService: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to initialize P2PService", e);
        }
    }

    /**
     * Lấy địa chỉ IP local (ưu tiên IPv4 không phải loopback, tránh virtual adapter)
     */
    private String getLocalIPAddress() {
        try {
            java.net.NetworkInterface networkInterface;
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            
            String fallbackIP = null;
            
            while (interfaces.hasMoreElements()) {
                networkInterface = interfaces.nextElement();
                
                // Bỏ qua interface down hoặc loopback
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }
                
                String interfaceName = networkInterface.getName().toLowerCase();
                String displayName = networkInterface.getDisplayName().toLowerCase();
                
                // BỎ QUA virtual interfaces (Docker, Hyper-V, VMware, VirtualBox, vEthernet)
                if (interfaceName.contains("virtual") || 
                    interfaceName.contains("vmware") || 
                    interfaceName.contains("vbox") ||
                    interfaceName.contains("docker") ||
                    interfaceName.startsWith("veth") ||
                    displayName.contains("virtual") ||
                    displayName.contains("hyper-v") ||
                    displayName.contains("vmware") ||
                    displayName.contains("vbox")) {
                    // Bỏ qua virtual interface (không log để tránh nhiễu)
                    continue;
                }
                
                java.util.Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    
                    // Chỉ lấy IPv4 và không phải loopback
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        String ip = addr.getHostAddress();
                        
                        // ƯU TIÊN WiFi và Ethernet thật (Mac, Windows, Linux)
                        // Mac: en0, en1
                        // Windows: wlan, eth (display name có "wireless" hoặc "wi-fi")
                        // Linux: wlan0, eth0
                        if (interfaceName.startsWith("en") || 
                            interfaceName.startsWith("eth") || 
                            interfaceName.startsWith("wlan") ||
                            displayName.contains("wireless") ||
                            displayName.contains("wi-fi") ||
                            displayName.contains("802.11")) {
                            System.out.println("✓ Chọn IP từ physical interface: " + ip + " (" + networkInterface.getName() + " - " + networkInterface.getDisplayName() + ")");
                            return ip;
                        }
                        
                        // Lưu làm fallback (nhưng ưu tiên IP trong dải private phổ biến)
                        if (fallbackIP == null || ip.startsWith("192.168.") || ip.startsWith("10.")) {
                            fallbackIP = ip;
                            System.out.println("  → Fallback IP: " + ip + " (" + networkInterface.getName() + " - " + networkInterface.getDisplayName() + ")");
                        }
                    }
                }
            }
            
            // Nếu không tìm thấy physical interface, dùng fallback
            if (fallbackIP != null) {
                System.out.println("⚠ Dùng fallback IP: " + fallbackIP);
                return fallbackIP;
            }
            
            // Fallback cuối cùng
            System.err.println("⚠ Không tìm thấy IP mạng LAN, dùng localhost");
            return "127.0.0.1";
            
        } catch (Exception e) {
            System.err.println("❌ Lỗi lấy IP: " + e.getMessage());
            return "127.0.0.1";
        }
    }

    /**
     * Setup listener cho peer discovery
     */
    private void setupPeerDiscoveryListener() {
        peerDiscovery.addListener(new PeerDiscovery.PeerDiscoveryListener() {
            @Override
            public void onPeerDiscovered(PeerInfo peer) {
                notifyPeerDiscovered(peer);
            }

            @Override
            public void onPeerLost(PeerInfo peer) {
                notifyPeerLost(peer);
            }
        });
    }
    
    /**
     * Bật relay với cấu hình
     * Gọi trước khi start() để enable relay fallback
     * 
     * @param config Cấu hình relay server
     */
    public void enableRelay(RelayConfig config) {
        fileTransferService.enableRelay(config);
        
        // Set RelayClient cho FileSearchService để tự động upload file khi share
        if (fileTransferService.getRelayClient() != null) {
            fileSearchService.setRelayClient(fileTransferService.getRelayClient());
            
            // Set RelayClient cho PINCodeService để sync PIN qua Internet
            pinCodeService.setRelayClient(fileTransferService.getRelayClient());
        }
        
        System.out.println("✓ Relay đã được bật: " + config.getServerUrl());
        System.out.println("  • Prefer P2P: " + config.isPreferP2P());
        System.out.println("  • P2P Timeout: " + config.getP2pTimeoutMs() + "ms");
        System.out.println("  • Force Relay: " + config.isForceRelay());
    }
    
    /**
     * Kiểm tra relay có được bật không
     */
    public boolean isRelayEnabled() {
        return fileTransferService.isRelayEnabled();
    }

    /**
     * Bắt đầu tất cả các service P2P (với TLS + Peer Authentication)
     */
    public void start() throws IOException {
        if (running) {
            System.out.println("⚠ P2P Service đã đang chạy");
            return;
        }

        System.out.println("🚀 ========== KHỞI ĐỘNG P2P SERVICE (TLS + Auth) ==========");
        System.out.println("   Peer ID: " + localPeer.getPeerId());
        System.out.println("   Display Name: " + localPeer.getDisplayName());
        System.out.println("   IP Address: " + localPeer.getIpAddress());
        System.out.println("   TCP Port Request: " + localPeer.getPort() + " (will auto-assign)");
        System.out.println("   Public Key: " + localPeer.getPublicKey().substring(0, 40) + "...");
        System.out.println("   Security: TLS + ECDSA Signatures");

        try {
            // ⭐ BƯỚC 1: Start FileTransferService TRƯỚC để lấy port thực
            System.out.println("\n[1/5] Khởi động FileTransferService (TLS)...");
            fileTransferService.start();

            // Port giờ đã được set bởi FileTransferService
            int actualPort = localPeer.getPort();
            System.out.println("✓ FileTransferService (TLS) started on port: " + actualPort);

            // ⭐ BƯỚC 2: Start FileSearchService
            System.out.println("\n[2/5] Khởi động FileSearchService (TLS)...");
            fileSearchService.start();
            System.out.println("✓ FileSearchService (TLS) started");
            
            // ⭐ BƯỚC 3: Start PINCodeService
            System.out.println("\n[3/6] Khởi động PINCodeService (TLS + Signatures)...");
            pinCodeService.start();
            System.out.println("✓ PINCodeService (TLS + Signatures) started");
            
            // ⭐ BƯỚC 3.5: Start PreviewService (UltraView)
            System.out.println("\n[3.5/6] Khởi động PreviewService (UltraView)...");
            previewService.start();
            System.out.println("✓ PreviewService started on port: " + previewService.getPreviewPort());

            // ⭐ BƯỚC 4: Start PeerDiscovery NHƯNG CHƯA GỬI JOIN
            System.out.println("\n[4/6] Khởi động PeerDiscovery (TLS + Signatures, listening mode)...");
            peerDiscovery.start(false);  // ← false = không gửi JOIN ngay
            System.out.println("✓ PeerDiscovery (TLS + Signatures) started");

            // ⭐ BƯỚC 5: GIỜ MỚI GỬI JOIN (sau khi TẤT CẢ đã sẵn sàng)
            System.out.println("\n[5/6] Gửi signed JOIN announcement...");
            peerDiscovery.sendJoinAnnouncement();
            
            // ⭐ BƯỚC 6: Đăng ký với relay server (chỉ đăng ký, KHÔNG discover peers ngay)
            // Việc discover peers qua relay sẽ được thực hiện khi chuyển sang Relay mode
            if (fileTransferService.isRelayEnabled()) {
                System.out.println("\n[6/6] Đăng ký peer với relay server...");
                RelayClient relayClient = fileTransferService.getRelayClient();
                if (relayClient != null) {
                    boolean registered = relayClient.registerPeer(localPeer);
                    if (registered) {
                        System.out.println("✓ Đã đăng ký với relay server (sẵn sàng cho Relay mode)");
                        // Heartbeat định kỳ để duy trì kết nối
                        startRelayHeartbeat(relayClient);
                    }
                }
            }

            running = true;

            System.out.println("\n✅ ========== P2P SERVICE READY (SECURE + ULTRAVIEW) ==========");
            System.out.println("📌 Final Peer Info:");
            System.out.println("   - Display Name: " + localPeer.getDisplayName());
            System.out.println("   - IP Address: " + localPeer.getIpAddress());
            System.out.println("   - TCP Port: " + localPeer.getPort());
            System.out.println("   - Preview Port: " + previewService.getPreviewPort());
            System.out.println("   - Peer ID: " + localPeer.getPeerId());
            System.out.println("   - Public Key: " + localPeer.getPublicKey().substring(0, 40) + "...");
            System.out.println("   - TLS: Enabled ✅");
            System.out.println("   - ECDSA Signatures: Enabled ✅");
            System.out.println("   - UltraView Preview: Enabled ✅");
            System.out.println("==================================================\n");

            notifyServiceStarted();

        } catch (IOException e) {
            System.err.println("❌ Lỗi khi khởi động P2P Service: " + e.getMessage());
            e.printStackTrace();
            stop(); // Dừng các service đã khởi động
            throw e;
        }
    }

    /**
     * Dừng tất cả các service P2P
     */
    public void stop() {
        if (!running) return;

        System.out.println("🛑 Đang dừng P2P Service...");

        pinCodeService.stop();
        previewService.stop();  // UltraView
        fileTransferService.stop();
        fileSearchService.stop();
        peerDiscovery.stop();

        running = false;
        System.out.println("✅ P2P Service đã dừng");
        notifyServiceStopped();
    }

    /**
     * Lấy port thực tế đang sử dụng (sau khi được auto-assign)
     */
    public int getActualPort() {
        return localPeer.getPort();
    }

    /**
     * Thêm file để chia sẻ
     *
     * @param file File cần chia sẻ
     */
    public void addSharedFile(File file) {
        if (!file.exists() || !file.isFile()) {
            System.err.println("❌ File không tồn tại: " + file.getAbsolutePath());
            return;
        }

        try {
            // Tính SHA-256 hash cho file
            System.out.println("🔐 Đang tính hash cho: " + file.getName() + "...");
            String fileHash = FileHashUtil.calculateSHA256(file);
            String md5Checksum = FileHashUtil.calculateMD5(file);
            
            System.out.println("  ✓ SHA-256: " + fileHash.substring(0, 16) + "...");
            System.out.println("  ✓ MD5: " + md5Checksum.substring(0, 16) + "...");
            
            // Tạo FileInfo với hash
            FileInfo fileInfo = new FileInfo(
                    file.getName(),
                    file.length(),
                    file.getAbsolutePath(),
                    md5Checksum,
                    localPeer.getPeerId()
            );
            fileInfo.setFileHash(fileHash);

            fileSearchService.addSharedFile(file.getParent(), fileInfo);
            
            // UltraView: Tạo preview manifest (từ file gốc, TRƯỚC khi mã hóa)
            try {
                System.out.println("  📸 Đang tạo preview từ file gốc...");
                // Force regenerate để áp dụng code mới (xóa cache cũ)
                PreviewManifest manifest = previewCacheService.getOrCreateManifest(file, true);
                if (manifest != null) {
                    System.out.println("  ✓ Preview manifest đã tạo (chứa: " + manifest.getAvailableTypes() + ")");
                    System.out.println("  💡 Preview sẽ được gửi đến client mà KHÔNG cần giải mã");
                }
            } catch (Exception e) {
                System.err.println("  ⚠️ Không thể tạo preview: " + e.getMessage());
            }
            
            System.out.println("✅ Đã thêm file chia sẻ: " + file.getName());
            
        } catch (IOException e) {
            System.err.println("❌ Lỗi khi tính hash: " + e.getMessage());
        }
    }
    
    /**
     * Xóa file khỏi danh sách chia sẻ
     *
     * @param fileInfo File cần xóa
     */
    public void removeSharedFile(FileInfo fileInfo) {
        if (fileInfo == null) return;
        
        File file = new File(fileInfo.getFilePath());
        fileSearchService.removeSharedFile(file.getParent(), fileInfo.getFileName());
        System.out.println("🗑️ Đã xóa file khỏi chia sẻ: " + fileInfo.getFileName());
    }

    /**
     * Thêm thư mục để chia sẻ (tất cả file trong thư mục)
     *
     * @param directory Thư mục cần chia sẻ
     */
    public void addSharedDirectory(File directory) {
        if (!directory.exists() || !directory.isDirectory()) {
            System.err.println("❌ Thư mục không tồn tại: " + directory.getAbsolutePath());
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) return;

        int count = 0;
        for (File file : files) {
            if (file.isFile()) {
                addSharedFile(file);
                count++;
            }
        }

        System.out.println("✓ Đã thêm " + count + " file từ thư mục: " + directory.getName());
    }

    /**
     * Tìm kiếm file trong mạng P2P
     *
     * @param query Từ khóa tìm kiếm
     */
    public void searchFile(String query) {
        if (!running) {
            System.err.println("❌ P2P Service chưa khởi động");
            return;
        }

        if (query == null || query.trim().isEmpty()) {
            System.err.println("❌ Từ khóa tìm kiếm không hợp lệ");
            return;
        }

        fileSearchService.searchFile(query.trim(), new FileSearchService.SearchResultCallback() {
            @Override
            public void onSearchResult(SearchResponse response) {
                notifySearchResult(response);
            }

            @Override
            public void onSearchComplete() {
                notifySearchComplete();
            }
        });
    }

    /**
     * Download file từ peer
     *
     * @param peer Peer có file
     * @param fileInfo Thông tin file cần download
     * @param saveDirectory Thư mục lưu file
     */
    public void downloadFile(PeerInfo peer, FileInfo fileInfo, String saveDirectory) {
        if (!running) {
            System.err.println("❌ P2P Service chưa khởi động");
            return;
        }

        fileTransferService.downloadFile(
                peer,
                fileInfo,
                saveDirectory,
                new FileTransferService.TransferProgressListener() {
                    @Override
                    public void onProgress(long bytesTransferred, long totalBytes) {
                        notifyTransferProgress(fileInfo.getFileName(), bytesTransferred, totalBytes);
                    }

                    @Override
                    public void onComplete(File file) {
                        notifyTransferComplete(fileInfo.getFileName(), file);
                    }

                    @Override
                    public void onError(Exception e) {
                        notifyTransferError(fileInfo.getFileName(), e);
                    }
                    
                    @Override
                    public void onP2PFailed(String reason) {
                        System.out.println("⚠️  P2P failed: " + reason + ", switching to relay...");
                    }
                    
                    @Override
                    public void onRelayFallback(String transferId) {
                        System.out.println("🌐 Using relay transfer: " + transferId);
                    }
                }
        );
    }

    /**
     * Lấy danh sách peer đã phát hiện
     */
    public List<PeerInfo> getDiscoveredPeers() {
        return peerDiscovery.getDiscoveredPeers();
    }

    /**
     * Lấy số lượng peer online
     */
    public int getPeerCount() {
        return peerDiscovery.getPeerCount();
    }

    /**
     * Lấy danh sách file đang chia sẻ
     */
    public List<FileInfo> getSharedFiles() {
        return fileSearchService.getAllSharedFiles();
    }

    // ========== PIN Code Methods ==========

    /**
     * Tạo mã PIN cho file (Send Anywhere style)
     *
     * @param fileInfo File cần chia sẻ
     * @return ShareSession chứa PIN code
     */
    public ShareSession createSharePIN(FileInfo fileInfo) {
        if (!running) {
            System.err.println("❌ P2P Service chưa khởi động");
            return null;
        }

        ShareSession session = pinCodeService.createPIN(fileInfo);

        // PIN sẽ được gửi bởi PINCodeService.sendPINToAllPeers
        
        return session;
    }

    /**
     * Nhận file bằng mã PIN
     *
     * @param pin Mã PIN 6 số
     * @param saveDirectory Thư mục lưu file
     * @throws IllegalStateException Nếu service chưa chạy
     * @throws IllegalArgumentException Nếu PIN không hợp lệ hoặc đã hết hạn
     */
    public void receiveByPIN(String pin, String saveDirectory) {
        if (!running) {
            throw new IllegalStateException("P2P Service chưa khởi động");
        }

        // Tìm session bằng PIN
        System.out.println("🔍 Đang tìm PIN: " + pin);
        ShareSession session = pinCodeService.findByPIN(pin);

        if (session == null) {
            throw new IllegalArgumentException("Không tìm thấy PIN: " + pin);
        }

        if (session.isExpired()) {
            throw new IllegalArgumentException("PIN đã hết hạn: " + pin);
        }

        System.out.println("✓ Tìm thấy PIN: " + pin + " -> " + session.getFileInfo().getFileName());
        System.out.println("  📁 File: " + session.getFileInfo().getFileName());
        System.out.println("  📏 Size: " + session.getFileInfo().getFileSize() + " bytes");
        System.out.println("  🌐 Relay: " + (session.getFileInfo().getRelayFileInfo() != null ? "Yes" : "No"));
        
        // Download file từ owner peer
        downloadFile(session.getOwnerPeer(), session.getFileInfo(), saveDirectory);
    }

    /**
     * Hủy mã PIN
     */
    public void cancelPIN(String pin) {
        pinCodeService.cancelPIN(pin);
    }

    /**
     * Lấy danh sách PIN đang active
     */
    public List<ShareSession> getActivePINs() {
        return pinCodeService.getActiveSessions();
    }

    /**
     * Thêm listener cho PIN events
     */
    public void addPINListener(PINCodeService.PINCodeListener listener) {
        pinCodeService.addListener(listener);
    }

    /**
     * Lấy số lượng file đang chia sẻ
     */
    public int getSharedFileCount() {
        return fileSearchService.getSharedFileCount();
    }

    /**
     * Lấy thông tin local peer
     */
    public PeerInfo getLocalPeer() {
        return localPeer;
    }

    /**
     * Kiểm tra service có đang chạy không
     */
    public boolean isRunning() {
        return running;
    }
    
    /**
     * Lấy RelayClient instance để download/upload
     */
    public org.example.p2psharefile.network.RelayClient getRelayClient() {
        return fileTransferService != null ? fileTransferService.getRelayClient() : null;
    }
    
    // ========== UltraView Preview Methods ==========
    
    /**
     * Request preview manifest từ peer
     * 
     * @param peer Peer có file
     * @param fileHash Hash của file
     * @return PreviewManifest hoặc null
     */
    public PreviewManifest requestPreviewManifest(PeerInfo peer, String fileHash) {
        if (!running) {
            System.err.println("❌ P2P Service chưa khởi động");
            return null;
        }
        
        return previewService.requestManifest(peer, fileHash);
    }
    
    /**
     * Request preview content từ peer
     * 
     * @param peer Peer có file
     * @param fileHash Hash của file
     * @param type Loại preview
     * @return PreviewContent hoặc null
     */
    public PreviewContent requestPreviewContent(PeerInfo peer, String fileHash, 
                                               PreviewManifest.PreviewType type) {
        if (!running) {
            System.err.println("❌ P2P Service chưa khởi động");
            return null;
        }
        
        return previewService.requestContent(peer, fileHash, type);
    }
    
    /**
     * Lấy preview manifest cho file local
     * 
     * @param fileHash Hash của file
     * @return PreviewManifest hoặc null
     */
    public PreviewManifest getLocalPreviewManifest(String fileHash) {
        return previewCacheService.getManifest(fileHash);
    }
    
    /**
     * Lấy hoặc tạo preview manifest cho file
     * 
     * @param file File cần tạo preview
     * @return PreviewManifest hoặc null
     */
    public PreviewManifest getOrCreatePreviewManifest(File file) {
        return previewCacheService.getOrCreateManifest(file);
    }
    
    /**
     * Lấy preview cache service
     */
    public PreviewCacheService getPreviewCacheService() {
        return previewCacheService;
    }
    
    /**
     * Lấy preview service
     */
    public PreviewService getPreviewService() {
        return previewService;
    }

    // ========== Listener Management ==========

    public void addListener(P2PServiceListener listener) {
        listeners.add(listener);
    }

    public void removeListener(P2PServiceListener listener) {
        listeners.remove(listener);
    }

    private void notifyPeerDiscovered(PeerInfo peer) {
        for (P2PServiceListener listener : listeners) {
            try {
                listener.onPeerDiscovered(peer);
            } catch (Exception e) {
                System.err.println("Lỗi trong listener: " + e.getMessage());
            }
        }
    }

    private void notifyPeerLost(PeerInfo peer) {
        for (P2PServiceListener listener : listeners) {
            try {
                listener.onPeerLost(peer);
            } catch (Exception e) {
                System.err.println("Lỗi trong listener: " + e.getMessage());
            }
        }
    }

    private void notifySearchResult(SearchResponse response) {
        for (P2PServiceListener listener : listeners) {
            try {
                listener.onSearchResult(response);
            } catch (Exception e) {
                System.err.println("Lỗi trong listener: " + e.getMessage());
            }
        }
    }

    private void notifySearchComplete() {
        for (P2PServiceListener listener : listeners) {
            try {
                listener.onSearchComplete();
            } catch (Exception e) {
                System.err.println("Lỗi trong listener: " + e.getMessage());
            }
        }
    }

    private void notifyTransferProgress(String fileName, long bytesTransferred, long totalBytes) {
        for (P2PServiceListener listener : listeners) {
            try {
                listener.onTransferProgress(fileName, bytesTransferred, totalBytes);
            } catch (Exception e) {
                System.err.println("Lỗi trong listener: " + e.getMessage());
            }
        }
    }

    private void notifyTransferComplete(String fileName, File file) {
        for (P2PServiceListener listener : listeners) {
            try {
                listener.onTransferComplete(fileName, file);
            } catch (Exception e) {
                System.err.println("Lỗi trong listener: " + e.getMessage());
            }
        }
    }
    
    /**
     * Khởi động relay heartbeat (mỗi 30 giây)
     */
    private void startRelayHeartbeat(RelayClient relayClient) {
        Thread heartbeatThread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(30000); // 30 giây
                    if (running && relayClient != null) {
                        relayClient.sendHeartbeat(localPeer.getPeerId());
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    // Ignore heartbeat errors
                }
            }
        }, "RelayHeartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }
    
    /**
     * Set connection mode cho tất cả services
     * @param p2pOnly true = P2P only (LAN), false = Relay only (Internet)
     */
    public void setP2POnlyMode(boolean p2pOnly) {
        System.out.println("\n🔧 ========== CHUYỂN CHẾ ĐỘ KẾT NỐI ==========");
        System.out.println("   Mode: " + (p2pOnly ? "P2P (Mạng LAN - Bảo mật cao)" : "Relay (Internet - Kết nối mọi nơi)"));
        
        // Set mode cho PeerDiscovery
        peerDiscovery.setP2POnlyMode(p2pOnly);
        
        // Set mode cho FileSearchService
        fileSearchService.setP2POnlyMode(p2pOnly);
        
        // Set mode cho PINCodeService
        pinCodeService.setP2POnlyMode(p2pOnly);
        
        // Nếu chuyển sang Relay mode, trigger discover peers qua relay
        if (!p2pOnly && fileTransferService.isRelayEnabled()) {
            RelayClient relayClient = fileTransferService.getRelayClient();
            if (relayClient != null) {
                new Thread(() -> {
                    try {
                        List<PeerInfo> relayPeers = relayClient.discoverPeers(localPeer.getPeerId());
                        for (PeerInfo peer : relayPeers) {
                            peerDiscovery.addDiscoveredPeer(peer);
                            notifyPeerDiscovered(peer);
                        }
                        if (!relayPeers.isEmpty()) {
                            System.out.println("🌐 Đã phát hiện " + relayPeers.size() + " peer(s) qua Internet");
                        }
                    } catch (Exception e) {
                        System.err.println("Lỗi discover peers qua relay: " + e.getMessage());
                    }
                }, "RelayDiscoveryOnModeSwitch").start();
            }
        }
        
        System.out.println("✅ Đã chuyển chế độ kết nối thành công!");
        System.out.println("================================================\n");
    }
    
    /**
     * Kiểm tra mode hiện tại
     */
    public boolean isP2POnlyMode() {
        return fileSearchService.isP2POnlyMode();
    }


    private void notifyTransferError(String fileName, Exception e) {
        for (P2PServiceListener listener : listeners) {
            try {
                listener.onTransferError(fileName, e);
            } catch (Exception ex) {
                System.err.println("Lỗi trong listener: " + ex.getMessage());
            }
        }
    }

    private void notifyServiceStarted() {
        for (P2PServiceListener listener : listeners) {
            try {
                listener.onServiceStarted();
            } catch (Exception e) {
                System.err.println("Lỗi trong listener: " + e.getMessage());
            }
        }
    }

    private void notifyServiceStopped() {
        for (P2PServiceListener listener : listeners) {
            try {
                listener.onServiceStopped();
            } catch (Exception e) {
                System.err.println("Lỗi trong listener: " + e.getMessage());
            }
        }
    }
}
