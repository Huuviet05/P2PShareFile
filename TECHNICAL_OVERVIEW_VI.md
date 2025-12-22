# 📚 TỔNG QUAN KỸ THUẬT - P2P SHARE FILE

## 📋 Mục Lục

1. [Giới thiệu dự án](#1-giới-thiệu-dự-án)
2. [Kiến trúc tổng quan](#2-kiến-trúc-tổng-quan)
3. [Chế độ P2P (LAN)](#3-chế-độ-p2p-lan)
4. [Chế độ Relay (Internet)](#4-chế-độ-relay-internet)
5. [Bảo mật](#5-bảo-mật)
6. [Các thành phần chính](#6-các-thành-phần-chính)
7. [Luồng hoạt động chi tiết](#7-luồng-hoạt-động-chi-tiết)

---

## 1. Giới thiệu dự án

### 1.1 Mục đích

**P2P Share File** là ứng dụng chia sẻ file kết hợp hai chế độ:

-  **P2P (Peer-to-Peer)**: Chia sẻ trực tiếp trong mạng LAN
-  **Relay**: Chia sẻ qua Internet thông qua server trung gian

### 1.2 Tính năng chính

| Tính năng          | Mô tả                             |
| ------------------ | --------------------------------- |
| 🔒 Mã hóa đầu cuối | TLS 1.3 + AES-256-GCM             |
| 🔐 Xác thực        | ECDSA digital signatures          |
| 📤 Quick Share     | Mã PIN 6 số (giống Send Anywhere) |
| 🔍 Tìm kiếm        | Flooding algorithm trong mạng P2P |
| 👁️ Preview         | Xem trước ảnh, PDF, archive       |
| 📦 Nén file        | GZIP compression                  |

### 1.3 Công nghệ sử dụng

-  **Ngôn ngữ**: Java 21+
-  **UI Framework**: JavaFX + FXML
-  **Security**: BouncyCastle, Java SSL/TLS
-  **HTTP Server**: com.sun.net.httpserver (cho Relay)
-  **Build Tool**: Maven

---

## 2. Kiến trúc tổng quan

### 2.1 Sơ đồ kiến trúc

```
┌─────────────────────────────────────────────────────────────────────┐
│                        P2P SHARE FILE APPLICATION                    │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐              │
│  │   JavaFX    │    │    FXML     │    │     CSS     │              │
│  │     UI      │◄───│   Layout    │◄───│   Styles    │              │
│  └──────┬──────┘    └─────────────┘    └─────────────┘              │
│         │                                                            │
│         ▼                                                            │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                    MAIN CONTROLLER                            │   │
│  │  • Xử lý sự kiện UI                                          │   │
│  │  • Điều phối các service                                     │   │
│  └──────────────────────────┬───────────────────────────────────┘   │
│                             │                                        │
│                             ▼                                        │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                      P2P SERVICE (Facade)                     │   │
│  │  • Khởi tạo và quản lý tất cả services                       │   │
│  │  • Cung cấp API thống nhất cho UI                            │   │
│  └──────────────────────────┬───────────────────────────────────┘   │
│                             │                                        │
│         ┌───────────────────┼───────────────────┐                   │
│         ▼                   ▼                   ▼                   │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐              │
│  │   Network   │    │   Service   │    │  Security   │              │
│  │   Layer     │    │   Layer     │    │   Layer     │              │
│  └─────────────┘    └─────────────┘    └─────────────┘              │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 Package Structure

```
org.example.p2psharefile/
├── MainApplication.java      # Entry point
├── controller/
│   └── MainController.java   # UI Controller
├── model/                    # Data models
│   ├── FileInfo.java
│   ├── PeerInfo.java
│   ├── ShareSession.java
│   └── ...
├── network/                  # Network services
│   ├── PeerDiscovery.java    # Tìm peer trong LAN
│   ├── FileSearchService.java # Tìm kiếm file
│   ├── FileTransferService.java # Truyền file
│   ├── RelayClient.java      # HTTP client cho relay
│   └── RelayConfig.java      # Cấu hình relay
├── relay/                    # Relay server
│   ├── RelayServer.java      # HTTP server
│   └── PeerRegistry.java     # Registry peers
├── security/                 # Security modules
│   ├── SecurityManager.java  # Quản lý TLS + ký số
│   ├── AESEncryption.java    # Mã hóa AES-256
│   └── FileHashUtil.java     # Hash SHA-256
├── service/                  # Business services
│   ├── P2PService.java       # Facade service
│   ├── PINCodeService.java   # Quản lý mã PIN
│   ├── PreviewService.java   # Preview files
│   └── PreviewGenerator.java # Tạo preview
└── compression/
    └── FileCompression.java  # GZIP compression
```

---

## 3. Chế độ P2P (LAN)

### 3.1 Tổng quan

Chế độ P2P cho phép các máy tính trong **cùng mạng LAN** chia sẻ file trực tiếp với nhau mà không cần server trung gian.

### 3.2 Peer Discovery (Khám phá peer)

#### 3.2.1 Cơ chế hoạt động

```
┌──────────────────────────────────────────────────────────────────┐
│                    PEER DISCOVERY PROCESS                         │
├──────────────────────────────────────────────────────────────────┤
│                                                                   │
│  PEER A (192.168.1.100)              PEER B (192.168.1.105)      │
│  ┌─────────────────────┐             ┌─────────────────────┐     │
│  │  SSLServerSocket    │             │  SSLServerSocket    │     │
│  │  Port: 8888         │             │  Port: 8888         │     │
│  └─────────────────────┘             └─────────────────────┘     │
│           ▲                                   ▲                   │
│           │                                   │                   │
│  Step 1: Peer A quét subnet 192.168.1.0/24                       │
│           │                                   │                   │
│           └──────── SSLSocket ───────────────►│                   │
│                    connect()                                      │
│                                                                   │
│  Step 2: TLS Handshake                                           │
│           ◄───────── Certificate ─────────────                   │
│           ─────────── Certificate ───────────►                   │
│                                                                   │
│  Step 3: Gửi JOIN message (có chữ ký ECDSA)                      │
│           ─────────── SignedMessage ─────────►                   │
│           {                                                       │
│             "type": "JOIN",                                       │
│             "peerInfo": {...},                                   │
│             "signature": "ECDSA signature"                       │
│           }                                                       │
│                                                                   │
│  Step 4: Peer B verify signature và lưu peer                     │
│           ◄───────── SignedMessage ───────────                   │
│           (Response với PeerInfo của B)                          │
│                                                                   │
└──────────────────────────────────────────────────────────────────┘
```

#### 3.2.2 Code Implementation

**File**: `PeerDiscovery.java`

```java
// Constants
private static final int PEER_PORT = 8888;        // Port lắng nghe
private static final int SCAN_TIMEOUT = 200;      // Timeout quét (ms)
private static final int HEARTBEAT_INTERVAL = 5000; // Gửi heartbeat mỗi 5s
private static final int PEER_TIMEOUT = 15000;    // Peer timeout (15s)

// Quét subnet để tìm peer
private void scanSubnet() {
    String localIP = getLocalIPAddress();  // VD: 192.168.1.100
    String baseIP = localIP.substring(0, localIP.lastIndexOf(".") + 1);
    // Quét từ 192.168.1.1 đến 192.168.1.254
    for (int i = 1; i <= 254; i++) {
        String targetIP = baseIP + i;
        if (!targetIP.equals(localIP)) {
            tryConnect(targetIP);  // Thử kết nối SSL
        }
    }
}
```

#### 3.2.3 Heartbeat Mechanism

-  Mỗi peer gửi **HEARTBEAT** mỗi 5 giây
-  Nếu không nhận được heartbeat trong 15 giây → peer bị đánh dấu **offline**
-  Heartbeat message cũng được ký bằng ECDSA để chống fake peer

### 3.3 File Search (Flooding Algorithm)

#### 3.3.1 Cơ chế Flooding

```
┌─────────────────────────────────────────────────────────────────┐
│                 FILE SEARCH - FLOODING ALGORITHM                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│           Peer A                Peer B                Peer C     │
│           (Tìm file)            (Có file)             (Không có) │
│              │                     │                     │       │
│   Step 1:   │                     │                     │       │
│   Tạo SearchRequest với requestId duy nhất             │       │
│              │                     │                     │       │
│   Step 2:   │─── SearchRequest ──►│                     │       │
│   Forward   │─────────────────────┼───SearchRequest ───►│       │
│              │                     │                     │       │
│   Step 3:   │                     │                     │       │
│   Mỗi peer kiểm tra có file match không                │       │
│              │                     │                     │       │
│   Step 4:   │◄── SearchResponse ──│                     │       │
│   Peer B    │   (có file match)   │                     │       │
│   trả về    │                     │                     │       │
│              │                     │                     │       │
│   Step 5:   │◄─────────────────────────── No result ────│       │
│   Peer C không có file → không trả về                   │       │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### 3.3.2 Chống lặp vô hạn

-  Mỗi request có **requestId** duy nhất
-  Peer lưu các requestId đã xử lý trong `processedRequests` set
-  Khi nhận request đã có trong set → **bỏ qua**

**File**: `FileSearchService.java`

```java
private final Set<String> processedRequests = ConcurrentHashMap.newKeySet();

// Khi nhận search request
if (processedRequests.contains(request.getRequestId())) {
    return; // Đã xử lý rồi, bỏ qua
}
processedRequests.add(request.getRequestId());
```

### 3.4 File Transfer (P2P)

#### 3.4.1 Quy trình truyền file

```
┌─────────────────────────────────────────────────────────────────┐
│                   P2P FILE TRANSFER PROCESS                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   Peer A (Receiver)                    Peer B (Sender)           │
│        │                                    │                    │
│ Step 1:│                                    │                    │
│        │─────── Download Request ──────────►│                    │
│        │        (fileId, fileName)          │                    │
│        │                                    │                    │
│ Step 2:│                                    │ Đọc file           │
│        │                                    │ ↓                  │
│        │                                    │ Nén GZIP           │
│        │                                    │ ↓                  │
│        │                                    │ Mã hóa AES-256     │
│        │                                    │                    │
│ Step 3:│◄───── FileSize + Hash ─────────────│                    │
│        │       (để verify sau khi nhận)     │                    │
│        │                                    │                    │
│ Step 4:│◄───── Encrypted Chunks ────────────│                    │
│        │       (8KB mỗi chunk, qua TLS)     │                    │
│        │                                    │                    │
│ Step 5:│                                    │                    │
│ Giải mã│                                    │                    │
│    ↓   │                                    │                    │
│ Giải nén                                    │                    │
│    ↓   │                                    │                    │
│ Verify │                                    │                    │
│ hash   │                                    │                    │
│        │                                    │                    │
└─────────────────────────────────────────────────────────────────┘
```

#### 3.4.2 Security Layers

| Layer       | Mục đích                                               |
| ----------- | ------------------------------------------------------ |
| **TLS 1.3** | Bảo vệ transport channel (confidentiality + integrity) |
| **AES-256** | Mã hóa file content (defense in depth)                 |
| **SHA-256** | Verify file integrity sau khi nhận                     |
| **GZIP**    | Nén file trước khi truyền                              |

---

## 4. Chế độ Relay (Internet)

### 4.1 Tổng quan

Khi các peer **không cùng mạng LAN**, cần sử dụng **Relay Server** làm trung gian.

### 4.2 Kiến trúc Relay

```
┌─────────────────────────────────────────────────────────────────┐
│                      RELAY ARCHITECTURE                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│    Sender                    Relay Server                Receiver│
│  (Home WiFi)                 (Cloud/Render)          (Office LAN)│
│       │                           │                        │     │
│       │                           │                        │     │
│       │    ┌─────────────────────┐│                        │     │
│       │    │   HTTPS Endpoints   ││                        │     │
│       │    ├─────────────────────┤│                        │     │
│       │    │ POST /upload        ││                        │     │
│       │    │ GET  /download/:id  ││                        │     │
│       │    │ POST /pin/create    ││                        │     │
│       │    │ GET  /pin/lookup    ││                        │     │
│       │    │ POST /peer/register ││                        │     │
│       │    │ GET  /files/search  ││                        │     │
│       │    └─────────────────────┘│                        │     │
│       │                           │                        │     │
│       │──── 1. Upload file ──────►│                        │     │
│       │                           │                        │     │
│       │◄─── 2. uploadId + PIN ────│                        │     │
│       │                           │                        │     │
│       │         User shares PIN qua chat/SMS               │     │
│       │                           │                        │     │
│       │                           │◄── 3. Lookup PIN ──────│     │
│       │                           │                        │     │
│       │                           │─── 4. Download URL ───►│     │
│       │                           │                        │     │
│       │                           │◄── 5. Download file ───│     │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 4.3 API Endpoints

**File**: `RelayServer.java`

| Endpoint                  | Method | Mô tả                      |
| ------------------------- | ------ | -------------------------- |
| `/api/relay/upload`       | POST   | Upload file theo chunks    |
| `/api/relay/download/:id` | GET    | Download file đã upload    |
| `/api/relay/status/:id`   | GET    | Kiểm tra trạng thái upload |
| `/api/pin/create`         | POST   | Tạo mã PIN cho file        |
| `/api/pin/lookup/:pin`    | GET    | Tìm file theo mã PIN       |
| `/api/files/search`       | GET    | Tìm kiếm file trên relay   |
| `/api/peer/register`      | POST   | Đăng ký peer với relay     |
| `/api/peer/heartbeat`     | POST   | Gửi heartbeat              |

### 4.4 PIN Code System (Quick Share)

#### 4.4.1 Quy trình chia sẻ bằng PIN

```
┌─────────────────────────────────────────────────────────────────┐
│                     PIN CODE SHARING FLOW                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  SENDER                      RELAY                     RECEIVER  │
│     │                          │                          │      │
│  1. Chọn file                  │                          │      │
│     │                          │                          │      │
│  2. ────── Upload file ───────►│                          │      │
│     │      (chunked upload)    │                          │      │
│     │                          │                          │      │
│  3. ────── Create PIN ────────►│                          │      │
│     │      {                   │ ← Lưu vào pinRegistry    │      │
│     │        uploadId,         │                          │      │
│     │        fileName,         │                          │      │
│     │        fileSize          │                          │      │
│     │      }                   │                          │      │
│     │                          │                          │      │
│  4. ◄────── PIN: 123456 ───────│                          │      │
│     │                          │                          │      │
│  5. [User chia sẻ PIN 123456 qua chat/SMS/email]         │      │
│     │                          │                          │      │
│     │                          │◄─── Nhập PIN 123456 ─────│      │
│     │                          │     (lookup request)      │      │
│  6. │                          │                          │      │
│     │                          │───── File Info ─────────►│      │
│     │                          │     {                     │      │
│     │                          │       fileName,           │      │
│     │                          │       fileSize,           │      │
│     │                          │       downloadUrl         │      │
│     │                          │     }                     │      │
│  7. │                          │                          │      │
│     │                          │◄──── Download file ───────│      │
│     │                          │                          │      │
│  8. │                          │ PIN tự động hết hạn      │      │
│     │                          │ sau 10 phút              │      │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### 4.4.2 Code Implementation

**File**: `PINCodeService.java`

```java
// Tạo PIN 6 số ngẫu nhiên
private String generatePIN() {
    Random random = new Random();
    StringBuilder pin = new StringBuilder();
    for (int i = 0; i < PIN_LENGTH; i++) {
        pin.append(random.nextInt(10));  // 0-9
    }
    return pin.toString();
}

// PIN hết hạn sau 10 phút
private static final long DEFAULT_EXPIRY = 600000; // 10 phút
```

### 4.5 Chunked Upload/Download

#### 4.5.1 Lý do cần chunk

-  File lớn không thể upload 1 lần
-  Cho phép resume nếu bị ngắt kết nối
-  Hiển thị progress chính xác

#### 4.5.2 Implementation

**File**: `RelayClient.java`

```java
// Upload theo chunks
public String uploadFile(File file, String encryptionKey,
                        UploadProgressListener listener) {
    String uploadId = UUID.randomUUID().toString();

    try (FileInputStream fis = new FileInputStream(file)) {
        byte[] buffer = new byte[CHUNK_SIZE];  // 64KB chunks
        int chunkIndex = 0;
        int bytesRead;

        while ((bytesRead = fis.read(buffer)) != -1) {
            byte[] chunk = Arrays.copyOf(buffer, bytesRead);

            // Mã hóa chunk nếu có key
            if (encryptionKey != null) {
                chunk = AESEncryption.encryptBytes(chunk, key);
            }

            // Upload chunk
            uploadChunk(uploadId, chunkIndex, chunk, file.length());

            // Update progress
            listener.onProgress(totalUploaded, file.length());
            chunkIndex++;
        }
    }
    return uploadId;
}
```

---

## 5. Bảo mật

### 5.1 Tổng quan các lớp bảo mật

```
┌─────────────────────────────────────────────────────────────────┐
│                     SECURITY LAYERS                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   Layer 4: ECDSA Digital Signatures                             │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │ • Xác thực danh tính peer                               │   │
│   │ • Chống impersonation attacks                           │   │
│   │ • Ký các control messages (JOIN, HEARTBEAT, PIN)        │   │
│   └─────────────────────────────────────────────────────────┘   │
│                          │                                       │
│   Layer 3: AES-256-GCM Encryption                               │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │ • Mã hóa nội dung file                                  │   │
│   │ • Defense in depth                                       │   │
│   │ • Bảo vệ ngay cả khi TLS bị compromise                  │   │
│   └─────────────────────────────────────────────────────────┘   │
│                          │                                       │
│   Layer 2: TLS 1.3 Transport Security                           │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │ • Mã hóa channel communication                          │   │
│   │ • Bảo vệ confidentiality và integrity                   │   │
│   │ • Self-signed certificates cho peer identity            │   │
│   └─────────────────────────────────────────────────────────┘   │
│                          │                                       │
│   Layer 1: SHA-256 File Hashing                                 │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │ • Verify file integrity sau khi truyền                  │   │
│   │ • Phát hiện file corruption                             │   │
│   └─────────────────────────────────────────────────────────┘   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 5.2 SecurityManager

**File**: `SecurityManager.java`

```java
/**
 * SecurityManager - Quản lý bảo mật
 *
 * Chức năng:
 * 1. Tạo keypair RSA 2048-bit cho mỗi peer
 * 2. Tạo self-signed X.509 certificate
 * 3. Tạo SSLContext cho TLS connections
 * 4. Ký và verify messages
 */
public class SecurityManager {
    private static final String KEY_ALGORITHM = "RSA";
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final int KEY_SIZE = 2048;

    private final KeyPair keyPair;
    private final X509Certificate selfCertificate;
    private final Map<String, PublicKey> trustedPeerKeys;
}
```

### 5.3 AES Encryption

**File**: `AESEncryption.java`

| Thuộc tính | Giá trị                     |
| ---------- | --------------------------- |
| Algorithm  | AES                         |
| Mode       | CBC (Cipher Block Chaining) |
| Padding    | PKCS5Padding                |
| Key Size   | 256 bit                     |
| IV Size    | 16 bytes                    |

```java
// Mã hóa file
public static byte[] encryptBytes(byte[] data, SecretKey key) {
    byte[] iv = generateIV();  // Random 16 bytes
    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));

    byte[] encrypted = cipher.doFinal(data);

    // Prepend IV to encrypted data
    byte[] result = new byte[iv.length + encrypted.length];
    System.arraycopy(iv, 0, result, 0, iv.length);
    System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);

    return result;
}
```

### 5.4 Digital Signatures

#### 5.4.1 Quy trình ký và verify

```
┌─────────────────────────────────────────────────────────────────┐
│                  DIGITAL SIGNATURE PROCESS                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   SENDER                                          RECEIVER       │
│                                                                  │
│   Message: "JOIN request"                                        │
│       │                                                          │
│       ▼                                                          │
│   ┌───────────────┐                                              │
│   │  SHA-256 Hash │ → Hash của message                          │
│   └───────┬───────┘                                              │
│           │                                                      │
│           ▼                                                      │
│   ┌───────────────┐                                              │
│   │  RSA Sign     │ → Ký hash bằng Private Key                  │
│   │  (Private Key)│                                              │
│   └───────┬───────┘                                              │
│           │                                                      │
│           ▼                                                      │
│   SignedMessage {                                                │
│     message: "JOIN request",                                     │
│     signature: "abc123...",        ─────────────►   Nhận message │
│     publicKey: sender's key                              │       │
│   }                                                      │       │
│                                                          ▼       │
│                                          ┌───────────────────┐   │
│                                          │  RSA Verify       │   │
│                                          │  (Public Key)     │   │
│                                          └─────────┬─────────┘   │
│                                                    │             │
│                                          ┌─────────▼─────────┐   │
│                                          │  Valid? Yes/No    │   │
│                                          └───────────────────┘   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 6. Các thành phần chính

### 6.1 Model Classes

| Class            | Mô tả                                          |
| ---------------- | ---------------------------------------------- |
| `PeerInfo`       | Thông tin peer: ID, name, IP, port, public key |
| `FileInfo`       | Thông tin file: name, path, size, hash, owner  |
| `ShareSession`   | Session chia sẻ: PIN, files, expiry time       |
| `SignedMessage`  | Message có chữ ký số                           |
| `SearchRequest`  | Yêu cầu tìm kiếm                               |
| `SearchResponse` | Kết quả tìm kiếm                               |
| `RelayFileInfo`  | Thông tin file trên relay server               |

### 6.2 Network Services

| Service               | Port | Chức năng                |
| --------------------- | ---- | ------------------------ |
| `PeerDiscovery`       | 8888 | Khám phá peer, heartbeat |
| `FileSearchService`   | 8891 | Tìm kiếm file (flooding) |
| `FileTransferService` | 8889 | Truyền file P2P          |
| `PINCodeService`      | 8887 | Sync PIN giữa peers      |

### 6.3 Preview Service

**File**: `PreviewGenerator.java`

| File Type              | Preview Method             |
| ---------------------- | -------------------------- |
| Images (PNG, JPG, GIF) | JavaFX ImageView thumbnail |
| PDF                    | PDFBox render first page   |
| ZIP/Archive            | List file entries          |
| Text                   | First 500 characters       |
| Other                  | File info only             |

---

## 7. Luồng hoạt động chi tiết

### 7.1 Khởi động ứng dụng

```
1. MainApplication.start()
2. Load FXML → MainController.initialize()
3. P2PService khởi tạo:
   a. SecurityManager: tạo keypair + certificate
   b. PeerDiscovery: start SSLServerSocket (8888)
   c. FileSearchService: start (8891)
   d. FileTransferService: start (8889)
   e. PINCodeService: start (8887)
   f. RelayClient: kết nối relay server (nếu enabled)
4. Bắt đầu quét subnet tìm peers
5. UI hiển thị danh sách files và peers
```

### 7.2 Chia sẻ file P2P

```
1. User chọn file từ UI
2. Nhấn "Chia sẻ" → File được thêm vào sharedFiles map
3. (Optional) Tạo PIN → PINCodeService sinh PIN 6 số
4. PIN được broadcast đến tất cả peers trong LAN
5. Peer khác nhập PIN → Lookup trong globalSessions
6. Nếu match → Bắt đầu download từ peer source
7. File được truyền qua TLS với encryption
```

### 7.3 Chia sẻ file qua Relay

```
1. User chọn file
2. Bật "Chế độ Internet" (p2pOnlyMode = false)
3. RelayClient.uploadFile() → Upload lên relay server
4. Nhận uploadId → Tạo PIN từ relay server
5. Chia sẻ PIN cho người nhận
6. Người nhận nhập PIN → RelayClient.lookupPIN()
7. Nhận download URL → RelayClient.downloadFile()
8. File được tải về local
```

### 7.4 Tìm kiếm file

```
1. User nhập keyword vào ô tìm kiếm
2. FileSearchService.searchFile(keyword) được gọi
3. Tạo SearchRequest với:
   - requestId (unique)
   - originPeerId
   - keyword
   - hopCount
4. Gửi đến tất cả peers đã discover
5. Mỗi peer nhận request:
   a. Check processedRequests (tránh loop)
   b. Search local sharedFiles
   c. Forward đến peers khác
   d. Gửi SearchResponse về origin
6. UI hiển thị kết quả tìm kiếm
```

---

## 📝 Tóm tắt

| Aspect        | P2P Mode       | Relay Mode           |
| ------------- | -------------- | -------------------- |
| **Phạm vi**   | LAN only       | Internet             |
| **Server**    | Không cần      | Cần relay server     |
| **Discovery** | Quét subnet    | API registration     |
| **Transfer**  | Direct socket  | HTTP upload/download |
| **Security**  | TLS + AES      | HTTPS + AES          |
| **PIN sync**  | Broadcast      | HTTP API             |
| **Latency**   | Thấp           | Cao hơn              |
| **Bandwidth** | Không giới hạn | Phụ thuộc server     |

---

_Tài liệu được tạo cho dự án P2P Share File - Lập trình Mạng_
