# UltraView - Quick Start Guide

## 🚀 Giới thiệu nhanh

UltraView cho phép xem preview file (thumbnail, text snippet, archive listing) trước khi download trong mạng P2P.

## 📋 Cài đặt

Không cần cài đặt thêm - tính năng đã được tích hợp sẵn vào P2PService.

## 💡 Sử dụng cơ bản

### 1. Owner - Chia sẻ file với preview

```java
// Khởi tạo service
P2PService p2pService = new P2PService("My Peer", 0);
p2pService.start();

// Thêm file - preview tự động được tạo
File imageFile = new File("vacation.jpg");
p2pService.addSharedFile(imageFile);

// Preview đã sẵn sàng cho peer khác request!
```

### 2. Requester - Xem preview trước khi download

```java
// Sau khi search và nhận SearchResponse
PeerInfo peer = searchResponse.getSourcePeer();
FileInfo fileInfo = searchResponse.getFoundFiles().get(0);
String fileHash = fileInfo.getFileHash();

// Bước 1: Request manifest
PreviewManifest manifest = p2pService.requestPreviewManifest(peer, fileHash);

if (manifest != null && manifest.hasPreviewType(PreviewManifest.PreviewType.THUMBNAIL)) {
    // Bước 2: Request thumbnail content
    PreviewContent content = p2pService.requestPreviewContent(
        peer,
        fileHash,
        PreviewManifest.PreviewType.THUMBNAIL
    );

    // Bước 3: Hiển thị trong UI
    byte[] imageData = content.getData();
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
    // ... display image in UI
}
```

## 🎨 Preview Types

### Image Files (jpg, png, gif, bmp, webp)

```java
PreviewManifest.PreviewType.THUMBNAIL
// → Ảnh thu nhỏ 200x200px
```

### Text Files (txt, java, py, js, md, html, css, json, xml)

```java
PreviewManifest.PreviewType.TEXT_SNIPPET
// → 10 dòng đầu hoặc 500 ký tự

String snippet = new String(content.getData(), StandardCharsets.UTF_8);
System.out.println(snippet);
```

### Archive Files (zip, jar, war)

```java
PreviewManifest.PreviewType.ARCHIVE_LISTING
// → Danh sách file trong archive

String listing = new String(content.getData(), StandardCharsets.UTF_8);
System.out.println(listing);
```

## 🔒 Security

### Disable preview cho file nhạy cảm

```java
PreviewManifest manifest = p2pService.getOrCreatePreviewManifest(file);
manifest.setAllowPreview(false);  // Không cho preview
```

### Chỉ cho trusted peers xem preview

```java
PreviewManifest manifest = p2pService.getOrCreatePreviewManifest(file);
Set<String> trustedPeers = new HashSet<>();
trustedPeers.add("peer-id-1");
trustedPeers.add("peer-id-2");
manifest.setTrustedPeersOnly(trustedPeers);
```

## 🧪 Test Demo

### Chạy Owner Peer

```bash
cd v:\LapTrinhMang\P2PShareFile
mvn compile
java -cp target/classes org.example.p2psharefile.test.UltraViewDemo owner
```

### Chạy Requester Peer (terminal mới)

```bash
cd v:\LapTrinhMang\P2PShareFile
java -cp target/classes org.example.p2psharefile.test.UltraViewDemo requester
```

## 📊 File Support

| Type        | Extensions            | Preview           | Size      |
| ----------- | --------------------- | ----------------- | --------- |
| Image       | jpg, png, gif, bmp    | Thumbnail 200x200 | ~5-20KB   |
| Text        | txt, java, py, js, md | 10 dòng đầu       | ~500B-2KB |
| Archive     | zip, jar              | File listing      | ~1-10KB   |
| Audio/Video | mp3, mp4, avi         | Metadata only     | ~100B     |

## ⚡ Performance

-  Preview generation: ~100-300ms
-  Network transfer: ~100-500ms (LAN)
-  Max file size: 100MB (không preview nếu lớn hơn)

## ❓ Troubleshooting

### Preview không hiển thị?

1. ✅ Check file size < 100MB
2. ✅ Check file type có trong supported list
3. ✅ Check allowPreview = true
4. ✅ Check network connection

### Signature verification failed?

1. ✅ Check peer's public key
2. ✅ Check manifest không bị modify
3. ✅ Check system time sync

## 📚 Xem thêm

-  [ULTRAVIEW_README.md](ULTRAVIEW_README.md) - Full documentation
-  [ULTRAVIEW_IMPLEMENTATION.md](ULTRAVIEW_IMPLEMENTATION.md) - Implementation details

## 🎯 Next Steps

1. Tích hợp vào JavaFX UI
2. Hiển thị preview dialog
3. Add progress indicator
4. Implement lazy loading

---

**Happy previewing! 🎉**
