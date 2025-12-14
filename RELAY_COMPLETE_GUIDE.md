# 🌐 RELAY SERVER - TÀI LIỆU TỔNG HỢP

## 📖 Mục lục

1. [Tổng quan](#tổng-quan)
2. [Quick Start](#quick-start)
3. [Architecture](#architecture)
4. [Deployment](#deployment)
5. [API Reference](#api-reference)
6. [Security](#security)
7. [Troubleshooting](#troubleshooting)

---

## 🎯 Tổng quan

### Vấn đề

-  P2P chỉ hoạt động trong cùng mạng LAN
-  NAT traversal khó khăn
-  Peers từ mạng khác nhau không thể kết nối

### Giải pháp

**Relay Server** - Server trung gian giúp:

-  ✅ Peers từ mạng khác nhau phát hiện và kết nối với nhau
-  ✅ Tự động fallback: P2P trước, Relay sau (smart routing)
-  ✅ File transfer qua relay với mã hóa end-to-end
-  ✅ Không cần config router, port forwarding, NAT

### Cách hoạt động

```
┌─────────┐                  ┌──────────────┐                  ┌─────────┐
│ Peer A  │ ←── P2P Direct ──→ Peer B       │                  │ Peer C  │
│(LAN A)  │                  │(Cùng LAN)    │                  │(LAN B)  │
└────┬────┘                  └──────────────┘                  └────┬────┘
     │                                                                │
     │                    ┌─────────────────────┐                   │
     └────── Relay ──────→│   Relay Server      │←───── Relay ──────┘
                          │  (Cloud/Internet)   │
                          └─────────────────────┘
```

**Smart Routing:**

1. Cố gắng kết nối P2P trực tiếp (nhanh nhất)
2. Nếu timeout (khác mạng) → tự động dùng Relay
3. Transfer file qua relay với chunking, resume, verify

---

## 🚀 Quick Start

### Cách 1: Sử dụng Relay Server có sẵn

**1. Set environment variable:**

```bash
# Windows
set RELAY_SERVER_URL=https://p2p-relay-server.onrender.com
set START_RELAY_SERVER=false

# Linux/Mac
export RELAY_SERVER_URL=https://p2p-relay-server.onrender.com
export START_RELAY_SERVER=false
```

**2. Chạy ứng dụng:**

```bash
mvn clean javafx:run
```

**3. Test:** Chạy 2 peer từ 2 mạng khác nhau → Vẫn kết nối được!

### Cách 2: Deploy Relay Server riêng

**1. Deploy lên Render.com:**

-  Xem chi tiết: [RENDER_DEPLOYMENT_GUIDE.md](RENDER_DEPLOYMENT_GUIDE.md)
-  Thời gian: ~10 phút
-  Chi phí: **MIỄN PHÍ** (Free tier)

**2. Hoặc chạy local:**

```bash
# Windows
run-relay-server.bat

# Linux/Mac
./run-relay-server.sh
```

**3. Test server:**

```bash
# Windows
test-relay-server.bat http://localhost:8080

# Linux/Mac
./test-relay-server.sh http://localhost:8080
```

---

## 🏗️ Architecture

### Components

#### 1. **StandaloneRelayServer** ([code](src/main/java/org/example/p2psharefile/relay/StandaloneRelayServer.java))

-  Relay server độc lập, có thể chạy riêng
-  Config qua environment variables
-  Dùng để deploy lên cloud

#### 2. **RelayServer** ([code](src/main/java/org/example/p2psharefile/relay/RelayServer.java))

-  HTTP server core (Java HttpServer)
-  Endpoints: upload, download, status, peer discovery
-  Tự động cleanup file hết hạn

#### 3. **RelayClient** ([code](src/main/java/org/example/p2psharefile/network/RelayClient.java))

-  Client library để upload/download qua relay
-  Chunking, resume, retry
-  Client-side encryption (AES-256)

#### 4. **PeerRegistry** ([code](src/main/java/org/example/p2psharefile/relay/PeerRegistry.java))

-  Quản lý danh sách peers online
-  Heartbeat & timeout detection
-  Peer discovery API

#### 5. **RelayStarter** ([code](src/main/java/org/example/p2psharefile/relay/RelayStarter.java))

-  Helper để khởi động relay trong app
-  Auto-detect: local server vs remote server
-  Smart configuration

---

## 📡 API Reference

### Base URL

```
Production: https://p2p-relay-server.onrender.com
Local: http://localhost:8080
```

### Endpoints

#### 1. Health Check

```http
GET /api/relay/status/health
```

**Response:**

```json
{
	"status": "healthy",
	"uptime": 1234567890,
	"activePeers": 5,
	"activeUploads": 2,
	"timestamp": 1702345678901
}
```

#### 2. Register Peer

```http
POST /api/peers/register
Content-Type: application/json

{
  "peerId": "peer_abc123",
  "displayName": "My Peer",
  "publicIp": "123.45.67.89",
  "port": 12345,
  "publicKey": "MIIBIjANBg..."
}
```

**Response:**

```json
{
	"success": true,
	"peerId": "peer_abc123"
}
```

#### 3. List Peers

```http
GET /api/peers/list
```

**Response:**

```json
{
	"peers": [
		{
			"peerId": "peer_abc123",
			"displayName": "Peer A",
			"ip": "123.45.67.89",
			"port": 12345,
			"publicKey": "MIIBIjANBg..."
		},
		{
			"peerId": "peer_xyz789",
			"displayName": "Peer B",
			"ip": "98.76.54.32",
			"port": 54321,
			"publicKey": "MIIBIjANBg..."
		}
	],
	"count": 2
}
```

#### 4. Upload File Chunk

```http
POST /api/relay/upload
X-Upload-Id: upload_abc123
X-File-Name: document.pdf
X-Chunk-Index: 0
X-Total-Chunks: 10
Content-Type: application/octet-stream

[binary chunk data]
```

**Response:**

```json
{
	"uploadId": "upload_abc123",
	"chunkIndex": 0,
	"status": "ok"
}
```

#### 5. Download File

```http
GET /api/relay/download/upload_abc123
Range: bytes=0-1048575  (optional, for resume)
```

**Response:**

```
Content-Type: application/octet-stream
Content-Length: 12345678
Content-Disposition: attachment; filename="document.pdf"

[binary file data]
```

#### 6. Upload Status

```http
GET /api/relay/status/upload_abc123
```

**Response:**

```json
{
	"uploadId": "upload_abc123",
	"fileName": "document.pdf",
	"totalSize": 12345678,
	"uploadedSize": 12345678,
	"isComplete": true,
	"receivedChunks": [0, 1, 2, 3, 4, 5, 6, 7, 8, 9],
	"expiryTime": 1702432078901
}
```

#### 7. Heartbeat

```http
POST /api/peers/heartbeat
Content-Type: application/json

{
  "peerId": "peer_abc123"
}
```

**Response:**

```json
{
	"success": true
}
```

---

## 🔐 Security

### File Encryption

-  **Algorithm:** AES-256-GCM
-  **Encryption:** Client-side trước khi upload
-  **Decryption:** Client-side sau khi download
-  **Server:** Chỉ lưu encrypted data, không có key

### Access Control

-  Upload URL chỉ dùng để upload (1 lần)
-  Download URL cần `uploadId` (UUID random)
-  Không có public listing của files

### Data Retention

-  File tự xóa sau 24 giờ (configurable)
-  Cleanup job chạy mỗi 10 phút
-  Không backup, không recovery

### Network

-  Support HTTPS (recommended cho production)
-  CORS enabled (cho web clients)
-  No authentication (dùng relay như CDN)

---

## 🛠️ Deployment

### Requirements

-  **Java:** 21+
-  **Maven:** 3.9+
-  **RAM:** 512MB minimum
-  **Storage:** 1GB minimum
-  **Bandwidth:** Depends on usage

### Deploy lên Render.com

Chi tiết xem: [RENDER_DEPLOYMENT_GUIDE.md](RENDER_DEPLOYMENT_GUIDE.md)

**Tóm tắt:**

1. Push code lên GitHub
2. Tạo Web Service trên Render
3. Set environment variables
4. Deploy (auto build & run)
5. Lấy URL và dùng

**Thời gian:** ~10 phút  
**Chi phí:** Miễn phí (Free tier: 750h/month)

### Deploy lên VPS

```bash
# 1. Clone code
git clone https://github.com/your-repo/P2PShareFile
cd P2PShareFile

# 2. Build
mvn clean package -DskipTests

# 3. Set env vars
export PORT=8080
export STORAGE_DIR=/var/relay-storage
export FILE_EXPIRY_HOURS=24

# 4. Run
java -cp target/classes:target/*.jar \
  org.example.p2psharefile.relay.StandaloneRelayServer
```

### Docker Deploy

```bash
# Build image
docker build -t p2p-relay-server .

# Run container
docker run -d \
  -p 8080:8080 \
  -e PORT=8080 \
  -e STORAGE_DIR=/app/relay-storage \
  -e FILE_EXPIRY_HOURS=24 \
  -v relay-storage:/app/relay-storage \
  --name relay-server \
  p2p-relay-server

# Check logs
docker logs -f relay-server
```

---

## 🔍 Monitoring

### Health Check

```bash
curl https://your-server.com/api/relay/status/health
```

### Metrics to Monitor

-  **activePeers:** Số peers online
-  **activeUploads:** Số file đang upload/stored
-  **uptime:** Server uptime
-  **disk usage:** Storage space used
-  **bandwidth:** Network I/O

### Render Dashboard

-  Logs: Real-time streaming
-  Metrics: CPU, RAM, Network
-  Deploy history: Rollback support

---

## ❓ Troubleshooting

### Vấn đề: "Cannot connect to relay server"

**Nguyên nhân:**

-  URL sai
-  Server chưa start
-  Network/firewall block

**Giải pháp:**

```bash
# Test connectivity
curl https://your-server.com/api/relay/status/health

# Check environment
echo $RELAY_SERVER_URL

# Check logs (Render dashboard)
```

### Vấn đề: "Upload failed"

**Nguyên nhân:**

-  File quá lớn (> MAX_FILE_SIZE_MB)
-  Storage đầy
-  Network timeout

**Giải pháp:**

-  Tăng `MAX_FILE_SIZE_MB`
-  Cleanup old files
-  Retry với smaller chunks
-  Check server logs

### Vấn đề: "Server sleep/cold start"

**Nguyên nhân:**

-  Render Free tier sleep sau 15 phút không dùng

**Giải pháp:**

-  Upgrade Paid plan ($7/month)
-  Setup cron job ping server
-  Accept 30s cold start

### Vấn đề: "Bandwidth exceeded"

**Nguyên nhân:**

-  Free tier: 100GB/month limit

**Giải pháp:**

-  Monitor usage
-  Reduce file sizes
-  Upgrade plan
-  Self-host VPS

---

## 📊 Performance

### Benchmarks

**Upload speed:**

-  LAN: ~50MB/s
-  Internet: ~5-10MB/s (depends on network)

**Download speed:**

-  Same as upload

**Latency:**

-  Peer discovery: ~100-500ms
-  File metadata: ~50-200ms

**Scalability:**

-  Free tier: ~50 concurrent peers
-  Paid tier: ~500+ concurrent peers

---

## 🎓 Best Practices

### For Clients

1. Always try P2P first (faster)
2. Use relay as fallback only
3. Cleanup temp files after transfer
4. Handle network errors gracefully

### For Server

1. Regular cleanup of expired files
2. Monitor storage & bandwidth
3. Use HTTPS in production
4. Log errors for debugging
5. Backup config & env vars

### For Security

1. Never disable client-side encryption
2. Use strong random uploadIds
3. Don't share download URLs publicly
4. Rotate encryption keys periodically

---

## 📚 References

-  **Code:** [src/main/java/org/example/p2psharefile/relay/](src/main/java/org/example/p2psharefile/relay/)
-  **Deployment:** [RENDER_DEPLOYMENT_GUIDE.md](RENDER_DEPLOYMENT_GUIDE.md)
-  **Quick Start:** [RELAY_INTERNET_QUICKSTART.md](RELAY_INTERNET_QUICKSTART.md)
-  **Config:** [.env.example](.env.example)

---

## 📝 Changelog

### v1.0 (Current)

-  ✅ Standalone relay server
-  ✅ Render deployment support
-  ✅ Peer discovery & heartbeat
-  ✅ File upload/download with chunking
-  ✅ Client-side AES encryption
-  ✅ Auto cleanup
-  ✅ Health check endpoint
-  ✅ Docker support

### Future

-  [ ] WebSocket support (real-time)
-  [ ] Multiple relay servers (load balancing)
-  [ ] File compression
-  [ ] CDN integration
-  [ ] Web UI dashboard

---

## 🤝 Contributing

Contributions welcome! Please:

1. Fork the repo
2. Create feature branch
3. Test thoroughly
4. Submit PR with description

---

## 📄 License

MIT License - See [LICENSE](LICENSE) file

---

## 🎉 Kết luận

Relay Server giúp ứng dụng P2P hoạt động **qua Internet**, không chỉ trong LAN!

**Key benefits:**

-  ✅ Kết nối peers từ mạng khác nhau
-  ✅ Tự động fallback thông minh
-  ✅ Bảo mật end-to-end
-  ✅ Deploy dễ dàng & miễn phí
-  ✅ No config router/NAT

**Happy sharing!** 🚀
