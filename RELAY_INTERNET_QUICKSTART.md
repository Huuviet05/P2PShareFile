# 🚀 QUICK START: Kết nối Peers qua Internet với Relay Server

## ❌ Vấn đề hiện tại

-  Mỗi peer tự khởi động relay server riêng trên `localhost:8080`
-  Peers từ mạng khác nhau **KHÔNG THỂ** kết nối với nhau
-  Chỉ hoạt động trong cùng mạng LAN

## ✅ Giải pháp: Deploy Relay Server chung

### Bước 1: Deploy lên Render (5 phút)

1. **Push code lên GitHub** (nếu chưa có)

   ```bash
   git add .
   git commit -m "Add relay server"
   git push
   ```

2. **Tạo Web Service trên Render**
   -  Vào https://render.com → Sign up (miễn phí)
   -  New + → Web Service
   -  Connect GitHub repo
   -  Cấu hình:
      -  **Runtime:** Java
      -  **Build:** `mvn clean package -DskipTests`
      -  **Start:** `java -cp target/classes:target/*.jar org.example.p2psharefile.relay.StandaloneRelayServer`
3. **Thêm Environment Variables:**

   ```
   PORT = 10000
   STORAGE_DIR = /tmp/relay-storage
   FILE_EXPIRY_HOURS = 24
   ```

4. **Deploy** → Đợi 5-10 phút → Lấy URL (vd: `https://p2p-relay-server.onrender.com`)

### Bước 2: Cấu hình Client

**Windows:**

```cmd
set RELAY_SERVER_URL=https://p2p-relay-server.onrender.com
set START_RELAY_SERVER=false
mvn clean javafx:run
```

**Linux/Mac:**

```bash
export RELAY_SERVER_URL=https://p2p-relay-server.onrender.com
export START_RELAY_SERVER=false
mvn clean javafx:run
```

### Bước 3: Test

1. Chạy ứng dụng trên **2 máy khác mạng**
2. Cả 2 peer click **"Start"**
3. Peer 1 share file → Peer 2 click **"Search"**
4. ✅ **Peer 2 sẽ thấy Peer 1 và có thể download file!**

---

## 💡 Cách hoạt động

```
Peer A (Mạng 1) ←→ Relay Server (Cloud) ←→ Peer B (Mạng 2)
```

-  **Discovery:** Peers đăng ký với relay server → biết nhau tồn tại
-  **Transfer:**
   1. Thử P2P trước (trong LAN thì nhanh)
   2. Nếu fail → tự động dùng Relay (upload → relay → download)
-  **Security:** File mã hóa AES-256 client-side trước khi upload
-  **Auto cleanup:** File tự xóa sau 24h

---

## 📊 Chi phí & Giới hạn

**Render Free Plan:**

-  ✅ **MIỄN PHÍ** 750 giờ/tháng
-  ✅ 512MB RAM, 1GB storage
-  ⚠️ Sleep sau 15 phút không dùng (cold start ~30s)
-  ⚠️ Bandwidth: 100GB/tháng (~1000 file 100MB)

**Upgrade $7/tháng:**

-  🚀 No sleep
-  🚀 Unlim bandwidth
-  🚀 Better performance

---

## 🧪 Test Local trước khi Deploy

```bash
# Chạy relay server local
./run-relay-server.bat   # Windows
./run-relay-server.sh    # Linux/Mac

# Test với client
set RELAY_SERVER_URL=http://localhost:8080
mvn clean javafx:run
```

---

## ❓ Có truyền được file không?

### ✅ CÓ! Hoàn toàn được!

**Cách thức:**

1. Sender upload file lên relay server (encrypted)
2. Relay server lưu tạm + trả về download URL
3. Receiver download file từ relay server
4. File tự xóa sau 24h

**Giới hạn:**

-  File size: **100MB** (có thể tăng lên)
-  Tốc độ: **5-10MB/s** (tùy network)
-  Bandwidth: **100GB/tháng** (free plan)

**Bảo mật:**

-  ✅ File mã hóa AES-256 trước khi upload
-  ✅ Server chỉ lưu encrypted data
-  ✅ Chỉ người có URL mới download được

---

## 📚 Chi tiết đầy đủ

Xem [RENDER_DEPLOYMENT_GUIDE.md](RENDER_DEPLOYMENT_GUIDE.md) để biết thêm:

-  Hướng dẫn deploy chi tiết
-  Troubleshooting
-  Monitoring & scaling
-  Alternative platforms (Heroku, Railway, VPS)

---

## 🎯 Tóm tắt

| Trước                      | Sau                          |
| -------------------------- | ---------------------------- |
| ❌ Chỉ kết nối trong LAN   | ✅ Kết nối qua Internet      |
| ❌ Mỗi peer tự chạy server | ✅ 1 server chung cho tất cả |
| ❌ Không thể NAT traversal | ✅ Tự động fallback relay    |
| ❌ Setup phức tạp          | ✅ Chỉ set 1 env variable    |

**→ Deploy 1 lần, tất cả peers tự động kết nối nhau!** 🚀
