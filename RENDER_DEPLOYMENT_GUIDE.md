# 🌐 HƯỚNG DẪN DEPLOY RELAY SERVER LÊN RENDER

## 📋 Tổng quan

Hướng dẫn này giúp bạn deploy một **Relay Server chung** lên [Render.com](https://render.com) để tất cả các peer trong ứng dụng P2P có thể kết nối với nhau **qua Internet**, không chỉ trong cùng mạng LAN.

### ✨ Lợi ích:

-  ✅ Các peer từ **mạng khác nhau** có thể kết nối với nhau
-  ✅ Không cần cấu hình port forwarding hoặc NAT
-  ✅ Tự động fallback: Thử P2P trước, nếu không được thì dùng Relay
-  ✅ File được **mã hóa AES-256** client-side trước khi upload
-  ✅ Server chỉ lưu trữ file tạm (24h tự động xóa)
-  ✅ **MIỄN PHÍ** với Render Free Plan (750h/month)

---

## 🚀 BƯỚC 1: Deploy lên Render

### Cách 1: Deploy từ GitHub (Khuyến nghị)

1. **Push code lên GitHub repository**

   ```bash
   git add .
   git commit -m "Add standalone relay server"
   git push origin main
   ```

2. **Tạo tài khoản Render**

   -  Truy cập: https://render.com
   -  Sign up (miễn phí) bằng GitHub account

3. **Tạo Web Service mới**

   -  Vào Dashboard → Click **"New +"** → Chọn **"Web Service"**
   -  Connect GitHub repository của bạn
   -  Chọn repo: `P2PShareFile`

4. **Cấu hình service:**

   ```yaml
   Name: p2p-relay-server
   Region: Singapore (hoặc gần bạn nhất)
   Branch: main
   Runtime: Java
   Build Command: mvn clean package -DskipTests
   Start Command: java -cp target/classes:target/P2PShareFile-1.0-SNAPSHOT.jar org.example.p2psharefile.relay.StandaloneRelayServer
   Instance Type: Free
   ```

5. **Thêm Environment Variables** trong Render dashboard:

   ```
   PORT = 10000
   STORAGE_DIR = /tmp/relay-storage
   FILE_EXPIRY_HOURS = 24
   MAX_FILE_SIZE_MB = 100
   ENABLE_CORS = true
   JAVA_TOOL_OPTIONS = -Xmx512m -Xms256m
   ```

6. **Deploy**
   -  Click **"Create Web Service"**
   -  Chờ 5-10 phút để build và deploy
   -  Lấy URL của service (ví dụ: `https://p2p-relay-server.onrender.com`)

### Cách 2: Deploy bằng Dockerfile

1. **Build Docker image**

   ```bash
   docker build -t p2p-relay-server .
   ```

2. **Test locally**

   ```bash
   docker run -p 8080:8080 -e PORT=8080 p2p-relay-server
   ```

3. **Deploy lên Render**
   -  Trong Render dashboard, chọn **"New +" → "Web Service"**
   -  Chọn **"Deploy from Docker"**
   -  Dockerfile path: `./Dockerfile`
   -  Configure như Cách 1

---

## 🔧 BƯỚC 2: Cấu hình Client để kết nối Relay Server

### Option 1: Sử dụng Environment Variable (Khuyến nghị)

**Windows:**

```cmd
set RELAY_SERVER_URL=https://p2p-relay-server.onrender.com
set START_RELAY_SERVER=false
```

**Linux/Mac:**

```bash
export RELAY_SERVER_URL=https://p2p-relay-server.onrender.com
export START_RELAY_SERVER=false
```

**Chạy ứng dụng:**

```bash
mvn clean javafx:run
```

### Option 2: Thay đổi trong code

Sửa file [MainController.java](src/main/java/org/example/p2psharefile/controller/MainController.java):

```java
// Trước khi start P2P Service
System.setProperty("RELAY_SERVER_URL", "https://p2p-relay-server.onrender.com");
System.setProperty("START_RELAY_SERVER", "false");

p2pService = new P2PService(displayName, port);
RelayStarter.startRelayInBackground(p2pService);
```

---

## 📊 BƯỚC 3: Kiểm tra hoạt động

### 1. Kiểm tra Relay Server

Mở browser và truy cập:

```
https://p2p-relay-server.onrender.com/api/peers/list
```

Nếu thấy response JSON → Server hoạt động tốt!

### 2. Test với 2 peer từ 2 mạng khác nhau

**Peer 1 (Máy A - Mạng A):**

```bash
set RELAY_SERVER_URL=https://p2p-relay-server.onrender.com
mvn clean javafx:run
```

**Peer 2 (Máy B - Mạng B):**

```bash
set RELAY_SERVER_URL=https://p2p-relay-server.onrender.com
mvn clean javafx:run
```

**Test:**

1. Cả 2 peer đều click "Start"
2. Peer 1 share một file
3. Peer 2 click "Search" → Sẽ thấy file của Peer 1
4. Peer 2 download file

**Expected behavior:**

-  ✅ Peer 2 thấy Peer 1 trong danh sách (qua relay discovery)
-  ✅ Thử kết nối P2P trước (sẽ timeout vì khác mạng)
-  ✅ Tự động fallback sang Relay để transfer file
-  ✅ File được download thành công

---

## 🔍 CÁCH HOẠT ĐỘNG

### Luồng kết nối:

```
┌─────────┐                 ┌──────────────────┐                 ┌─────────┐
│ Peer A  │                 │  Relay Server    │                 │ Peer B  │
│(Mạng 1) │                 │  (Render.com)    │                 │(Mạng 2) │
└────┬────┘                 └────────┬─────────┘                 └────┬────┘
     │                               │                                 │
     │  1. Register peer info        │                                 │
     ├──────────────────────────────>│                                 │
     │                               │  2. Register peer info          │
     │                               │<────────────────────────────────┤
     │                               │                                 │
     │  3. Discover peers            │                                 │
     ├──────────────────────────────>│                                 │
     │  4. Return peer list          │                                 │
     │<──────────────────────────────┤                                 │
     │                               │                                 │
     │  5. Try P2P direct (timeout)  │                                 │
     ├───────────X───────────────────┼─────────────────X──────────────>│
     │                               │                                 │
     │  6. Fallback: Upload file     │                                 │
     │     (encrypted AES-256)       │                                 │
     ├──────────────────────────────>│                                 │
     │  7. Return download URL       │                                 │
     │<──────────────────────────────┤                                 │
     │                               │                                 │
     │  8. Send download URL         │  9. Download file               │
     │───────────────────────────────┼────────────────────────────────>│
     │                               │<────────────────────────────────┤
     │                               │ 10. Stream encrypted chunks     │
     │                               ├────────────────────────────────>│
     │                               │                                 │
```

### Luồng transfer file:

1. **Peer A** upload file lên Relay Server (file đã mã hóa AES-256)
2. Relay Server lưu file tạm và trả về `uploadId` + `downloadUrl`
3. **Peer A** gửi `downloadUrl` cho **Peer B** qua signaling
4. **Peer B** download file từ Relay Server
5. **Peer B** giải mã file và verify hash
6. Sau 24h, file tự động bị xóa khỏi server

---

## 💰 Chi phí & Giới hạn

### Render Free Plan:

-  ✅ 750 giờ/tháng miễn phí
-  ✅ 512MB RAM
-  ✅ 1GB disk storage
-  ⚠️ Sleep sau 15 phút không hoạt động (cold start ~30s)
-  ⚠️ Giới hạn bandwidth: 100GB/tháng

### Khuyến nghị:

-  **File size tối đa:** 100MB
-  **Thời gian lưu file:** 24 giờ
-  **Số lượng peer đồng thời:** ~50 peers
-  **Bandwidth ước tính:** ~1000 file transfers/tháng (với file trung bình 100MB)

### Nâng cấp:

Nếu cần nhiều hơn, upgrade lên Render Paid Plan ($7/tháng):

-  🚀 No sleep
-  🚀 Unlim bandwidth
-  🚀 Better performance

---

## ⚠️ LƯU Ý QUAN TRỌNG

### 1. Cold Start trên Free Plan

-  Server sleep sau 15 phút không dùng
-  Request đầu tiên sau khi sleep sẽ mất ~30s để wake up
-  **Giải pháp:** Dùng cron job ping server mỗi 10 phút (hoặc upgrade paid plan)

### 2. File Security

-  ✅ File được mã hóa **client-side** trước khi upload
-  ✅ Server chỉ lưu trữ encrypted data
-  ✅ Chỉ người có download URL mới tải được file
-  ⚠️ Không share download URL công khai

### 3. Storage Limit

-  Free plan: 1GB storage
-  Auto cleanup sau 24h
-  Nếu đầy → upload mới sẽ fail
-  **Giải pháp:** Giảm `FILE_EXPIRY_HOURS` hoặc upgrade plan

### 4. Bandwidth Limit

-  Free: 100GB/tháng
-  Nếu vượt → service bị suspend
-  **Giải pháp:** Monitor usage hoặc upgrade plan

---

## 🧪 Test Local trước khi Deploy

### Chạy Relay Server local:

**Windows:**

```cmd
run-relay-server.bat
```

**Linux/Mac:**

```bash
chmod +x run-relay-server.sh
./run-relay-server.sh
```

**Hoặc manual:**

```bash
mvn clean compile
java -cp target/classes org.example.p2psharefile.relay.StandaloneRelayServer
```

### Test với client:

```bash
set RELAY_SERVER_URL=http://localhost:8080
mvn clean javafx:run
```

---

## 🛠️ Troubleshooting

### Lỗi: "Không thể kết nối relay server"

-  ✅ Kiểm tra `RELAY_SERVER_URL` có đúng không
-  ✅ Kiểm tra Render service có đang chạy không (vào dashboard)
-  ✅ Test endpoint: `curl https://your-server.onrender.com/api/peers/list`

### Lỗi: "Upload file thất bại"

-  ✅ Kiểm tra file size < 100MB
-  ✅ Kiểm tra storage trên server còn chỗ không
-  ✅ Xem logs trên Render dashboard

### Lỗi: "Service sleep/cold start"

-  ✅ Upgrade lên Paid plan ($7/tháng)
-  ✅ Hoặc setup cron job để ping server

### Lỗi: "Bandwidth exceeded"

-  ✅ Monitor usage trong Render dashboard
-  ✅ Giảm file size hoặc số lượng transfers
-  ✅ Upgrade plan

---

## 📚 Tài liệu tham khảo

-  [Render Documentation](https://render.com/docs)
-  [Java Web Service on Render](https://render.com/docs/deploy-java)
-  [Environment Variables](https://render.com/docs/environment-variables)
-  [Free Plan Limits](https://render.com/docs/free)

---

## ❓ FAQ

### Q: Có thể dùng Heroku, Railway, Fly.io không?

**A:** Có! Chỉ cần:

1. Deploy Dockerfile hoặc Java app
2. Set environment variables
3. Expose port

### Q: Có thể tự host relay server không?

**A:** Có! Chạy [StandaloneRelayServer.java](src/main/java/org/example/p2psharefile/relay/StandaloneRelayServer.java) trên VPS của bạn:

```bash
java -cp target/classes org.example.p2psharefile.relay.StandaloneRelayServer
```

### Q: Relay server có log dữ liệu gì không?

**A:** Relay chỉ log metadata (filename, size, uploadId). **KHÔNG log nội dung file** vì file đã encrypted client-side.

### Q: Tốc độ transfer file qua relay có chậm không?

**A:** Phụ thuộc vào:

-  Network speed của client
-  Render server location (chọn region gần bạn)
-  File size

Thông thường: **5-10MB/s** (tương đương upload/download bình thường)

### Q: Có giới hạn số lượng peer không?

**A:** Free plan: ~50 peers đồng thời. Paid plan: unlimited.

---

## 🎉 Kết luận

✅ **Deploy relay server lên Render = Miễn phí + Dễ dàng + Hiệu quả**  
✅ **Các peer từ mạng khác nhau có thể kết nối và chia sẻ file**  
✅ **Tự động fallback: P2P trước, Relay sau**  
✅ **Bảo mật: AES-256 encryption + Auto cleanup**

Happy sharing! 🚀
