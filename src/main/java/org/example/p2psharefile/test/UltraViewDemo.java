package org.example.p2psharefile.test;

import org.example.p2psharefile.model.*;
import org.example.p2psharefile.service.P2PService;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.List;

/**
 * UltraViewDemo - Demo tính năng preview
 * 
 * Cách chạy:
 * 1. Chạy 2 instance: Peer A (owner) và Peer B (requester)
 * 2. Peer A share file
 * 3. Peer B search và request preview
 */
public class UltraViewDemo {
    
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: UltraViewDemo <mode>");
            System.out.println("  mode: owner | requester");
            return;
        }
        
        String mode = args[0];
        
        if ("owner".equals(mode)) {
            runOwnerDemo();
        } else if ("requester".equals(mode)) {
            runRequesterDemo();
        } else {
            System.err.println("Invalid mode: " + mode);
        }
    }
    
    /**
     * Demo peer owner: share file với preview
     */
    private static void runOwnerDemo() throws Exception {
        System.out.println("========== ULTRAVIEW DEMO - OWNER ==========\n");
        
        // Khởi tạo P2P Service
        P2PService p2pService = new P2PService("Owner Peer", 0);
        p2pService.start();
        
        System.out.println("\n[Owner] P2P Service started");
        System.out.println("[Owner] Port: " + p2pService.getActualPort());
        
        // Thêm một số file để share
        shareExampleFiles(p2pService);
        
        System.out.println("\n[Owner] Đã share " + p2pService.getSharedFileCount() + " files");
        System.out.println("[Owner] Preview cache size: " + 
            p2pService.getPreviewCacheService().getManifestCacheSize());
        
        // Keep running
        System.out.println("\n[Owner] Đang lắng nghe preview requests...");
        System.out.println("[Owner] Press Ctrl+C to stop");
        
        // Prevent exit
        Thread.currentThread().join();
    }
    
    /**
     * Share example files
     */
    private static void shareExampleFiles(P2PService p2pService) {
        // 1. Share ảnh (nếu có)
        String imagePath = "C:\\Users\\NGUYEN HUU VIET\\Downloads\\Hearty Sandwich Logo in Yellow and Burgundy.png";  // Thay bằng path thật
        File imageFile = new File(imagePath);
        if (imageFile.exists()) {
            System.out.println("\n[Owner] Sharing image: " + imagePath);
            p2pService.addSharedFile(imageFile);
            
            // Kiểm tra manifest
            PreviewManifest manifest = p2pService.getOrCreatePreviewManifest(imageFile);
            if (manifest != null) {
                System.out.println("  ✓ Preview types: " + manifest.getAvailableTypes());
                System.out.println("  ✓ Signature: " + (manifest.getSignature() != null ? "Yes" : "No"));
            }
        }
        
        // 2. Share text file (nếu có)
        String textPath = "V:\\LapTrinhMang\\P2PShareFile\\SECURITY_BENEFITS.md";
        File textFile = new File(textPath);
        if (textFile.exists()) {
            System.out.println("\n[Owner] Sharing text: " + textPath);
            p2pService.addSharedFile(textFile);
            
            PreviewManifest manifest = p2pService.getOrCreatePreviewManifest(textFile);
            if (manifest != null) {
                System.out.println("  ✓ Preview types: " + manifest.getAvailableTypes());
                System.out.println("  ✓ Snippet length: " + 
                    (manifest.getSnippet() != null ? manifest.getSnippet().length() : 0) + " chars");
            }
        }
        
        // 3. Share archive (nếu có)
        String archivePath = "example.zip";
        File archiveFile = new File(archivePath);
        if (archiveFile.exists()) {
            System.out.println("\n[Owner] Sharing archive: " + archivePath);
            p2pService.addSharedFile(archiveFile);
            
            PreviewManifest manifest = p2pService.getOrCreatePreviewManifest(archiveFile);
            if (manifest != null) {
                System.out.println("  ✓ Preview types: " + manifest.getAvailableTypes());
                System.out.println("  ✓ Archive files: " + 
                    (manifest.getArchiveListing() != null ? manifest.getArchiveListing().size() : 0));
            }
        }
    }
    
    /**
     * Demo peer requester: search và request preview
     */
    private static void runRequesterDemo() throws Exception {
        System.out.println("========== ULTRAVIEW DEMO - REQUESTER ==========\n");
        
        // Khởi tạo P2P Service
        P2PService p2pService = new P2PService("Requester Peer", 0);
        p2pService.start();
        
        System.out.println("\n[Requester] P2P Service started");
        
        // Đợi discover peers
        System.out.println("\n[Requester] Waiting for peers...");
        Thread.sleep(3000);
        
        List<PeerInfo> peers = p2pService.getDiscoveredPeers();
        System.out.println("[Requester] Found " + peers.size() + " peers");
        
        if (peers.isEmpty()) {
            System.out.println("\n[Requester] No peers found. Make sure owner peer is running.");
            p2pService.stop();
            return;
        }
        
        // Search files
        System.out.println("\n[Requester] Searching for files...");
        searchAndPreview(p2pService);
        
        // Keep running
        System.out.println("\n[Requester] Press Ctrl+C to stop");
        Thread.currentThread().join();
    }
    
    /**
     * Search và request preview
     */
    private static void searchAndPreview(P2PService p2pService) throws Exception {
        final boolean[] searchCompleted = {false};
        
        // Search listener
        p2pService.addListener(new P2PService.P2PServiceListener() {
            @Override
            public void onSearchResult(SearchResponse response) {
                System.out.println("\n[Requester] Search result from: " + 
                    response.getSourcePeer().getDisplayName());
                
                for (FileInfo fileInfo : response.getFoundFiles()) {
                    System.out.println("  - File: " + fileInfo.getFileName() + 
                                     " (" + fileInfo.getFormattedSize() + ")");
                    
                    // Request preview
                    requestPreviewForFile(p2pService, response.getSourcePeer(), fileInfo);
                }
            }
            
            @Override
            public void onSearchComplete() {
                System.out.println("\n[Requester] Search completed");
                searchCompleted[0] = true;
            }
            
            @Override
            public void onPeerDiscovered(PeerInfo peer) {}
            @Override
            public void onPeerLost(PeerInfo peer) {}
            @Override
            public void onTransferProgress(String fileName, long bytesTransferred, long totalBytes) {}
            @Override
            public void onTransferComplete(String fileName, File file) {}
            @Override
            public void onTransferError(String fileName, Exception e) {}
            @Override
            public void onServiceStarted() {}
            @Override
            public void onServiceStopped() {}
        });
        
        // Tìm tất cả files
        p2pService.searchFile("*");
        
        // Đợi search complete
        while (!searchCompleted[0]) {
            Thread.sleep(100);
        }
    }
    
    /**
     * Request preview cho file
     */
    private static void requestPreviewForFile(P2PService p2pService, PeerInfo peer, FileInfo fileInfo) {
        try {
            System.out.println("\n[Requester] === Requesting preview for: " + fileInfo.getFileName() + " ===");
            
            // Giả sử fileHash được set (trong thực tế cần update FileSearchService)
            String fileHash = fileInfo.getChecksum();  // Temporary: dùng checksum làm hash
            
            if (fileHash == null) {
                System.out.println("[Requester] ⚠️ File hash not available, skipping preview");
                return;
            }
            
            // Request manifest
            System.out.println("[Requester] 1. Requesting manifest...");
            PreviewManifest manifest = p2pService.requestPreviewManifest(peer, fileHash);
            
            if (manifest == null) {
                System.out.println("[Requester] ❌ Failed to get manifest");
                return;
            }
            
            System.out.println("[Requester] ✓ Manifest received");
            System.out.println("    - Available types: " + manifest.getAvailableTypes());
            System.out.println("    - Signature: " + (manifest.getSignature() != null ? "Verified ✅" : "None"));
            
            // Request preview content tùy theo type
            if (manifest.hasPreviewType(PreviewManifest.PreviewType.THUMBNAIL)) {
                requestThumbnail(p2pService, peer, fileHash, manifest);
            }
            
            if (manifest.hasPreviewType(PreviewManifest.PreviewType.TEXT_SNIPPET)) {
                requestTextSnippet(p2pService, peer, fileHash, manifest);
            }
            
            if (manifest.hasPreviewType(PreviewManifest.PreviewType.ARCHIVE_LISTING)) {
                requestArchiveListing(p2pService, peer, fileHash, manifest);
            }
            
        } catch (Exception e) {
            System.err.println("[Requester] ❌ Error requesting preview: " + e.getMessage());
        }
    }
    
    /**
     * Request thumbnail
     */
    private static void requestThumbnail(P2PService p2pService, PeerInfo peer, 
                                        String fileHash, PreviewManifest manifest) {
        System.out.println("\n[Requester] 2. Requesting THUMBNAIL...");
        
        PreviewContent content = p2pService.requestPreviewContent(
            peer, fileHash, PreviewManifest.PreviewType.THUMBNAIL
        );
        
        if (content != null) {
            System.out.println("[Requester] ✓ Thumbnail received: " + content.getFormattedSize());
            System.out.println("    - Format: " + content.getFormat());
            System.out.println("    - Dimensions: " + content.getWidth() + "x" + content.getHeight());
            
            // Save thumbnail to file (demo)
            try {
                ByteArrayInputStream bais = new ByteArrayInputStream(content.getData());
                BufferedImage image = ImageIO.read(bais);
                
                File outputFile = new File("preview_thumbnail_" + System.currentTimeMillis() + ".jpg");
                ImageIO.write(image, "jpg", outputFile);
                
                System.out.println("    - Saved to: " + outputFile.getAbsolutePath());
            } catch (Exception e) {
                System.err.println("    ⚠️ Failed to save thumbnail: " + e.getMessage());
            }
        }
    }
    
    /**
     * Request text snippet
     */
    private static void requestTextSnippet(P2PService p2pService, PeerInfo peer, 
                                          String fileHash, PreviewManifest manifest) {
        System.out.println("\n[Requester] 2. Requesting TEXT_SNIPPET...");
        
        PreviewContent content = p2pService.requestPreviewContent(
            peer, fileHash, PreviewManifest.PreviewType.TEXT_SNIPPET
        );
        
        if (content != null) {
            System.out.println("[Requester] ✓ Text snippet received: " + content.getFormattedSize());
            
            String snippet = new String(content.getData(), java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("    --- Snippet Preview ---");
            System.out.println(snippet);
            System.out.println("    --- End Snippet ---");
        }
    }
    
    /**
     * Request archive listing
     */
    private static void requestArchiveListing(P2PService p2pService, PeerInfo peer, 
                                             String fileHash, PreviewManifest manifest) {
        System.out.println("\n[Requester] 2. Requesting ARCHIVE_LISTING...");
        
        PreviewContent content = p2pService.requestPreviewContent(
            peer, fileHash, PreviewManifest.PreviewType.ARCHIVE_LISTING
        );
        
        if (content != null) {
            System.out.println("[Requester] ✓ Archive listing received: " + content.getFormattedSize());
            
            String listing = new String(content.getData(), java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("    --- Archive Contents ---");
            System.out.println(listing);
            System.out.println("    --- End Listing ---");
        }
    }
}
