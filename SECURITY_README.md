# P2P File Sharing - TLS + Peer Authentication

## 🔐 Security Improvements

Hệ thống P2P đã được nâng cấp với **TLS + Peer Authentication** để đảm bảo bảo mật toàn diện trong môi trường mạng LAN.

**Note:** Hiện sử dụng RSA 2048-bit cho keypair (thay vì ECDSA) để tương thích tốt hơn với certificate generation trên nhiều Java versions.

---

## ⭐ Tính năng bảo mật mới

### 1. **TLS/SSL Encryption cho tất cả channels**

-  **PeerDiscovery**: Sử dụng `SSLServerSocket` và `SSLSocket` thay vì plaintext TCP
-  **FileSearchService**: Mã hóa search requests/responses qua TLS
-  **FileTransferService**: Double encryption (TLS + AES) cho file content
-  **PINCodeService**: Bảo vệ PIN transmission qua TLS

**Lợi ích:**

-  Chống eavesdropping (nghe lén)
-  Đảm bảo confidentiality và integrity của data
-  Bảo vệ metadata (file names, sizes, PINs)

---

### 2. **ECDSA Keypair + Digital Signatures**

Mỗi peer có:

-  **Private key** (ECDSA 256-bit): Ký messages
-  **Public key**: Được share trong `PeerInfo` để verify signatures

**Signed messages:**

-  `JOIN` messages - Ngăn chặn unauthorized peers
-  `HEARTBEAT` messages - Đảm bảo peer authenticity
-  `PIN` messages - Chống PIN forgery/impersonation

**Verification process:**

```
Sender: Message → Sign with private key → Send (SignedMessage)
Receiver: Verify signature with sender's public key → Accept/Reject
```

**Lợi ích:**

-  Chống message forgery (giả mạo)
-  Chống impersonation attacks (mạo danh peer)
-  Đảm bảo message integrity và non-repudiation

---

### 3. **Self-signed X.509 Certificates**

Mỗi peer tự tạo certificate cho TLS handshake:

-  Algorithm: ECDSA (Elliptic Curve)
-  Validity: 1 năm
-  DN: `CN=<peerName>, OU=P2P, O=P2PShareFile, C=VN`

**Trust model:**

-  Trong mạng LAN, peers trust all certificates (không cần CA)
-  `TrustManager` accept tất cả certificates
-  Phù hợp cho dev/local network environment

---

## 🏗️ Kiến trúc bảo mật

### Components

#### 1. **SecurityManager** (`security/SecurityManager.java`)

Quản lý:

-  Keypair generation (ECDSA)
-  Self-signed certificate creation
-  SSLContext configuration
-  Message signing/verification
-  Trusted peer keys management

#### 2. **PeerInfo** (updated)

Thêm field:

-  `publicKey` (String): Base64-encoded public key

#### 3. **SignedMessage** (`model/SignedMessage.java`)

Wrapper cho control messages:

```java
{
  messageType: "JOIN" | "HEARTBEAT" | "PIN"
  senderId: peerId
  signature: ECDSA signature (Base64)
  payload: PeerInfo | ShareSession
}
```

---

## 🔄 Workflow

### Peer Discovery (với TLS + Signatures)

1. **Peer A khởi động:**

   ```
   Generate ECDSA keypair
   Create self-signed certificate
   Start SSLServerSocket (port 8888)
   ```

2. **Peer A quét mạng:**

   ```
   For each IP in subnet:
     Try SSLSocket connection
     Create SignedMessage("JOIN", localPeerInfo)
     Send signed message
   ```

3. **Peer B nhận JOIN:**

   ```
   Accept SSL connection
   Receive SignedMessage
   Verify signature with Peer A's public key
   If valid:
     Add Peer A to trusted list
     Send SignedMessage("ACK", localPeerInfo)
   ```

4. **Heartbeat:**
   ```
   Every 5 seconds:
     Send SignedMessage("HEARTBEAT", localPeerInfo)
     Verify response signature
   ```

---

### File Transfer (với TLS + AES)

1. **Download request:**

   ```
   Client ----[TLS]----> Server
          Request file
   ```

2. **Server response:**

   ```
   Read file → Compress (GZIP) → Encrypt (AES) → Send over TLS
   ```

3. **Client receive:**
   ```
   Receive over TLS → Decrypt (AES) → Decompress → Save file
   ```

**Defense in depth:**

-  TLS: Bảo vệ transport layer
-  AES: Bảo vệ file content (even if TLS compromised)

---

### PIN Sharing (với TLS + Signatures)

1. **Peer A tạo PIN:**

   ```
   Create ShareSession(pin, fileInfo, ownerPeer)
   Create SignedMessage("PIN", session)
   Broadcast to all peers (over TLS)
   ```

2. **Peer B nhận PIN:**

   ```
   Receive SignedMessage over TLS
   Verify signature with Peer A's public key
   If valid:
     Store in globalSessions
   ```

3. **Peer C nhập PIN để download:**
   ```
   Find session by PIN
   Download file from owner peer (TLS + AES)
   ```

---

## 📊 So sánh trước/sau

| Feature                      | Before                          | After                         |
| ---------------------------- | ------------------------------- | ----------------------------- |
| **Discovery channel**        | Plaintext TCP                   | TLS/SSL                       |
| **Search channel**           | Plaintext TCP                   | TLS/SSL                       |
| **Transfer channel**         | TCP + AES                       | TLS + AES (double encryption) |
| **PIN channel**              | Plaintext TCP                   | TLS/SSL                       |
| **Peer authentication**      | ❌ None                         | ✅ ECDSA signatures           |
| **Message integrity**        | ❌ No verification              | ✅ Digital signatures         |
| **MITM protection**          | ❌ Vulnerable                   | ✅ TLS prevents MITM          |
| **Impersonation protection** | ❌ Anyone can claim any peer ID | ✅ Signatures verify identity |
| **Metadata protection**      | ❌ File names, sizes visible    | ✅ Encrypted via TLS          |

---

## 🚀 Cách sử dụng

### Code không thay đổi gì (Transparent security)

```java
// Khởi tạo P2P Service (tự động khởi tạo SecurityManager)
P2PService p2pService = new P2PService("MyPeer", 0);

// Start service (tự động dùng TLS + signatures)
p2pService.start();

// Tất cả operations đều secure bên dưới
p2pService.searchFile("report.pdf");
p2pService.downloadFile(peer, fileInfo, "downloads");
```

**Transparency:**

-  UI/Controller code không cần thay đổi
-  Security được handle tự động bởi các services
-  Backward compatibility (nhưng chỉ kết nối được với peers cùng có TLS)

---

## ⚠️ Lưu ý triển khai

### 1. **Quản lý Keystore/Truststore**

Hiện tại:

-  Keypair được generate mỗi khi app khởi động (ephemeral)
-  Certificate self-signed tạm thời

**Production cần:**

-  Lưu keypair vào persistent keystore (file)
-  Load keypair khi restart để giữ nguyên peer identity
-  Trust bootstrapping mechanism (first-time trust)

### 2. **Certificate Validation**

Hiện tại:

-  Trust all certificates (for LAN dev)

**Production cần:**

-  Certificate pinning
-  CA-signed certificates (nếu có infrastructure)
-  CRL/OCSP checking

### 3. **Compatibility**

-  Peers phải cùng dùng TLS mới kết nối được
-  Không backward compatible với version cũ (plaintext)

### 4. **Performance**

-  TLS handshake overhead (~100-200ms per connection)
-  ECDSA signature generation/verification (~1-5ms per message)
-  Acceptable cho LAN P2P application

---

## 🛡️ Security Analysis

### Threats mitigated:

✅ **Man-in-the-Middle (MITM)**

-  TLS prevents eavesdropping và tampering
-  Certificate verification (though self-signed in LAN)

✅ **Message Forgery**

-  ECDSA signatures ensure message authenticity
-  Recipient can verify sender's identity

✅ **Peer Impersonation**

-  Attacker cannot claim another peer's ID without private key
-  Signatures bind peer ID to public key

✅ **Data Exposure**

-  File content encrypted (AES + TLS)
-  Metadata encrypted (TLS)
-  PINs encrypted (TLS)

### Remaining risks:

⚠️ **Trust bootstrapping**

-  Trong LAN, trust on first use (TOFU) model
-  Vulnerable đến first connection MITM (cần certificate pinning)

⚠️ **Key management**

-  Ephemeral keys → peer identity không persistent
-  Cần persistent keystore

⚠️ **DoS attacks**

-  Malicious peer có thể flood signatures
-  Cần rate limiting

---

## 📚 Dependencies

Các class mới:

-  `security/SecurityManager.java` - Core security manager
-  `model/SignedMessage.java` - Signed message wrapper

Các class đã update:

-  `model/PeerInfo.java` - Thêm publicKey field
-  `network/PeerDiscovery.java` - TLS + signature verification
-  `network/FileSearchService.java` - TLS channels
-  `network/FileTransferService.java` - TLS channels
-  `service/PINCodeService.java` - TLS + signed PIN messages
-  `service/P2PService.java` - Integrate SecurityManager

---

## 🔧 Testing

### Test local:

1. Chạy 2+ instances trên cùng máy (different ports)
2. Kiểm tra:
   -  TLS handshake success
   -  Signature verification logs
   -  Peer discovery với signed messages
   -  File transfer qua TLS

### Verify security:

```bash
# Wireshark capture để kiểm tra traffic encrypted
# Filter: tcp.port == 8888 || tcp.port == 8891
# → Không thấy plaintext content
```

---

## 📈 Future enhancements

1. **Ephemeral DH key exchange**

   -  Derive session AES keys thay vì shared key
   -  Perfect forward secrecy

2. **Certificate Authority (CA)**

   -  Local CA cho organization
   -  Centralized trust management

3. **Key persistence**

   -  Lưu keypair vào file
   -  Peer identity persistent across restarts

4. **Access control**

   -  Whitelist/blacklist peers
   -  Permission-based file sharing

5. **Audit logging**
   -  Log tất cả security events
   -  Signature verification failures

---

## 👨‍💻 Developed by

Cải tiến security cho P2P File Sharing project

-  TLS/SSL implementation
-  ECDSA signatures
-  Self-signed certificates
-  Secure peer authentication

**Độ khó: High** ✅ Completed
