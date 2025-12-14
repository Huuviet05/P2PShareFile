# UltraView - Preview Feature Documentation

## Tổng quan

UltraView là tính năng preview file trước khi download trong hệ thống P2P ShareFile. Cho phép người dùng xem nhanh nội dung file (thumbnail, text snippet, archive listing, etc.) để quyết định có download hay không.

## Kiến trúc

### 1. Models

#### PreviewManifest

Chứa metadata và thông tin về các loại preview có sẵn cho một file.

**Các loại preview (PreviewType):**

-  `THUMBNAIL` - Ảnh thu nhỏ 200x200px cho image files (jpg, png, gif, bmp, webp)
-  `TEXT_SNIPPET` - Trích xuất 10 dòng đầu cho text files (txt, java, py, js, md, etc.)
-  `ARCHIVE_LISTING` - Danh sách file bên trong archive (zip, jar)
-  `PDF_PAGES` - Thumbnails của PDF pages (chưa implement)
-  `AUDIO_SAMPLE` - Audio sample 10s (chưa implement)
-  `VIDEO_PREVIEW` - Video preview GIF/low-res (chưa implement)
-  `METADATA_ONLY` - Chỉ metadata (size, mime-type, hash)
-  `FIRST_CHUNK` - Stream first N KB (chưa implement)

**Security:**

-  Manifest được ký (ECDSA signature) bởi owner
-  Có thể giới hạn preview chỉ cho trusted peers
-  Flag `allowPreview` để owner opt-out

#### PreviewContent

Chứa dữ liệu preview thực tế (byte array).

**Metadata:**

-  `format` - jpg, png, txt, etc.
-  `width/height` - cho thumbnail/video
-  `duration` - cho audio/video
-  `encoding` - UTF-8 cho text

### 2. Services

#### PreviewGenerator

Service sinh preview cho các loại file.

**Chức năng:**

-  `generateManifest(File, ownerPeerId)` - Tạo manifest với signature
-  `generatePreviewContent(File, manifest, type)` - Tạo preview content cụ thể
-  `calculateFileHash(File)` - Tính SHA-256 hash

**Giới hạn:**

-  File > 100MB không sinh preview (chỉ metadata)
-  Thumbnail size: 200x200px (giữ tỷ lệ)
-  Text snippet: max 10 dòng hoặc 500 ký tự

#### PreviewCacheService

Quản lý cache preview để tránh sinh lại.

**Cache layers:**

-  `manifestCache`: FileHash -> PreviewManifest
-  `contentCache`: FileHash_PreviewType -> PreviewContent
-  `fileCache`: FileHash -> File

#### PreviewService

Service P2P xử lý preview requests qua TLS.

**Port:** Transfer port + 100 (auto-assigned)

**Request types:**

-  `GET_MANIFEST` - Lấy manifest
-  `GET_CONTENT` - Lấy preview content

**Security:**

-  TLS cho transport
-  Verify signature của manifest
-  Check permission (trustedPeersOnly)

### 3. P2P Flow

#### Flow sinh preview (Owner side)

```
1. User thêm file vào shared files
   → P2PService.addSharedFile(file)

2. PreviewCacheService.getOrCreateManifest(file)
   → PreviewGenerator.generateManifest(file, ownerPeerId)

3. Detect file type → Generate preview type phù hợp
   - Image: Resize → JPEG thumbnail
   - Text: Read first 10 lines
   - Archive: List entries with size

4. Sign manifest với ECDSA private key
   → manifest.setSignature(signature)

5. Cache manifest & file
```

#### Flow request preview (Requester side)

```
1. User tìm kiếm file → nhận SearchResponse với preview metadata

2. User click "Preview"
   → P2PService.requestPreviewManifest(peer, fileHash)

3. PreviewService gửi GET_MANIFEST request qua TLS (port+100)

4. Owner peer:
   - Check permission (allowPreview, trustedPeersOnly)
   - Return manifest with signature

5. Requester verify signature bằng owner's public key
   → SecurityManager.verifySignature(...)

6. Nếu manifest hợp lệ, request content:
   → P2PService.requestPreviewContent(peer, fileHash, THUMBNAIL)

7. Owner peer:
   - Check permission
   - Generate hoặc lấy từ cache
   - Return PreviewContent

8. Display preview trong UI
```

## API Usage

### 1. Thêm file với preview

```java
P2PService p2pService = new P2PService("MyPeer", 0);
p2pService.start();

File file = new File("path/to/image.jpg");
p2pService.addSharedFile(file);  // Tự động tạo preview
```

### 2. Request preview từ peer

```java
// Sau khi search và có SearchResponse
PeerInfo peer = response.getSourcePeer();
FileInfo fileInfo = response.getFoundFiles().get(0);
String fileHash = fileInfo.getFileHash();

// Request manifest
PreviewManifest manifest = p2pService.requestPreviewManifest(peer, fileHash);

if (manifest != null && manifest.hasPreviewType(PreviewManifest.PreviewType.THUMBNAIL)) {
    // Request thumbnail content
    PreviewContent content = p2pService.requestPreviewContent(
        peer,
        fileHash,
        PreviewManifest.PreviewType.THUMBNAIL
    );

    if (content != null) {
        byte[] imageData = content.getData();
        // Display image in UI
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
    }
}
```

### 3. Disable preview cho file nhạy cảm

```java
PreviewManifest manifest = p2pService.getOrCreatePreviewManifest(file);
manifest.setAllowPreview(false);  // Opt-out
```

### 4. Giới hạn preview cho trusted peers

```java
PreviewManifest manifest = p2pService.getOrCreatePreviewManifest(file);
Set<String> trustedPeers = new HashSet<>();
trustedPeers.add("peer-id-1");
trustedPeers.add("peer-id-2");
manifest.setTrustedPeersOnly(trustedPeers);
```

## Security Features

### 1. Signature Verification

Mỗi manifest được ký bằng ECDSA private key của owner:

```
dataToSign = fileHash|fileName|fileSize|mimeType|timestamp|ownerPeerId
signature = Sign(dataToSign, privateKey)
```

Peer nhận verify:

```
valid = Verify(dataToSign, signature, ownerPublicKey)
```

### 2. Permission Control

-  `allowPreview` - Owner có thể disable preview
-  `trustedPeersOnly` - Giới hạn preview cho peers cụ thể

### 3. TLS Transport

Preview Service chạy trên TLS port riêng (transfer port + 100).

## File Support Matrix

| File Type | Extension                        | Preview Type    | Status                |
| --------- | -------------------------------- | --------------- | --------------------- |
| Image     | jpg, png, gif, bmp, webp         | THUMBNAIL       | ✅ Implemented        |
| Text      | txt, md, java, py, js, html, css | TEXT_SNIPPET    | ✅ Implemented        |
| Archive   | zip, jar, war                    | ARCHIVE_LISTING | ✅ Implemented        |
| Audio     | mp3, wav, ogg, flac              | METADATA_ONLY   | ⏳ Basic (expandable) |
| Video     | mp4, avi, mkv, mov               | METADATA_ONLY   | ⏳ Basic (expandable) |
| PDF       | pdf                              | PDF_PAGES       | ❌ Not implemented    |

## Limitations & Future Enhancements

### Current Limitations

1. **File size limit:** 100MB (không sinh preview cho file lớn hơn)
2. **No video/audio preview:** Chỉ metadata (có thể mở rộng với FFmpeg)
3. **No PDF preview:** Cần thư viện như Apache PDFBox
4. **No streaming preview:** Chưa hỗ trợ FIRST_CHUNK streaming

### Future Enhancements

1. **Audio sample generation**

   -  Extract first 10s
   -  Transcode to low-bitrate MP3/OGG
   -  Requires: FFmpeg Java wrapper (JAVE2, Xuggler)

2. **Video preview**

   -  Generate GIF from first 5s
   -  Or low-res MP4 preview
   -  Requires: FFmpeg

3. **PDF thumbnails**

   -  Generate thumbnail for first page
   -  Requires: Apache PDFBox

4. **Streaming preview**

   -  Stream first N KB (for text, PDF)
   -  Render incrementally in UI

5. **Smart snippet extraction**

   -  For code: Extract function/class definitions
   -  For PDF: Extract text with keyword highlighting

6. **Preview quality options**
   -  Thumbnail: 200px (default), 400px (high), 100px (low)
   -  Text: 5 lines (low), 10 (default), 20 (high)

## Performance Considerations

### Cache Strategy

-  Manifest cache: Không expire (invalidate khi file thay đổi)
-  Content cache: Có thể implement LRU eviction nếu memory cao

### Network Optimization

-  Preview request timeout: 10s
-  Thumbnail size nhỏ (~5-20KB) → download nhanh
-  Text snippet nhỏ (~500 bytes - 2KB)
-  Archive listing: Tùy số lượng file (thường < 10KB)

### Recommendations

-  Enable preview cho file < 100MB
-  Thumbnail cho image < 10MB để tránh timeout khi resize
-  Consider lazy loading trong UI (chỉ load preview khi user scroll)

## Testing

### Manual Test Cases

1. **Test Image Preview**

   ```
   - Share: image.jpg (500KB)
   - Verify: Manifest có THUMBNAIL
   - Request: Preview content
   - Verify: Image hiển thị đúng, tỷ lệ preserved
   ```

2. **Test Text Preview**

   ```
   - Share: code.java (10KB)
   - Verify: Manifest có TEXT_SNIPPET
   - Request: Preview content
   - Verify: Hiển thị 10 dòng đầu
   ```

3. **Test Archive Preview**

   ```
   - Share: archive.zip (1MB, 50 files)
   - Verify: Manifest có ARCHIVE_LISTING
   - Request: Preview content
   - Verify: List 50 files với size
   ```

4. **Test Signature Verification**

   ```
   - Share file từ Peer A
   - Request preview từ Peer B
   - Verify: Signature valid
   - Modify manifest
   - Verify: Signature invalid → reject
   ```

5. **Test Permission Control**
   ```
   - Peer A: setAllowPreview(false)
   - Peer B: Request preview
   - Verify: Request rejected
   ```

## Integration với UI (Future)

### Preview Dialog Example

```
┌─────────────────────────────────────┐
│ Preview: vacation.jpg               │
│                                     │
│  ┌─────────────────────────────┐   │
│  │                             │   │
│  │   [Thumbnail Image]         │   │
│  │       200x150px             │   │
│  │                             │   │
│  └─────────────────────────────┘   │
│                                     │
│  File: vacation.jpg                 │
│  Size: 2.5 MB                       │
│  Type: image/jpeg                   │
│  Hash: 3a7f9b...                    │
│                                     │
│  ┌─────────┐  ┌──────────────┐     │
│  │ Download│  │    Cancel    │     │
│  └─────────┘  └──────────────┘     │
└─────────────────────────────────────┘
```

## Troubleshooting

### Preview không hiển thị

1. Check file size < 100MB
2. Check file type có trong supported list
3. Check owner's allowPreview = true
4. Check network (preview port = transfer port + 100)

### Signature verification failed

1. Check owner's public key trong PeerInfo
2. Check manifest không bị modify
3. Check system time sync (signature có timestamp)

### Out of memory

1. Implement LRU cache eviction
2. Giảm THUMBNAIL_SIZE
3. Giảm MAX_SNIPPET_LINES

---

**Author:** P2P ShareFile Team  
**Version:** 1.0  
**Last Updated:** December 2025
