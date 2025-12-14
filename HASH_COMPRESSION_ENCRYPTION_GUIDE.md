# Hướng dẫn: Hash, Compression & Encryption trong P2P ShareFile

## 📋 Tổng quan

Ứng dụng P2P ShareFile đã tích hợp đầy đủ các tính năng bảo mật và tối ưu hóa:

### 🔐 Security Layers (3 lớp bảo mật)

1. **TLS/SSL Transport**

   -  Mã hóa toàn bộ kênh truyền dữ liệu
   -  Certificate-based authentication
   -  Port: Tự động chọn

2. **AES-256 File Encryption**

   -  Mã hóa nội dung file trước khi gửi
   -  Giải mã sau khi nhận
   -  Defense in depth (lớp bảo vệ thứ 2)

3. **ECDSA Digital Signatures**
   -  Ký các thông điệp control (JOIN, HEARTBEAT, PIN)
   -  Verify danh tính peer
   -  Chống giả mạo

### 📦 Compression (Nén dữ liệu)

-  **Thuật toán**: GZIP
-  **Auto-detect**: Tự động nén các file text, code, archives
-  **File types được nén**:
   -  Text: `.txt`, `.log`, `.csv`, `.json`, `.xml`
   -  Code: `.java`, `.js`, `.py`, `.cpp`, `.c`, `.h`
   -  Config: `.properties`, `.yaml`, `.yml`, `.conf`
   -  Web: `.html`, `.css`, `.svg`, `.md`
   -  Archives: `.tar`

### 🔑 File Hashing

-  **SHA-256**: Unique identifier cho file
   -  Sử dụng cho preview manifest
   -  Verify file integrity
   -  Detect duplicates
-  **MD5**: Checksum cho backward compatibility
   -  Legacy integrity check

## 🔄 Workflow: Share File

Khi bạn share một file, hệ thống tự động:

```
1. Đọc file từ disk
2. Tính SHA-256 hash  ─────► Unique identifier
3. Tính MD5 checksum  ─────► Integrity check
4. Tạo FileInfo object ────► Metadata
5. Tạo Preview Manifest ───► UltraView feature
6. Add to shared list ─────► Ready to transfer
```

**Console output example:**

```
🔐 Đang tính hash cho: document.pdf...
  ✓ SHA-256: a3f5e8c9d2b1...
  ✓ MD5: 7c4b2a9e1f3d...
  ✓ Đã tạo preview manifest
✅ Đã thêm file chia sẻ: document.pdf
```

## 📤 Workflow: Upload File (Peer A → Peer B)

```
┌─────────────┐                           ┌─────────────┐
│   Peer A    │                           │   Peer B    │
│  (Sender)   │                           │ (Receiver)  │
└──────┬──────┘                           └──────┬──────┘
       │                                         │
       │  1. TLS Handshake ─────────────────────►│
       │  (Establish secure channel)             │
       │                                         │
       │  2. Send file request                   │
       │     (file path)      ──────────────────►│
       │                                         │
       │◄─────────────────────  3. ACK           │
       │                                         │
       │  4. Read file from disk                 │
       │     (original data)                     │
       │          ▼                               │
       │  5. Compress (GZIP)                     │
       │     [if text/archive]                   │
       │          ▼                               │
       │  6. Encrypt (AES-256)                   │
       │     (encrypted blob)                    │
       │          ▼                               │
       │  7. Send encrypted data  ──────────────►│
       │     over TLS channel                    │
       │                                         │ 8. Receive encrypted data
       │                                         │          ▼
       │                                         │ 9. Decrypt (AES-256)
       │                                         │          ▼
       │                                         │ 10. Decompress (if needed)
       │                                         │          ▼
       │                                         │ 11. Save to disk
       │                                         │          ▼
       │◄──────────────────────  12. Success     │
       │                                         │
```

**Code trong FileTransferService:**

```java
// === UPLOAD (Peer A) ===
byte[] fileData = Files.readAllBytes(file.toPath());

// Nén (nếu cần)
boolean compressed = FileCompression.shouldCompress(file.getName());
if (compressed) {
    fileData = FileCompression.compress(fileData);
    System.out.println("✓ Đã nén: " + fileData.length + " bytes");
}

// Mã hóa
byte[] encryptedData = AESEncryption.encrypt(fileData, encryptionKey);
System.out.println("✓ Đã mã hóa: " + encryptedData.length + " bytes");

// Gửi qua TLS
dos.write(encryptedData);
```

```java
// === DOWNLOAD (Peer B) ===
// Nhận qua TLS
byte[] encryptedData = receiveData();

// Giải mã
byte[] decryptedData = AESEncryption.decrypt(encryptedData, encryptionKey);
System.out.println("✓ Đã giải mã");

// Giải nén (nếu đã nén)
byte[] finalData = compressed ?
    FileCompression.decompress(decryptedData) : decryptedData;

if (compressed) {
    System.out.println("✓ Đã giải nén");
}

// Lưu file
Files.write(savedFile.toPath(), finalData);
```

## 🎯 Use Cases

### 1. Share File với Hash

```java
// User clicks "Add File" button
File file = fileChooser.showOpenDialog(...);

// P2PService tự động tính hash
p2pService.addSharedFile(file);

// Console output:
// 🔐 Đang tính hash cho: report.pdf...
//   ✓ SHA-256: b2c4f8e1a3d5...
//   ✓ MD5: 9f7c2e4a1b6d...
//   ✓ Đã tạo preview manifest
// ✅ Đã thêm file chia sẻ: report.pdf
```

### 2. Preview File (UltraView)

```java
// User clicks "Preview" button
// Backend sử dụng fileHash để tìm manifest
String fileHash = fileInfo.getFileHash();
PreviewManifest manifest = previewCache.getManifest(fileHash);

if (manifest != null) {
    // Hiển thị preview: thumbnail, text snippet, etc.
    showPreviewDialog(manifest);
} else {
    showWarning("File không có preview");
}
```

### 3. Download với Compression & Encryption

```java
// User clicks "Download" button
p2pService.downloadFile(peer, fileInfo, saveDirectory);

// Progress logs:
// 📥 Đang download file: data.json từ Peer_ABC
//   ⏳ Nhận file: data.json (1847 bytes encrypted)
//   ✓ Đã nhận: 1847 bytes
//   ✓ Đã giải mã
//   ✓ Đã giải nén  ← (file JSON được nén)
//   ✅ Download hoàn tất: C:\Downloads\data.json
```

## 📊 Performance Benefits

### Compression Ratio (ví dụ)

| File Type        | Original Size | Compressed | Ratio     |
| ---------------- | ------------- | ---------- | --------- |
| `.txt` (log)     | 100 KB        | 15 KB      | 85% giảm  |
| `.json` (config) | 50 KB         | 8 KB       | 84% giảm  |
| `.java` (source) | 30 KB         | 7 KB       | 77% giảm  |
| `.jpg` (image)   | 500 KB        | 500 KB     | 0% (skip) |
| `.mp4` (video)   | 10 MB         | 10 MB      | 0% (skip) |

### Security Overhead

-  **AES-256 encryption**: ~0.1% overhead (CPU-bound)
-  **TLS handshake**: ~100-200ms initial latency
-  **ECDSA signature**: ~1-2ms per message

## 🔍 Hash Verification

Sau khi download, bạn có thể verify hash:

```java
// Tính hash của file đã download
String downloadedHash = FileHashUtil.calculateSHA256(downloadedFile);

// So sánh với hash gốc
if (downloadedHash.equals(originalFileInfo.getFileHash())) {
    System.out.println("✅ File integrity verified!");
} else {
    System.out.println("❌ File corrupted or tampered!");
}
```

## 🛡️ Security Best Practices

### ✅ Đã implement

1. **TLS 1.2/1.3** cho tất cả network channels
2. **AES-256-GCM** cho file encryption
3. **ECDSA P-256** cho digital signatures
4. **SHA-256** cho file hashing
5. **Ephemeral keys** cho mỗi TLS session

### ⚠️ Lưu ý

-  **AES Key**: Hiện tại dùng shared key cố định `DEFAULT_KEY`

   -  Production: Nên dùng key exchange (ECDH)
   -  Hoặc: User nhập password → derive key (PBKDF2)

-  **Certificate**: Self-signed certificates
   -  Production: Cần CA-signed certificates
   -  Hoặc: Implement trust-on-first-use (TOFU)

## 🧪 Testing

### Test Hash Calculation

```bash
# Chạy app và add file
# Kiểm tra console output:
🔐 Đang tính hash cho: test.txt...
  ✓ SHA-256: e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
  ✓ MD5: d41d8cd98f00b204e9800998ecf8427e
```

### Test Compression

```bash
# Tạo file text lớn
echo "Test data repeated many times..." > test.txt

# Add vào shared files
# Upload/Download
# Kiểm tra logs:
  ✓ Đã nén: 523 bytes (từ 5230 bytes)
  ✓ Đã giải nén
```

### Test Encryption

```bash
# Download file bất kỳ
# Kiểm tra logs:
  ✓ Đã mã hóa: 1847 bytes
  ✓ Đã giải mã
  ✅ Download hoàn tất
```

## 📝 Summary

**Tích hợp hoàn chỉnh:**

-  ✅ SHA-256 + MD5 hash tự động khi share file
-  ✅ GZIP compression cho text/code files
-  ✅ AES-256 encryption cho tất cả transfers
-  ✅ TLS transport layer
-  ✅ ECDSA signatures cho control messages
-  ✅ Preview manifest sử dụng fileHash
-  ✅ UI hiển thị hash, compression, encryption status

**User không cần làm gì** - tất cả tự động:

1. Add file → Tính hash
2. Download file → Decrypt + Decompress
3. Preview file → Dùng hash để load manifest

**Security guarantee:**

-  Confidentiality: TLS + AES
-  Integrity: SHA-256 + MD5
-  Authenticity: ECDSA signatures
-  Efficiency: GZIP compression
