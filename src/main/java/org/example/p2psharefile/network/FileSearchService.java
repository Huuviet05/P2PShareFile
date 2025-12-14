package org.example.p2psharefile.network;

import org.example.p2psharefile.model.*;
import org.example.p2psharefile.security.SecurityManager;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * FileSearchService - Tìm kiếm file qua TLS/SSL socket
 *
 * Security improvements:
 * - Sử dụng SSLSocket để encrypt search requests/responses
 * - Bảo vệ metadata (file names, sizes) khỏi eavesdropping
 */
public class FileSearchService {

    private static final int SEARCH_PORT = 8891;
    private static final int SEARCH_TIMEOUT = 5000;
    private static final int CONNECTION_TIMEOUT = 2000;

    private final PeerInfo localPeer;
    private final PeerDiscovery peerDiscovery;
    private final SecurityManager securityManager;
    private final Map<String, List<FileInfo>> sharedFiles;
    private final Set<String> processedRequests;
    
    private RelayClient relayClient; // Để upload file lên relay server khi share

    private SSLServerSocket searchServer;
    private ExecutorService executorService;
    private ScheduledExecutorService scheduledExecutor;
    private volatile boolean running = false;

    private final Map<String, SearchResultCallback> activeSearches;

    public interface SearchResultCallback {
        void onSearchResult(SearchResponse response);
        void onSearchComplete();
    }

    /**
     * Forward request to discovered peers (except origin/self) and relay any SearchResponse
     * that contains files back to the origin peer (if origin known via PeerDiscovery).
     */
    private void forwardRequestToPeersAndRelay(SearchRequest request) {
        List<PeerInfo> peers = peerDiscovery.getDiscoveredPeers();

        for (PeerInfo peer : peers) {
            try {
                // Skip self and origin
                if (peer.getPeerId().equals(localPeer.getPeerId())) continue;
                if (peer.getPeerId().equals(request.getOriginPeerId())) continue;

                SSLSocket socket = securityManager.createSSLSocket(peer.getIpAddress(), SEARCH_PORT);
                socket.connect(new InetSocketAddress(peer.getIpAddress(), SEARCH_PORT), CONNECTION_TIMEOUT);
                socket.setSoTimeout(SEARCH_TIMEOUT);
                socket.startHandshake();

                try (ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
                     ObjectInputStream ois = new ObjectInputStream(socket.getInputStream())) {

                    oos.writeObject(request);
                    oos.flush();

                    Object obj = ois.readObject();
                    if (obj instanceof SearchResponse) {
                        SearchResponse resp = (SearchResponse) obj;
                        if (resp != null && !resp.getFoundFiles().isEmpty()) {
                            // Relay this response to origin peer if possible
                            PeerInfo origin = peerDiscovery.getPeerById(request.getOriginPeerId());
                            if (origin != null && !origin.getPeerId().equals(localPeer.getPeerId())) {
                                try (SSLSocket relay = securityManager.createSSLSocket(origin.getIpAddress(), SEARCH_PORT)) {
                                    relay.connect(new InetSocketAddress(origin.getIpAddress(), SEARCH_PORT), CONNECTION_TIMEOUT);
                                    relay.startHandshake();
                                    try (ObjectOutputStream roos = new ObjectOutputStream(relay.getOutputStream())) {
                                        roos.writeObject(resp);
                                        roos.flush();
                                    }
                                } catch (IOException e) {
                                    // Cannot relay to origin - ignore
                                }
                            }
                        }
                    }

                } finally {
                    try { socket.close(); } catch (IOException ignored) {}
                }

            } catch (Exception e) {
                // ignore individual peer failures
            }
        }
    }

    public FileSearchService(PeerInfo localPeer, PeerDiscovery peerDiscovery, SecurityManager securityManager) {
        this.localPeer = localPeer;
        this.peerDiscovery = peerDiscovery;
        this.securityManager = securityManager;
        this.sharedFiles = new ConcurrentHashMap<>();
        this.processedRequests = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.activeSearches = new ConcurrentHashMap<>();
    }

    public void start() throws IOException {
        if (running) return;

        running = true;

        // SSLServerSocket để nhận search request
        searchServer = securityManager.createSSLServerSocket(SEARCH_PORT);
        searchServer.setReuseAddress(true);

        System.out.println("✓ File Search Service (TLS) đã khởi động trên port " + SEARCH_PORT);
        System.out.println("  → Local Peer IP: " + localPeer.getIpAddress());

        executorService = Executors.newCachedThreadPool();
        scheduledExecutor = Executors.newScheduledThreadPool(1);

        // Thread lắng nghe search request
        executorService.submit(this::acceptSearchRequests);

        System.out.println("✓ File Search Service (TLS) đã sẵn sàng");
    }

    public void stop() {
        running = false;

        try {
            if (searchServer != null && !searchServer.isClosed()) {
                searchServer.close();
            }
        } catch (IOException e) {
            System.err.println("Lỗi đóng search server: " + e.getMessage());
        }

        if (executorService != null) {
            executorService.shutdownNow();
        }
        if (scheduledExecutor != null) {
            scheduledExecutor.shutdownNow();
        }
    }

    /**
     * Lắng nghe search request qua TCP
     */
    private void acceptSearchRequests() {
        System.out.println("👂 Đang lắng nghe search request trên port " + SEARCH_PORT);

        while (running) {
            try {
                Socket clientSocket = searchServer.accept();
                clientSocket.setSoTimeout(5000);

                // Xử lý request trong thread riêng
                executorService.submit(() -> handleSearchConnection(clientSocket));

            } catch (SocketException e) {
                if (running) {
                    System.err.println("Socket error: " + e.getMessage());
                }
                break;
            } catch (IOException e) {
                if (running) {
                    System.err.println("Lỗi accept search connection: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Xử lý kết nối search
     */
    private void handleSearchConnection(Socket socket) {
        try {
            ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
            ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());

            // Nhận message
            Object message = ois.readObject();

            if (message instanceof SearchRequest) {
                SearchRequest request = (SearchRequest) message;

                // Bỏ qua request từ chính mình
                if (request.getOriginPeerId().equals(localPeer.getPeerId())) {
                    socket.close();
                    return;
                }

                // Xử lý search request
                SearchResponse response = processSearchRequest(request);

                // Gửi response (kết quả local)
                oos.writeObject(response);
                oos.flush();

                // Nếu còn TTL, forward request đến peers khác và relay response về origin khi có kết quả
                if (request.canForward()) {
                    // Tạo bản sao request để forward (giảm TTL)
                    SearchRequest forwardReq = new SearchRequest(request.getRequestId(), request.getOriginPeerId(), request.getSearchQuery(), request.getTtl());
                    forwardReq.decrementTTL();

                    // Submit forwarding task
                    executorService.submit(() -> {
                        forwardRequestToPeersAndRelay(forwardReq);
                    });
                }
            }

            socket.close();

        } catch (Exception e) {
            if (running) {
                System.err.println("Lỗi xử lý search connection: " + e.getMessage());
            }
        }
    }

    /**
     * Xử lý search request và trả về response
     */
    private SearchResponse processSearchRequest(SearchRequest request) {
        // Tránh xử lý trùng
        if (processedRequests.contains(request.getRequestId())) {
            System.out.println("⚠ Request đã xử lý rồi: " + request.getRequestId());
            return new SearchResponse(request.getRequestId(), localPeer, new ArrayList<>());
        }
        processedRequests.add(request.getRequestId());

        System.out.println("🔍 Nhận search request: \"" + request.getSearchQuery() + "\"");
        System.out.println("  → Số file đang chia sẻ: " + getSharedFileCount());

        // Tìm file local
        List<FileInfo> foundFiles = new ArrayList<>();
        String query = request.getSearchQuery().toLowerCase();

        for (Map.Entry<String, List<FileInfo>> entry : sharedFiles.entrySet()) {
            System.out.println("  → Kiểm tra thư mục: " + entry.getKey() + " (" + entry.getValue().size() + " files)");
            for (FileInfo file : entry.getValue()) {
                if (file.getFileName().toLowerCase().contains(query)) {
                    foundFiles.add(file);
                    System.out.println("    ✓ KHỚP: " + file.getFileName());
                }
            }
        }

        if (!foundFiles.isEmpty()) {
            System.out.println("📦 Tìm thấy " + foundFiles.size() + " file");
        } else {
            System.out.println("⚠ Không tìm thấy file nào khớp với: \"" + query + "\"");
        }

        return new SearchResponse(request.getRequestId(), localPeer, foundFiles);
    }

    /**
     * Tìm kiếm file từ các peer
     */
    public void searchFile(String query, SearchResultCallback callback) {
        String requestId = UUID.randomUUID().toString();
        SearchRequest request = new SearchRequest(requestId, localPeer.getPeerId(), query, 5);

        activeSearches.put(requestId, callback);

        System.out.println("🔍 Bắt đầu tìm kiếm: \"" + query + "\"");

        // Lấy danh sách peer
        List<PeerInfo> peers = peerDiscovery.getDiscoveredPeers();

        if (peers.isEmpty()) {
            System.out.println("⚠ Không có peer nào để tìm kiếm");
            callback.onSearchComplete();
            activeSearches.remove(requestId);
            return;
        }

        System.out.println("📡 Gửi search request đến " + peers.size() + " peer(s)");

        // Gửi search request đến từng peer
        CountDownLatch latch = new CountDownLatch(peers.size());

        for (PeerInfo peer : peers) {
            executorService.submit(() -> {
                try {
                    sendSearchRequest(peer, request, callback);
                } finally {
                    latch.countDown();
                }
            });
        }

        // Timeout sau SEARCH_TIMEOUT
        scheduledExecutor.schedule(() -> {
            try {
                // Đợi tất cả peer phản hồi hoặc timeout
                latch.await(SEARCH_TIMEOUT, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // Ignore
            }

            activeSearches.remove(requestId);
            callback.onSearchComplete();

        }, SEARCH_TIMEOUT, TimeUnit.MILLISECONDS);
    }

    /**
     * Gửi search request đến một peer (với TLS)
     */
    private void sendSearchRequest(PeerInfo peer, SearchRequest request, SearchResultCallback callback) {
        SSLSocket socket = null;
        try {
            // Kết nối đến peer với TLS
            socket = securityManager.createSSLSocket(peer.getIpAddress(), SEARCH_PORT);
            socket.connect(new InetSocketAddress(peer.getIpAddress(), SEARCH_PORT), CONNECTION_TIMEOUT);
            socket.setSoTimeout(5000);
            socket.startHandshake();

            // Gửi request
            ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
            oos.writeObject(request);
            oos.flush();

            // Nhận response
            ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
            SearchResponse response = (SearchResponse) ois.readObject();

            // Xử lý response
            if (!response.getFoundFiles().isEmpty()) {
                System.out.println("📥 Nhận response: " + response.getFoundFiles().size() +
                        " files từ " + peer.getDisplayName());
                callback.onSearchResult(response);
            }

        } catch (IOException | ClassNotFoundException e) {
            System.err.println("⚠ Không thể kết nối đến peer " + peer.getDisplayName() +
                    ": " + e.getMessage());
        } catch (Exception e) {
            System.err.println("❌ Lỗi search: " + e.getMessage());
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Thêm file chia sẻ
     */
    public void addSharedFile(String directory, FileInfo fileInfo) {
        sharedFiles.computeIfAbsent(directory, k -> new CopyOnWriteArrayList<>()).add(fileInfo);
        System.out.println("✓ [FileSearchService] Đã thêm file: " + fileInfo.getFileName() +
                " vào thư mục: " + directory);
        System.out.println("  → Tổng số file đang chia sẻ: " + getSharedFileCount());
        
        // Tự động upload lên relay server nếu relay enabled
        if (relayClient != null) {
            uploadToRelayAsync(fileInfo);
        }
    }
    
    /**
     * Upload file lên relay server (async)
     */
    private void uploadToRelayAsync(FileInfo fileInfo) {
        // Tạo thread riêng để upload (không block UI)
        new Thread(() -> {
            try {
                System.out.println("📤 Đang upload file lên relay server: " + fileInfo.getFileName());
                
                java.io.File file = new java.io.File(fileInfo.getFilePath());
                if (!file.exists()) {
                    System.err.println("❌ File không tồn tại: " + fileInfo.getFilePath());
                    return;
                }
                
                // Tạo RelayUploadRequest
                org.example.p2psharefile.model.RelayUploadRequest request = 
                    new org.example.p2psharefile.model.RelayUploadRequest(
                        fileInfo.getFileName(),
                        file.length(),
                        localPeer.getPeerId(),
                        localPeer.getDisplayName(),
                        fileInfo.getChecksum()
                    );
                
                // Upload với progress listener
                String uploadId = relayClient.uploadFile(file, request, new RelayClient.RelayTransferListener() {
                    @Override
                    public void onProgress(org.example.p2psharefile.model.RelayTransferProgress progress) {
                        if (progress.getPercentage() % 20 == 0 || progress.getPercentage() == 100) {
                            System.out.printf("  📊 Upload progress: %.1f%% (%d/%d bytes)\n",
                                progress.getPercentage(),
                                progress.getCurrentBytes(),
                                progress.getTotalBytes());
                        }
                    }
                    
                    @Override
                    public void onComplete(org.example.p2psharefile.model.RelayFileInfo relayFileInfo) {
                        System.out.println("✅ Đã upload file lên relay: " + fileInfo.getFileName());
                        System.out.println("  → Upload ID: " + relayFileInfo.getUploadId());
                        System.out.println("  → Download URL: " + relayFileInfo.getDownloadUrl());
                        
                        // Lưu RelayFileInfo vào FileInfo
                        fileInfo.setRelayFileInfo(relayFileInfo);
                    }
                    
                    @Override
                    public void onError(Exception e) {
                        System.err.println("❌ Lỗi upload file lên relay: " + e.getMessage());
                    }
                });
                
            } catch (Exception e) {
                System.err.println("❌ Lỗi upload file: " + e.getMessage());
            }
        }, "RelayUpload-" + fileInfo.getFileName()).start();
    }
    
    /**
     * Set RelayClient (được gọi từ P2PService)
     */
    public void setRelayClient(RelayClient relayClient) {
        this.relayClient = relayClient;
    }

    public void removeSharedFile(String directory, String fileName) {
        List<FileInfo> files = sharedFiles.get(directory);
        if (files != null) {
            files.removeIf(f -> f.getFileName().equals(fileName));
        }
    }

    public Map<String, List<FileInfo>> getSharedFiles() {
        return new HashMap<>(sharedFiles);
    }

    /**
     * Lấy tất cả file đang chia sẻ (flatten)
     */
    public List<FileInfo> getAllSharedFiles() {
        List<FileInfo> allFiles = new ArrayList<>();
        for (List<FileInfo> files : sharedFiles.values()) {
            allFiles.addAll(files);
        }
        return allFiles;
    }

    /**
     * Đếm số lượng file đang chia sẻ
     */
    public int getSharedFileCount() {
        int count = 0;
        for (List<FileInfo> files : sharedFiles.values()) {
            count += files.size();
        }
        return count;
    }
}