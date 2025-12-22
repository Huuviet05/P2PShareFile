# FILE ROLES FULL — Mô tả nhiệm vụ và mục đích từng file (Tiếng Việt)

Tệp này mở rộng `FILE_ROLES_VI.md` bằng cách liệt kê từng file/chức năng chính và ghi rõ mục đích ngắn cho mỗi file.

---

## Root / cấu hình

-  Dockerfile
   -  Mục đích: build Docker image cho relay server hoặc môi trường dev.
-  pom.xml
   -  Mục đích: cấu hình Maven (dependencies, plugins, build lifecycle).
-  mvnw / mvnw.cmd
   -  Mục đích: Maven wrapper để chạy lệnh maven mà không cần cài Maven global.
-  render.yaml
   -  Mục đích: cấu hình deploy trên Render.com (service, healthcheck, env vars).
-  Các README / GUIDE .md
   -  Mục đích: hướng dẫn triển khai, cấu hình và bảo mật.

## Entry / App

-  src/main/java/org/example/p2psharefile/MainApplication.java
   -  Mục đích: JavaFX entrypoint; khởi tạo Stage/Scene, load FXML, khởi tạo `P2PService`.

## Controller

-  src/main/java/org/example/p2psharefile/controller/MainController.java
   -  Mục đích: xử lý tương tác UI, bind dữ liệu, gọi API từ `P2PService` và cập nhật view.

## Compression

-  src/main/java/org/example/p2psharefile/compression/FileCompression.java
   -  Mục đích: API nén (GZIP) và giải nén file trước/sau truyền để tiết kiệm băng thông.

## Models (chi tiết)

-  src/main/java/org/example/p2psharefile/model/FileInfo.java
   -  Mục đích: lưu metadata file (name, size, hash, owner) để truyền qua network và hiển thị UI.
-  src/main/java/org/example/p2psharefile/model/PeerInfo.java
   -  Mục đích: thông tin peer (id, ip, port, publicKey) dùng để kết nối/TLS/verify.
-  src/main/java/org/example/p2psharefile/model/PreviewContent.java
   -  Mục đích: chứa kết quả preview (thumbnail, excerpt) để UI hiển thị.
-  src/main/java/org/example/p2psharefile/model/PreviewManifest.java
   -  Mục đích: metadata cho preview (số trang, kích thước), hỗ trợ caching.
-  src/main/java/org/example/p2psharefile/model/RelayFileInfo.java
   -  Mục đích: mô tả file trên relay (uploadId, downloadUrl, expiry) cho client.
-  src/main/java/org/example/p2psharefile/model/RelayTransferProgress.java
   -  Mục đích: theo dõi tiến trình upload/download (chunk, bytes) và truyền cho UI.
-  src/main/java/org/example/p2psharefile/model/RelayUploadRequest.java
   -  Mục đích: DTO chứa thông tin upload (senderId, fileHash, encrypted flag).
-  src/main/java/org/example/p2psharefile/model/SearchRequest.java
   -  Mục đích: request tìm kiếm phân tán (requestId, originPeerId, keyword, hopCount).
-  src/main/java/org/example/p2psharefile/model/SearchResponse.java
   -  Mục đích: trả về danh sách `FileInfo` tìm thấy cho origin.
-  src/main/java/org/example/p2psharefile/model/ShareSession.java
   -  Mục đích: quản lý phiên chia sẻ (PIN, file list, expiry time).
-  src/main/java/org/example/p2psharefile/model/SignedMessage.java
   -  Mục đích: wrapper message có chữ ký số dùng cho JOIN/HEARTBEAT/PIN để verify integrity.

## Network / Core

-  src/main/java/org/example/p2psharefile/network/PeerDiscovery.java
   -  Mục đích: phát hiện peers trong LAN bằng TLS connections (SSLSocket/SSLServerSocket), gửi/nhận JOIN & HEARTBEAT, duy trì danh sách peers online.
-  src/main/java/org/example/p2psharefile/network/FileSearchService.java
   -  Mục đích: thực hiện tìm kiếm phân tán (flooding), tránh lặp bằng `processedRequests`, forward và trả kết quả.
-  src/main/java/org/example/p2psharefile/network/FileTransferService.java
   -  Mục đích: cung cấp server/client transfer cho file P2P (SSLSocket), chunked transfer, nén + AES encrypt, verify hash, và hỗ trợ fallback sang relay.
-  src/main/java/org/example/p2psharefile/network/RelayClient.java
   -  Mục đích: client HTTP để upload/download file lên/kéo từ `RelayServer`, hỗ trợ chunking, resume, encryption client-side.
-  src/main/java/org/example/p2psharefile/network/RelayConfig.java
   -  Mục đích: cấu hình relay client (serverUrl, chunkSize, retry policy, timeouts).

## Relay / Cloud

-  src/main/java/org/example/p2psharefile/relay/RelayServer.java
   -  Mục đích: HTTP server (com.sun.net.httpserver) cung cấp endpoints upload/download/pin/register/search và lưu file tạm thời.
-  src/main/java/org/example/p2psharefile/relay/PeerRegistry.java
   -  Mục đích: lưu trữ peer đăng ký với relay (peerId, ip, lastSeen) để hỗ trợ cloud-assisted discovery.
-  src/main/java/org/example/p2psharefile/relay/RelayStarter.java
   -  Mục đích: helper để khởi động relay local cho development và cấu hình `P2PService` để dùng relay.
-  src/main/java/org/example/p2psharefile/relay/StandaloneRelayServer.java (nếu có)
   -  Mục đích: entrypoint để chạy relay server độc lập.

## Security

-  src/main/java/org/example/p2psharefile/security/SecurityManager.java
   -  Mục đích: quản lý keypair, tạo self-signed cert, xây dựng SSLContext, ký và verify messages.
-  src/main/java/org/example/p2psharefile/security/AESEncryption.java
   -  Mục đích: utilities mã hoá/giải mã AES (tạo key, IV, encrypt/decrypt helpers).
-  src/main/java/org/example/p2psharefile/security/FileHashUtil.java
   -  Mục đích: tính toán SHA-256 cho file để verify integrity sau truyền.
-  src/main/java/org/example/p2psharefile/security/SecurityManagerTest.java
   -  Mục đích: unit tests cho các chức năng của `SecurityManager`.

## Services / Business

-  src/main/java/org/example/p2psharefile/service/P2PService.java
   -  Mục đích: facade chính; khởi tạo và điều phối `SecurityManager`, `PeerDiscovery`, `FileSearchService`, `FileTransferService`, `PINCodeService`, `PreviewService` và cung cấp API cho UI.
-  src/main/java/org/example/p2psharefile/service/PINCodeService.java
   -  Mục đích: tạo và quản lý PIN quick-share 6 chữ số, sync PIN qua TLS hoặc relay, quản lý expiry và listener callbacks.
-  src/main/java/org/example/p2psharefile/service/PreviewCacheService.java
   -  Mục đích: cache kết quả preview để tránh tạo lại tốn thời gian/IO.
-  src/main/java/org/example/p2psharefile/service/PreviewGenerator.java
   -  Mục đích: logic tạo thumbnail/preview cho image, PDF, text, archive.
-  src/main/java/org/example/p2psharefile/service/PreviewService.java
   -  Mục đích: API cao cấp cho UI để lấy preview, kết hợp cache và generator.

## Tests / Demos

-  src/main/java/org/example/p2psharefile/test/FakeJoinAttackTrustAll.java
   -  Mục đích: test/demo mô phỏng message giả mạo (dùng để kiểm tra bảo mật); không dùng trong production.
-  src/main/java/org/example/p2psharefile/test/RelayDemo.java
   -  Mục đích: kịch bản demo upload/download qua relay.
-  src/main/java/org/example/p2psharefile/test/UltraViewDemo.java
   -  Mục đích: demo khả năng preview và UI liên quan.

## UI Resources

-  src/main/resources/org/example/p2psharefile/main-view.fxml
   -  Mục đích: layout FXML chính (3 tabs) cấu hình giao diện.
-  src/main/resources/org/example/p2psharefile/styles.css
   -  Mục đích: styles cho JavaFX (pin digits, dropzone, các class tùy chỉnh).

## Build output

-  target/
   -  Mục đích: thư mục build output của Maven (classes, resources). Không commit.

---

Nếu bạn muốn, tôi có thể:

-  chèn nội dung này vào `FILE_ROLES_VI.md` (thay thế hoặc mở rộng), hoặc
-  tạo một bảng tóm tắt ports & protocol mapping (`PORTS_AND_PROTOCOLS.md`).

Bạn muốn tôi làm gì tiếp theo?
