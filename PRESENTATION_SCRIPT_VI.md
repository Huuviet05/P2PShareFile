# 🎤 KỊCH BẢN TRÌNH BÀY DỰ ÁN P2P SHARE FILE

## 📋 Thông tin chung

| Mục                     | Nội dung       |
| ----------------------- | -------------- |
| **Tên dự án**           | P2P Share File |
| **Môn học**             | Lập Trình Mạng |
| **Thời gian trình bày** | 10-15 phút     |
| **Số slide gợi ý**      | 12-15 slides   |

---

## 🎯 Mục tiêu trình bày

1. Giới thiệu ý tưởng và tính năng chính
2. Giải thích kiến trúc kỹ thuật (P2P + Relay)
3. Demo trực tiếp các chức năng
4. Trình bày về bảo mật
5. Kết luận và hướng phát triển

---

## 📖 NỘI DUNG TRÌNH BÀY CHI TIẾT

---

### 🔹 PHẦN 1: GIỚI THIỆU (2-3 phút)

#### Slide 1: Trang bìa

```
P2P SHARE FILE
Ứng dụng chia sẻ file ngang hàng bảo mật

[Tên sinh viên]
[Mã số sinh viên]
Môn: Lập Trình Mạng
```

#### Slide 2: Vấn đề và Giải pháp

**Nói:**

> "Trong thực tế, chúng ta thường gặp các vấn đề khi chia sẻ file:
>
> -  Gửi qua email: giới hạn dung lượng, chậm
> -  Dùng USB: bất tiện, có thể lây virus
> -  Upload lên cloud: tốn thời gian, lo ngại bảo mật
>
> Dự án P2P Share File giải quyết các vấn đề này bằng cách cho phép chia sẻ file trực tiếp, nhanh chóng và bảo mật."

#### Slide 3: Tính năng chính

| Icon | Tính năng              | Mô tả                               |
| ---- | ---------------------- | ----------------------------------- |
| 🔄   | **P2P trong LAN**      | Chia sẻ trực tiếp, không cần server |
| 🌐   | **Relay qua Internet** | Chia sẻ với bất kỳ ai, mọi nơi      |
| 🔒   | **Mã hóa đầu cuối**    | TLS + AES-256 bảo vệ dữ liệu        |
| 📱   | **Quick Share (PIN)**  | Mã 6 số như Send Anywhere           |
| 🔍   | **Tìm kiếm phân tán**  | Tìm file trên toàn mạng P2P         |
| 👁️   | **Xem trước file**     | Preview ảnh, PDF, archive           |

---

### 🔹 PHẦN 2: KIẾN TRÚC KỸ THUẬT (3-4 phút)

#### Slide 4: Tổng quan kiến trúc

**Nói:**

> "Ứng dụng được thiết kế với 2 chế độ hoạt động chính:
>
> 1. **Chế độ P2P (LAN)**: Các máy tính trong cùng mạng kết nối trực tiếp với nhau
> 2. **Chế độ Relay (Internet)**: Sử dụng server trung gian khi không cùng mạng"

**Vẽ sơ đồ (hoặc slide):**

```
┌─────────────────────────────────────────────────────┐
│                                                     │
│   ┌─────┐    P2P (LAN)    ┌─────┐                  │
│   │ PC1 │◄───────────────►│ PC2 │                  │
│   └─────┘                 └─────┘                  │
│                                                     │
│                  hoặc                               │
│                                                     │
│   ┌─────┐              ┌─────────┐    ┌─────┐      │
│   │ PC1 │◄────────────►│  RELAY  │◄──►│ PC2 │      │
│   └─────┘   Internet   │ SERVER  │    └─────┘      │
│                        └─────────┘                  │
│                                                     │
└─────────────────────────────────────────────────────┘
```

#### Slide 5: Chế độ P2P - Peer Discovery

**Nói:**

> "Trong chế độ P2P, ứng dụng tự động tìm các máy khác trong mạng LAN bằng cách:
>
> 1. Quét tất cả IP trong subnet (192.168.1.1 → 192.168.1.254)
> 2. Thử kết nối TLS đến port 8888
> 3. Nếu kết nối thành công → Đó là peer
> 4. Gửi heartbeat mỗi 5 giây để biết peer còn online"

**Code minh họa:**

```java
// PeerDiscovery.java
for (int i = 1; i <= 254; i++) {
    String targetIP = "192.168.1." + i;
    SSLSocket socket = securityManager.createSSLSocket(targetIP, 8888);
    // Nếu kết nối thành công → Peer mới!
}
```

#### Slide 6: Chế độ P2P - File Search (Flooding)

**Nói:**

> "Khi tìm kiếm file, ứng dụng sử dụng thuật toán Flooding:
>
> 1. Peer A gửi yêu cầu tìm kiếm đến tất cả peers đã biết
> 2. Mỗi peer kiểm tra xem mình có file không, đồng thời forward request
> 3. Các kết quả được gửi ngược về peer A
> 4. Để tránh loop vô hạn, mỗi request có ID duy nhất"

#### Slide 7: Chế độ Relay

**Nói:**

> "Khi cần chia sẻ qua Internet, ứng dụng sử dụng Relay Server:
>
> 1. Người gửi upload file lên server
> 2. Server trả về mã PIN 6 số
> 3. Người gửi chia sẻ PIN qua tin nhắn
> 4. Người nhận nhập PIN → Download file
>
> Server được deploy trên Render.com (miễn phí)"

---

### 🔹 PHẦN 3: BẢO MẬT (2-3 phút)

#### Slide 8: Các lớp bảo mật

**Nói:**

> "Bảo mật là ưu tiên hàng đầu. Ứng dụng sử dụng 4 lớp bảo mật:"

| Lớp | Công nghệ       | Mục đích               |
| --- | --------------- | ---------------------- |
| 1   | **TLS 1.3**     | Mã hóa kênh truyền     |
| 2   | **AES-256-GCM** | Mã hóa nội dung file   |
| 3   | **RSA/ECDSA**   | Chữ ký số xác thực     |
| 4   | **SHA-256**     | Kiểm tra tính toàn vẹn |

**Nói thêm:**

> "Điều này có nghĩa là ngay cả khi có người nghe lén mạng (man-in-the-middle), họ cũng không thể đọc được nội dung file."

#### Slide 9: Quy trình mã hóa file

**Nói:**

> "Khi truyền file, dữ liệu được xử lý qua nhiều bước:"

```
File gốc
    ↓
[Nén GZIP] → Giảm kích thước
    ↓
[Mã hóa AES-256] → Bảo mật nội dung
    ↓
[Truyền qua TLS] → Bảo mật kênh
    ↓
[Verify SHA-256] → Đảm bảo file không bị thay đổi
```

---

### 🔹 PHẦN 4: DEMO TRỰC TIẾP (3-4 phút)

#### Chuẩn bị trước demo:

-  [ ] Mở 2 cửa sổ ứng dụng (hoặc 2 máy)
-  [ ] Có sẵn file để chia sẻ (ảnh, PDF, file nhỏ)
-  [ ] Relay server đang chạy (nếu demo Internet mode)

---

#### Demo 1: Chia sẻ P2P trong LAN (2 phút)

**Bước 1: Khởi động và Peer Discovery**

**Nói:**

> "Đầu tiên, tôi sẽ mở ứng dụng trên 2 máy tính. Các bạn có thể thấy ứng dụng tự động phát hiện nhau trong mạng LAN."

**Thao tác:**

1. Mở ứng dụng trên máy 1
2. Mở ứng dụng trên máy 2
3. Chỉ vào danh sách peers: "Máy 2 đã xuất hiện trong danh sách peers"

**Bước 2: Chia sẻ file bằng PIN**

**Nói:**

> "Bây giờ tôi sẽ chia sẻ một file ảnh. Tôi chọn file, sau đó tạo mã PIN."

**Thao tác:**

1. Tab "Chia sẻ Code" → Chọn file (drag & drop hoặc click)
2. Nhấn "Tạo PIN"
3. "Các bạn thấy mã PIN 6 số: 123456"
4. Trên máy 2: Tab "Tìm" → Nhập PIN → Nhấn tìm
5. "File đã được tìm thấy, tôi nhấn Download"
6. "File đã được tải về thành công!"

---

#### Demo 2: Tìm kiếm file (1 phút)

**Nói:**

> "Tiếp theo, tôi sẽ demo tính năng tìm kiếm. Tôi đã chia sẻ một số file trên máy 1."

**Thao tác:**

1. Máy 1: Tab "File" → Thêm file vào danh sách chia sẻ
2. Máy 2: Tab "Tìm" → Nhập từ khóa (VD: "report")
3. "Các bạn thấy kết quả tìm kiếm hiện ra, bao gồm file từ máy 1"
4. Click download: "File đang được tải..."

---

#### Demo 3: Preview file (30 giây)

**Nói:**

> "Trước khi tải, người dùng có thể xem trước nội dung file."

**Thao tác:**

1. Trong kết quả tìm kiếm → Click vào file ảnh
2. "Đây là preview của file, giúp người dùng xác nhận đúng file cần tải"

---

#### Demo 4: Chế độ Relay (nếu có thời gian - 1 phút)

**Nói:**

> "Cuối cùng, tôi sẽ demo chia sẻ qua Internet bằng Relay Server."

**Thao tác:**

1. Bật "Chế độ Internet"
2. Chọn file → Tạo PIN
3. "PIN này có thể chia sẻ với bất kỳ ai trên Internet"
4. (Nếu có điện thoại hoặc máy khác mạng) Nhập PIN → Download

---

### 🔹 PHẦN 5: CÔNG NGHỆ SỬ DỤNG (1 phút)

#### Slide 10: Stack công nghệ

| Thành phần       | Công nghệ                  |
| ---------------- | -------------------------- |
| **Ngôn ngữ**     | Java 21                    |
| **UI Framework** | JavaFX + FXML + CSS        |
| **Security**     | BouncyCastle, Java SSL/TLS |
| **HTTP Server**  | com.sun.net.httpserver     |
| **Build Tool**   | Maven                      |
| **Deployment**   | Render.com (Relay Server)  |

---

### 🔹 PHẦN 6: KẾT LUẬN (1-2 phút)

#### Slide 11: Kết quả đạt được

**Nói:**

> "Qua dự án này, em đã hoàn thành các mục tiêu:"

✅ Xây dựng ứng dụng chia sẻ file P2P hoàn chỉnh  
✅ Triển khai 2 chế độ: LAN (P2P) và Internet (Relay)  
✅ Tích hợp bảo mật nhiều lớp: TLS, AES, Digital Signatures  
✅ Giao diện người dùng thân thiện với JavaFX  
✅ Hệ thống PIN chia sẻ nhanh giống Send Anywhere

#### Slide 12: Hướng phát triển

| Tính năng          | Mô tả                                      |
| ------------------ | ------------------------------------------ |
| 📁 Folder sharing  | Chia sẻ cả thư mục                         |
| 🔄 Resume download | Tiếp tục download bị gián đoạn             |
| 📱 Mobile app      | Ứng dụng Android/iOS                       |
| 🌐 WebRTC          | Kết nối P2P qua Internet (không cần relay) |
| 👥 Group sharing   | Chia sẻ cho nhiều người cùng lúc           |

#### Slide 13: Trang cảm ơn

```
CẢM ƠN THẦY/CÔ VÀ CÁC BẠN ĐÃ LẮNG NGHE!

Câu hỏi?

[Tên sinh viên]
GitHub: [link nếu có]
```

---

## 💡 CÂU HỎI CÓ THỂ ĐƯỢC HỎI VÀ CÁCH TRẢ LỜI

### Q1: Tại sao chọn P2P thay vì client-server?

> **Trả lời:** "P2P có ưu điểm:
>
> -  Không cần server trung tâm (giảm chi phí)
> -  Tốc độ nhanh hơn (truyền trực tiếp)
> -  Tính riêng tư cao hơn (dữ liệu không qua bên thứ 3)
> -  Khả năng mở rộng tốt (thêm peer = thêm tài nguyên)"

### Q2: Làm sao đảm bảo bảo mật khi không có server?

> **Trả lời:** "Mỗi peer có cặp khóa RSA riêng. Khi giao tiếp:
>
> -  TLS mã hóa kênh truyền
> -  AES-256 mã hóa nội dung file
> -  Chữ ký số xác thực danh tính peer
> -  SHA-256 kiểm tra file không bị thay đổi"

### Q3: Thuật toán Flooding có nhược điểm gì?

> **Trả lời:** "Flooding có thể gây traffic lớn khi mạng có nhiều peers. Để giảm thiểu:
>
> -  Giới hạn hop count (số lần forward)
> -  Mỗi request có ID duy nhất để tránh loop
> -  Có thể cải tiến thành Gnutella hoặc Kademlia DHT cho mạng lớn"

### Q4: Tại sao cần Relay Server nếu đã có P2P?

> **Trả lời:** "P2P yêu cầu các máy phải cùng mạng LAN. Khi muốn chia sẻ qua Internet (khác mạng), cần Relay vì:
>
> -  NAT/Firewall chặn kết nối P2P trực tiếp
> -  Không có IP public để kết nối
> -  Relay hoạt động như cầu nối giữa 2 mạng khác nhau"

### Q5: PIN 6 số có an toàn không?

> **Trả lời:** "PIN 6 số có 1 triệu tổ hợp, và:
>
> -  PIN chỉ có hiệu lực 10 phút (hết hạn tự động)
> -  File vẫn được mã hóa AES-256
> -  Người dùng tự chia sẻ PIN qua kênh riêng (tin nhắn, gọi điện)
> -  Có thể mở rộng lên 8-10 số nếu cần bảo mật cao hơn"

### Q6: Giải thích về TLS handshake?

> **Trả lời:** "TLS handshake gồm các bước:
>
> 1. Client gửi supported cipher suites
> 2. Server chọn cipher suite và gửi certificate
> 3. Client verify certificate
> 4. Hai bên trao đổi key bằng Diffie-Hellman
> 5. Sau đó tất cả dữ liệu được mã hóa bằng session key"

### Q7: Cách xử lý khi peer disconnect giữa chừng?

> **Trả lời:** "Khi heartbeat không nhận được trong 15 giây, peer được đánh dấu offline và xóa khỏi danh sách. Nếu đang transfer file, có thể:
>
> -  Thử kết nối lại tự động
> -  Thông báo lỗi cho user
> -  (Hướng phát triển) Resume download từ vị trí đã tải"

---

## 📝 CHECKLIST TRƯỚC KHI TRÌNH BÀY

### Phần cứng

-  [ ] Laptop có pin đầy hoặc cắm sạc
-  [ ] Cáp HDMI/VGA (nếu cần)
-  [ ] USB backup (phòng trường hợp)

### Phần mềm

-  [ ] Ứng dụng đã build và chạy được
-  [ ] File demo đã chuẩn bị
-  [ ] IntelliJ/VS Code mở sẵn để show code (nếu cần)

### Mạng

-  [ ] 2 máy tính cùng mạng WiFi (cho demo P2P)
-  [ ] Relay server đang chạy (nếu demo Internet mode)
-  [ ] Test thử tất cả demo 1 lần trước khi trình bày

### Slides

-  [ ] Slides đã hoàn thiện
-  [ ] Font chữ đọc được (cỡ 24+)
-  [ ] Sơ đồ/hình ảnh rõ ràng

### Tâm lý

-  [ ] Thư giãn, tự tin
-  [ ] Nắm rõ flow trình bày
-  [ ] Chuẩn bị câu trả lời cho câu hỏi phổ biến

---

## 🎬 TIMELINE TRÌNH BÀY (15 phút)

| Thời gian     | Nội dung            | Ghi chú     |
| ------------- | ------------------- | ----------- |
| 0:00 - 0:30   | Giới thiệu bản thân | Slide 1     |
| 0:30 - 2:30   | Vấn đề & Tính năng  | Slide 2-3   |
| 2:30 - 6:00   | Kiến trúc kỹ thuật  | Slide 4-7   |
| 6:00 - 8:30   | Bảo mật             | Slide 8-9   |
| 8:30 - 12:30  | **Demo trực tiếp**  | Thực hành   |
| 12:30 - 13:00 | Công nghệ sử dụng   | Slide 10    |
| 13:00 - 14:30 | Kết luận & Hướng PT | Slide 11-12 |
| 14:30 - 15:00 | Cảm ơn & Q&A        | Slide 13    |

---

_Chúc bạn trình bày thành công! 🎉_
