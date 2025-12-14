package org.example.p2psharefile.service;

import org.example.p2psharefile.model.PreviewContent;
import org.example.p2psharefile.model.PreviewManifest;
import org.example.p2psharefile.security.SecurityManager;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PreviewCacheService - Quản lý cache preview content
 * 
 * Cache preview để tránh phải sinh lại mỗi lần request
 */
public class PreviewCacheService {
    
    // FileHash -> PreviewManifest
    private final Map<String, PreviewManifest> manifestCache = new ConcurrentHashMap<>();
    
    // FileHash_PreviewType -> PreviewContent
    private final Map<String, PreviewContent> contentCache = new ConcurrentHashMap<>();
    
    // FilePath -> File (để truy cập nhanh)
    private final Map<String, File> fileCache = new ConcurrentHashMap<>();
    
    private final String ownerPeerId;
    private final SecurityManager securityManager;  // Để ký manifest
    
    public PreviewCacheService(String ownerPeerId, SecurityManager securityManager) {
        this.ownerPeerId = ownerPeerId;
        this.securityManager = securityManager;
    }
    
    /**
     * Lấy hoặc tạo manifest cho file
     */
    public PreviewManifest getOrCreateManifest(File file) {
        return getOrCreateManifest(file, false);
    }
    
    /**
     * Lấy hoặc tạo manifest cho file với tùy chọn force regenerate
     */
    public PreviewManifest getOrCreateManifest(File file, boolean forceRegenerate) {
        try {
            // Tính hash trước để kiểm tra cache
            String fileHash = PreviewGenerator.calculateFileHash(file);
            
            // Kiểm tra cache (nếu không force regenerate)
            if (!forceRegenerate) {
                PreviewManifest cached = manifestCache.get(fileHash);
                if (cached != null && cached.getLastModified() == file.lastModified()) {
                    return cached;
                }
            }
            
            // Xóa cache cũ nếu có
            if (forceRegenerate) {
                removeCache(fileHash);
            }
            
            // Sinh manifest mới
            PreviewManifest manifest = PreviewGenerator.generateManifest(file, ownerPeerId);
            
            // Ký manifest để đảm bảo tính xác thực
            try {
                String dataToSign = manifest.getDataToSign();
                String signature = securityManager.signMessage(dataToSign);
                manifest.setSignature(signature);
            } catch (Exception e) {
                System.err.println("⚠️ Không thể ký manifest: " + e.getMessage());
            }
            
            // Cache manifest và file
            manifestCache.put(fileHash, manifest);
            fileCache.put(fileHash, file);
            
            System.out.println("✓ Đã tạo manifest cho: " + file.getName() + 
                             " (hash: " + fileHash.substring(0, 16) + "...)");
            
            return manifest;
            
        } catch (Exception e) {
            System.err.println("Lỗi khi tạo manifest: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Lấy manifest từ cache
     */
    public PreviewManifest getManifest(String fileHash) {
        return manifestCache.get(fileHash);
    }
    
    /**
     * Lấy hoặc tạo preview content
     */
    public PreviewContent getOrCreateContent(String fileHash, PreviewManifest.PreviewType type) {
        try {
            String cacheKey = fileHash + "_" + type;
            
            // Kiểm tra cache
            PreviewContent cached = contentCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            
            // Lấy manifest và file
            PreviewManifest manifest = manifestCache.get(fileHash);
            File file = fileCache.get(fileHash);
            
            if (manifest == null || file == null) {
                System.err.println("Không tìm thấy manifest hoặc file cho hash: " + fileHash);
                return null;
            }
            
            // Sinh content
            PreviewContent content = PreviewGenerator.generatePreviewContent(file, manifest, type);
            
            // Cache content
            contentCache.put(cacheKey, content);
            
            System.out.println("✓ Đã tạo preview content: " + type + 
                             " (" + content.getFormattedSize() + ")");
            
            return content;
            
        } catch (Exception e) {
            System.err.println("Lỗi khi tạo preview content: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Xóa cache cho file
     */
    public void removeCache(String fileHash) {
        manifestCache.remove(fileHash);
        fileCache.remove(fileHash);
        
        // Xóa tất cả content cache cho file này
        contentCache.keySet().removeIf(key -> key.startsWith(fileHash + "_"));
    }
    
    /**
     * Xóa toàn bộ cache
     */
    public void clearAll() {
        manifestCache.clear();
        contentCache.clear();
        fileCache.clear();
    }
    
    /**
     * Lấy số lượng manifest đã cache
     */
    public int getManifestCacheSize() {
        return manifestCache.size();
    }
    
    /**
     * Lấy số lượng content đã cache
     */
    public int getContentCacheSize() {
        return contentCache.size();
    }
}
