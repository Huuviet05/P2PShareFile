# RELAY - Truyền File Qua Internet

## Giới Thiệu Nhanh

**Relay** là tính năng cho phép truyền file giữa các peer trên Internet (không chỉ LAN).

### Cơ Chế

-  ✅ **Ưu tiên P2P**: Thử kết nối P2P LAN trước (5 giây)
-  ✅ **Fallback Relay**: Nếu P2P thất bại → upload lên relay server → recipient download
-  ✅ **Hybrid**: Không làm ảnh hưởng kết nối LAN hiện tại

### Đặc Điểm

-  📦 Chunked upload/download (1MB chunks)
-  🔄 Resume support (tiếp tục nếu bị ngắt)
-  🔐 Client-side encryption (AES-GCM-256)
-  ✓ Hash verification (SHA-256)
-  📊 Progress tracking real-time
-  🔁 Auto retry (max 3 lần)

---

## Files Đã Tạo

### Models (`model/`)

-  `RelayUploadRequest.java` - Yêu cầu upload
-  `RelayFileInfo.java` - Thông tin file đã upload
-  `RelayTransferProgress.java` - Theo dõi tiến độ

### Network (`network/`)

-  `RelayConfig.java` - Cấu hình relay server
-  `RelayClient.java` - Client upload/download

### Documentation

-  `RELAY_GUIDE.md` - Hướng dẫn chi tiết (tiếng Việt)
-  `RELAY_CHECKLIST.md` - Checklist triển khai
-  `RELAY_README.md` - File này

---

## Quick Start

### 1. Khởi Tạo

```java
RelayConfig config = RelayConfig.forDevelopment();
RelayClient relayClient = new RelayClient(config);
```

### 2. Upload File

```java
RelayUploadRequest request = new RelayUploadRequest(
    peerId, peerName, file.getName(), file.length(), fileHash
);

relayClient.uploadFile(file, request, new RelayClient.RelayTransferListener() {
    @Override
    public void onProgress(RelayTransferProgress progress) {
        System.out.println("Upload: " + progress.getPercentage() + "%");
    }

    @Override
    public void onComplete(RelayFileInfo fileInfo) {
        // Gửi fileInfo cho recipient qua signaling
        sendToRecipient(fileInfo);
    }

    @Override
    public void onError(Exception e) {
        System.err.println("Error: " + e.getMessage());
    }
});
```

### 3. Download File

```java
relayClient.downloadFile(fileInfo, destinationFile, listener);
```

---

## Cấu Hình Quan Trọng

```java
config.setPreferP2P(true);           // Ưu tiên P2P (default: true)
config.setP2pTimeoutMs(5000);        // Timeout P2P: 5s
config.setForceRelay(false);         // Bắt buộc relay (default: false)
config.setChunkSize(1024 * 1024);    // Chunk: 1MB
config.setEnableEncryption(true);    // Mã hóa (default: true)
config.setMaxRetries(3);             // Retry max: 3
```

---

## Tiếp Theo

### Phase 2: Server & Integration

-  [ ] Tạo relay server (Node.js hoặc Spring Boot)
-  [ ] Tích hợp vào `FileTransferService`
-  [ ] Thêm signaling để trao đổi `RelayFileInfo`
-  [ ] Update UI để hiển thị mode (P2P/Relay)

### Phase 3: Testing

-  [ ] Test upload/download
-  [ ] Test resume khi bị ngắt
-  [ ] Test fallback P2P → Relay
-  [ ] Test với 2 peers trên mạng khác nhau

---

## Tài Liệu Đầy Đủ

📖 Đọc **[RELAY_GUIDE.md](RELAY_GUIDE.md)** để biết chi tiết:

-  Kiến trúc và flow
-  Cách sử dụng từng class
-  Logging và debugging
-  Bảo mật và encryption
-  FAQ và best practices

📋 Xem **[RELAY_CHECKLIST.md](RELAY_CHECKLIST.md)** để theo dõi tiến độ triển khai.

---

## Liên Hệ

Có câu hỏi? Tạo issue hoặc liên hệ team.

**P2PShareFile Team** - © 2025
