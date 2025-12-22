# FILE ROLES — Mô tả nhiệm vụ từng file chính (Tiếng Việt)

Tập tin dưới đây mô tả nhiệm vụ ngắn gọn của các file/đơn vị chính trong dự án `P2PShareFile`.

---

## Root / cấu hình

-  `Dockerfile`: Docker image build cho relay hoặc app (nếu sử dụng container).
-  `pom.xml`: Cấu hình Maven, dependencies và build.
-  `mvnw`, `mvnw.cmd`: Maven wrapper để build trên máy không có Maven cài sẵn.
-  `render.yaml`: Cấu hình deploy lên Render (nếu dùng).
-  Các hướng dẫn `.md` (ví dụ `RELAY_README.md`, `SECURITY_README.md`, `RELAY_COMPLETE_GUIDE.md`, v.v.): tài liệu triển khai, hướng dẫn bảo mật, guide relay.
-  `test_relay_file.txt`: file test mẫu dùng khi test relay.

## `src/main/java/module-info.java`

-  Mô tả module Java cho ứng dụng (module system declarations).

## Entry point

-  `MainApplication.java`: Lớp `Application` của JavaFX — khởi tạo UI, load FXML và start app.

## Controller

-  `controller/MainController.java`: Controller chính cho `main-view.fxml` — xử lý UI events, điều phối các service (P2PService, PIN, Transfer, Preview), bind dữ liệu với view.

## compression

-  `compression/FileCompression.java`: Hàm nén/giải nén (GZIP) trước/sau truyền file.

## model (một số lớp chính)

-  `model/FileInfo.java`: Mô tả metadata file (tên, đường dẫn, kích thước, hash, owner).
-  `model/PeerInfo.java`: Thông tin peer (peerId, displayName, IP, port, publicKey, v.v.).
-  `model/PreviewContent.java`: Kết quả preview (thumbnail, mime, text excerpt).
-  `model/PreviewManifest.java`: Metadata cho preview (loại, size, pages).
-  `model/RelayFileInfo.java`: Thông tin file trên relay (uploadId, url, expiry).
-  `model/RelayTransferProgress.java`: Trạng thái tiến trình upload/download qua relay.
-  `model/RelayUploadRequest.java`: DTO cho request upload lên relay.
-  `model/SearchRequest.java`: DTO request tìm kiếm file (requestId, originPeerId, keyword, hopCount).
-  `model/SearchResponse.java`: DTO response tìm kiếm (list `FileInfo`).
-  `model/ShareSession.java`: Session chia sẻ file (PIN, danh sách file, expiry).
-  `model/SignedMessage.java`: Structure message có chữ ký số để xác thực (type, payload, signature).

## network

-  `network/PeerDiscovery.java`: Cơ chế khám phá peer trong LAN (quét subnet, mở `SSLServerSocket`, handshake, JOIN/HEARTBEAT messages) và quản lý danh sách peers.
-  `network/FileSearchService.java`: Dịch vụ tìm kiếm file trong P2P (flooding), quản lý `processedRequests`, forward request và trả về `SearchResponse`.
-  `network/FileTransferService.java`: Truyền file giữa peers (server socket + client), chunked transfer, nén + mã hoá AES, verify hash, và fallback sang relay nếu cần.
-  `network/RelayClient.java`: HTTP client để tương tác với `RelayServer` (upload chunks, download, lookup PIN, check status), kèm progress listener.
-  `network/RelayConfig.java`: Cấu hình kết nối relay (server URL, timeouts, auth keys).

## relay

-  `relay/RelayServer.java`: HTTP server đơn giản (com.sun.net.httpserver) phục vụ upload/download, PIN registry, file registry và cleanup; endpoint API cho relay.
-  `relay/PeerRegistry.java`: Registry peer trên relay (dùng để đăng ký peer, heartbeat qua HTTP nếu app đăng ký relay).
-  `relay/RelayStarter.java`: Helper/entry để khởi động relay server (local dev) hoặc config bootstrap relay.
-  (nếu có) `StandaloneRelayServer.java`: Phiên bản chạy độc lập của relay server.

## security

-  `security/SecurityManager.java`: Quản lý keypair (RSA/ECDSA), tạo self-signed certificate, tạo `SSLContext`, ký & verify messages, quản lý truststore/trusted keys.
-  `security/AESEncryption.java`: Utilities mã hóa/giải mã AES (tạo key, IV, encrypt/decrypt helpers), chuyển đổi key ↔ string.
-  `security/FileHashUtil.java`: Tính toán hash (SHA-256) cho file để verify integrity.
-  `security/SecurityManagerTest.java`: Unit test cho `SecurityManager` (nếu có).

## service

-  `service/P2PService.java`: Facade/cầu nối — khởi tạo & quản lý các thành phần (SecurityManager, PeerDiscovery, FileSearchService, FileTransferService, PINCodeService, PreviewService) và cung cấp API cho `MainController`.
-  `service/PINCodeService.java`: Quản lý PIN (tạo PIN 6 số, pin registry local/global, sync PIN qua TLS hoặc relay, expiry management), lắng nghe PIN từ peers.
-  `service/PreviewCacheService.java`: Cache kết quả preview để tránh tốn thời gian tạo lại preview.
-  `service/PreviewGenerator.java`: Sinh preview cho image/PDF/text/archives.
-  `service/PreviewService.java`: API cao cấp cho UI gọi để lấy/hiện preview.

## test (demo / helper)

-  `test/FakeJoinAttackTrustAll.java`: Test/demo mô phỏng tấn công JOIN hoặc test trust-all behavior (cần review để xóa nếu production).
-  `test/RelayDemo.java`: Demo upload/download qua relay.
-  `test/UltraViewDemo.java`: Demo preview/ultraview features.

## resources (UI)

-  `resources/org/example/p2psharefile/main-view.fxml`: FXML layout chính (3 tabs: Code, File, Tìm), chứa các `fx:id` được `MainController` bind.
-  `resources/org/example/p2psharefile/styles.css`: CSS styles cho JavaFX UI (pin digits, dropzone, file-chip, toolbar, etc.).

## target (build outputs)

-  `target/`: Thư mục Maven build output — compiled classes, resources, generated-sources, maven-status; không commit thay đổi này.

---

Ghi chú:

-  Các file được liệt kê ở mức module/feature chính để bạn dễ tham chiếu. Nếu muốn tôi tạo một danh sách đầy đủ (mỗi file trên toàn bộ repo, từng dòng) tôi sẽ thực hiện và lưu vào `FILE_ROLES_FULL_VI.md`.
-  Muốn tôi cập nhật `TECHNICAL_OVERVIEW_VI.md` / `PRESENTATION_SCRIPT_VI.md` để chèn phần "Hybrid / Bootstrap" + link đến các file mã nguồn cụ thể không?
