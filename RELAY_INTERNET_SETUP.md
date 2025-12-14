# RELAY SETUP - Kết nối Peers qua Internet

## Vấn đề

-  LAN discovery chỉ hoạt động trong cùng mạng (192.168.x.x)
-  Để kết nối 2 máy qua Internet, cần **Relay Server** làm trung gian

## Giải pháp: Relay Server Architecture

```
Máy 1 (Client) ──┐
                 │
Máy 2 (Client) ──┼──> Relay Server (Máy có IP công khai)
                 │
Máy 3 (Client) ──┘
```

---

## 🚀 CÁCH 1: MỘT MÁY LÀM SERVER, CÁC MÁY KHÁC LÀ CLIENT

### **Bước 1: Máy Server (chạy relay server)**

Máy này cần có **IP công khai** hoặc **port forwarding** (192.168.x.x:8080 → public IP:8080)

#### Windows:

```cmd
set START_RELAY_SERVER=true
mvn clean compile exec:java -Dexec.mainClass="org.example.p2psharefile.MainApplication"
```

#### PowerShell:

```powershell
$env:START_RELAY_SERVER="true"
mvn clean compile exec:java -Dexec.mainClass="org.example.p2psharefile.MainApplication"
```

**Lưu ý IP công khai của máy này** (dùng lệnh `ipconfig` hoặc check trên router)

---

### **Bước 2: Các máy Client (kết nối tới relay server)**

Thay `192.168.1.100` bằng **IP công khai** của máy server ở bước 1.

#### Windows:

```cmd
set RELAY_SERVER_URL=http://192.168.1.100:8080
set START_RELAY_SERVER=false
mvn clean compile exec:java -Dexec.mainClass="org.example.p2psharefile.MainApplication"
```

#### PowerShell:

```powershell
$env:RELAY_SERVER_URL="http://192.168.1.100:8080"
$env:START_RELAY_SERVER="false"
mvn clean compile exec:java -Dexec.mainClass="org.example.p2psharefile.MainApplication"
```

---

## 🌍 CÁCH 2: DÙNG NGROK (Không cần IP công khai)

Nếu không có IP công khai, dùng **ngrok** để expose relay server ra Internet.

### **Bước 1: Cài đặt ngrok**

1. Download: https://ngrok.com/download
2. Giải nén và đăng ký tài khoản miễn phí

### **Bước 2: Máy Server**

Chạy relay server như bình thường:

```cmd
mvn clean compile exec:java -Dexec.mainClass="org.example.p2psharefile.MainApplication"
```

### **Bước 3: Expose port 8080 qua ngrok**

Mở terminal mới:

```cmd
ngrok http 8080
```

Ngrok sẽ tạo URL công khai, ví dụ: `https://abc123.ngrok.io`

### **Bước 4: Các máy Client**

Dùng URL ngrok:

```cmd
set RELAY_SERVER_URL=https://abc123.ngrok.io
set START_RELAY_SERVER=false
mvn clean compile exec:java -Dexec.mainClass="org.example.p2psharefile.MainApplication"
```

---

## ✅ Kiểm tra kết nối

### Log thành công trên Server:

```
🚀 Đang khởi động RelayServer LOCAL...
✅ RelayServer đã khởi động trên port 8080
📝 Peer đăng ký: Peer_NAM (203.0.113.45:12345)
```

### Log thành công trên Client:

```
🌍 Sử dụng relay server từ environment: http://192.168.1.100:8080
✓ Đã đăng ký peer với relay server: Peer_NAM
🔍 Đã phát hiện 2 peer(s) qua relay
🌐 Phát hiện peer qua Internet: Peer_VIET (203.0.113.67:54321)
```

---

## 🔧 Troubleshooting

### Vấn đề: "Đã phát hiện 0 peer(s) qua relay"

**Nguyên nhân:**

-  Client không kết nối được tới relay server
-  Firewall chặn port 8080
-  IP/URL relay server không đúng

**Giải pháp:**

1. Kiểm tra relay server có chạy không (xem log console)
2. Test kết nối: `curl http://IP_SERVER:8080/api/peers/list`
3. Tắt firewall tạm thời để test
4. Kiểm tra port forwarding trên router (nếu dùng)

### Vấn đề: Connection timeout

**Nguyên nhân:**

-  Relay server URL sai
-  Port bị chặn

**Giải pháp:**

-  Dùng ngrok thay vì IP trực tiếp
-  Thử port khác (8081, 8082, etc.)

---

## 📝 Ví dụ cụ thể: Test 2 máy

### Máy A (Server) - IP: 192.168.1.100

```cmd
# Không cần set gì, chạy bình thường
mvn clean compile exec:java -Dexec.mainClass="org.example.p2psharefile.MainApplication"
```

### Máy B (Client) - IP: 192.168.1.200

```cmd
set RELAY_SERVER_URL=http://192.168.1.100:8080
set START_RELAY_SERVER=false
mvn clean compile exec:java -Dexec.mainClass="org.example.p2psharefile.MainApplication"
```

Kết quả: Máy B sẽ phát hiện Máy A qua relay server!

---

## 🎯 Lưu ý quan trọng

1. **Port forwarding**: Nếu máy server sau router, cần forward port 8080
2. **Firewall**: Cho phép port 8080 (inbound/outbound)
3. **IP công khai vs LAN**:
   -  Trong cùng mạng LAN: dùng 192.168.x.x
   -  Qua Internet: dùng IP công khai hoặc ngrok
4. **Heartbeat**: Peer tự động gửi heartbeat mỗi 30s, nếu không -> bị xóa khỏi registry

---

## 🚀 Quick Start (cho người vội)

### Máy 1 (Server):

```cmd
mvn clean compile exec:java -Dexec.mainClass="org.example.p2psharefile.MainApplication"
```

### Máy 2 (Client):

```cmd
set RELAY_SERVER_URL=http://IP_CUA_MAY_1:8080
set START_RELAY_SERVER=false
mvn clean compile exec:java -Dexec.mainClass="org.example.p2psharefile.MainApplication"
```

Thay `IP_CUA_MAY_1` = IP thực của máy 1 (dùng `ipconfig` để xem).
