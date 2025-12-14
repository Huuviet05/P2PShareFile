# UltraView Architecture Diagram

## System Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          P2P ShareFile - UltraView                          │
│                     Preview trước khi download (P2P)                        │
└─────────────────────────────────────────────────────────────────────────────┘

┌────────────────┐                                        ┌────────────────┐
│   Peer A       │                                        │   Peer B       │
│   (Owner)      │◄──────────── TLS Channel ────────────►│  (Requester)   │
└────────────────┘                                        └────────────────┘
```

## Component Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              P2PService (Facade)                            │
├─────────────────────────────────────────────────────────────────────────────┤
│  • Tích hợp tất cả services                                                 │
│  • Cung cấp API đơn giản cho UI                                             │
│  • Quản lý lifecycle (start/stop)                                           │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
        ┌───────────────────────────┼───────────────────────────┐
        │                           │                           │
        ▼                           ▼                           ▼
┌───────────────┐        ┌──────────────────┐        ┌──────────────────┐
│ FileTransfer  │        │ PreviewCache     │        │ PreviewService   │
│ Service       │        │ Service          │        │                  │
├───────────────┤        ├──────────────────┤        ├──────────────────┤
│ • Transfer    │        │ • Manifest cache │        │ • TLS Server     │
│   full files  │        │ • Content cache  │        │   (port+100)     │
│ • TLS + AES   │        │ • File cache     │        │ • GET_MANIFEST   │
│ • Port: auto  │        │ • Sign manifest  │        │ • GET_CONTENT    │
└───────────────┘        └──────────────────┘        │ • Verify sig     │
                                  │                   └──────────────────┘
                                  │
                                  ▼
                         ┌──────────────────┐
                         │ PreviewGenerator │
                         ├──────────────────┤
                         │ • Generate       │
                         │   thumbnails     │
                         │ • Extract text   │
                         │ • List archives  │
                         │ • Calculate hash │
                         └──────────────────┘
```

## Data Models

```
┌──────────────────────────────────────────────────────────────────────────┐
│                            PreviewManifest                                │
├──────────────────────────────────────────────────────────────────────────┤
│ fileHash: String                 (SHA-256)                                │
│ fileName: String                                                          │
│ fileSize: long                                                            │
│ mimeType: String                                                          │
│ lastModified: long                                                        │
│ ┌────────────────────────────────────────────────────────────────┐       │
│ │ availableTypes: Set<PreviewType>                               │       │
│ │   • THUMBNAIL                                                  │       │
│ │   • TEXT_SNIPPET                                               │       │
│ │   • ARCHIVE_LISTING                                            │       │
│ │   • PDF_PAGES                                                  │       │
│ │   • AUDIO_SAMPLE                                               │       │
│ │   • VIDEO_PREVIEW                                              │       │
│ │   • METADATA_ONLY                                              │       │
│ │   • FIRST_CHUNK                                                │       │
│ └────────────────────────────────────────────────────────────────┘       │
│ previewHashes: Map<PreviewType, String>                                  │
│ previewSizes: Map<PreviewType, Long>                                     │
│ snippet: String                                                           │
│ archiveListing: List<String>                                             │
│ metadata: Map<String, String>                                            │
│ ┌────────────────────────────────────────────────────────────────┐       │
│ │ Security                                                       │       │
│ │   allowPreview: boolean                                        │       │
│ │   trustedPeersOnly: Set<String>                                │       │
│ │   ownerPeerId: String                                          │       │
│ │   signature: String (ECDSA)                                    │       │
│ │   timestamp: long                                              │       │
│ └────────────────────────────────────────────────────────────────┘       │
└──────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────┐
│                            PreviewContent                                 │
├──────────────────────────────────────────────────────────────────────────┤
│ fileHash: String                                                          │
│ type: PreviewType                                                         │
│ data: byte[]                     (actual preview data)                    │
│ dataHash: String                                                          │
│ format: String                   (jpg, png, txt, etc.)                    │
│ timestamp: long                                                           │
│ ┌────────────────────────────────────────────────────────────────┐       │
│ │ Type-specific metadata                                         │       │
│ │   width: int                   (for images/video)              │       │
│ │   height: int                                                  │       │
│ │   duration: int                (for audio/video - seconds)     │       │
│ │   encoding: String             (UTF-8 for text)                │       │
│ └────────────────────────────────────────────────────────────────┘       │
└──────────────────────────────────────────────────────────────────────────┘
```

## Flow Diagrams

### Owner Flow: Share File với Preview

```
┌─────────┐
│  User   │
│ (Owner) │
└────┬────┘
     │
     │ 1. Share file
     ▼
┌──────────────────┐
│  P2PService      │
│  .addSharedFile()│
└────┬─────────────┘
     │
     │ 2. Generate preview
     ▼
┌──────────────────────────┐
│  PreviewCacheService     │
│  .getOrCreateManifest()  │
└────┬─────────────────────┘
     │
     │ 3. Detect file type & generate
     ▼
┌──────────────────────────┐
│  PreviewGenerator        │
│  .generateManifest()     │
├──────────────────────────┤
│  • Calculate SHA-256     │
│  • Detect MIME type      │
│  • Generate preview:     │
│    - Image → Thumbnail   │
│    - Text → Snippet      │
│    - Archive → Listing   │
└────┬─────────────────────┘
     │
     │ 4. Sign manifest
     ▼
┌──────────────────────────┐
│  SecurityManager         │
│  .signMessage()          │
├──────────────────────────┤
│  dataToSign:             │
│  hash|name|size|mime|    │
│  timestamp|ownerId       │
│                          │
│  signature = Sign(       │
│    dataToSign,           │
│    privateKey            │
│  )                       │
└────┬─────────────────────┘
     │
     │ 5. Cache manifest
     ▼
┌──────────────────────────┐
│  Cache                   │
├──────────────────────────┤
│  manifestCache[hash]     │
│  fileCache[hash]         │
└──────────────────────────┘
     │
     ▼
  Ready for
  requests!
```

### Requester Flow: Request Preview

```
┌──────────┐
│   User   │
│(Requester)
└────┬─────┘
     │
     │ 1. Click "Preview"
     ▼
┌────────────────────────────┐
│  P2PService                │
│  .requestPreviewManifest() │
└────┬───────────────────────┘
     │
     │ 2. Send request via TLS
     ▼
┌─────────────────────────────────────────────────────────────┐
│                    TLS Channel                               │
│  Requester ──────────────────────────────────► Owner        │
│            ◄────────────────────────────────── (port+100)    │
└─────────────────────────────────────────────────────────────┘
     │
     │ 3. Request manifest
     ▼
┌──────────────────────────┐         ┌──────────────────────┐
│  PreviewService          │         │  PreviewService      │
│  (Requester)             │         │  (Owner)             │
├──────────────────────────┤         ├──────────────────────┤
│  requestManifest()       │────────►│  handleGetManifest() │
│                          │         │  • Check permission  │
│                          │         │  • Return manifest   │
│                          │◄────────│                      │
│  Verify signature ✓      │         │                      │
└──────────────────────────┘         └──────────────────────┘
     │
     │ 4. Manifest received & verified
     ▼
  Has THUMBNAIL?
     │ Yes
     │ 5. Request thumbnail content
     ▼
┌──────────────────────────┐         ┌──────────────────────┐
│  PreviewService          │         │  PreviewService      │
│  (Requester)             │         │  (Owner)             │
├──────────────────────────┤         ├──────────────────────┤
│  requestContent(         │────────►│  handleGetContent()  │
│    fileHash,             │         │  • Check permission  │
│    THUMBNAIL             │         │  • Generate/get      │
│  )                       │         │    from cache        │
│                          │◄────────│  • Return content    │
│                          │         │                      │
└──────────────────────────┘         └──────────────────────┘
     │
     │ 6. Content received (byte[])
     ▼
┌──────────────────────────┐
│  Display in UI           │
├──────────────────────────┤
│  byte[] → BufferedImage  │
│  Show in dialog/panel    │
└──────────────────────────┘
```

## Security Flow

```
┌────────────────────────────────────────────────────────────────────┐
│                      Signature Verification                        │
└────────────────────────────────────────────────────────────────────┘

Owner Side (Sign):                     Requester Side (Verify):

┌──────────────────┐                   ┌──────────────────┐
│ PreviewManifest  │                   │ PreviewManifest  │
└────┬─────────────┘                   └────┬─────────────┘
     │                                      │
     │ getDataToSign()                      │ getDataToSign()
     │                                      │
     ▼                                      ▼
fileHash|fileName|                    fileHash|fileName|
fileSize|mimeType|                    fileSize|mimeType|
timestamp|ownerId                     timestamp|ownerId
     │                                      │
     │                                      │
     ▼                                      ▼
┌──────────────────┐                   ┌──────────────────┐
│ SecurityManager  │                   │ SecurityManager  │
│ .signMessage()   │                   │ .verifySignature()│
└────┬─────────────┘                   └────┬─────────────┘
     │                                      │
     │ Sign with                            │ Verify with
     │ ECDSA private key                    │ owner's public key
     │                                      │
     ▼                                      ▼
signature: String                      valid: boolean
(Base64 encoded)                       (true/false)
     │                                      │
     │                                      │
     ▼                                      ▼
manifest.setSignature()                if (!valid) reject()
     │                                 else accept()
     ▼
Ready to send
```

## Preview Types Matrix

```
┌──────────────────────────────────────────────────────────────────────┐
│                        Preview Type Support                          │
├────────────┬────────────────┬──────────────────┬─────────────────────┤
│ File Type  │ Extensions     │ Preview Type     │ Output              │
├────────────┼────────────────┼──────────────────┼─────────────────────┤
│ Image      │ jpg, png, gif, │ THUMBNAIL        │ 200x200 JPEG        │
│            │ bmp, webp      │                  │ ~5-20KB             │
├────────────┼────────────────┼──────────────────┼─────────────────────┤
│ Text       │ txt, java, py, │ TEXT_SNIPPET     │ First 10 lines      │
│            │ js, md, html,  │                  │ or 500 chars        │
│            │ css, json, xml │                  │ UTF-8, ~500B-2KB    │
├────────────┼────────────────┼──────────────────┼─────────────────────┤
│ Archive    │ zip, jar, war  │ ARCHIVE_LISTING  │ File list + sizes   │
│            │                │                  │ UTF-8, ~1-10KB      │
├────────────┼────────────────┼──────────────────┼─────────────────────┤
│ Audio      │ mp3, wav, ogg, │ METADATA_ONLY    │ Name, size, type    │
│            │ flac, m4a      │                  │ ~100 bytes          │
├────────────┼────────────────┼──────────────────┼─────────────────────┤
│ Video      │ mp4, avi, mkv, │ METADATA_ONLY    │ Name, size, type    │
│            │ mov, webm, flv │                  │ ~100 bytes          │
├────────────┼────────────────┼──────────────────┼─────────────────────┤
│ PDF        │ pdf            │ (Not impl)       │ Future: Page thumbs │
├────────────┼────────────────┼──────────────────┼─────────────────────┤
│ Other      │ *              │ METADATA_ONLY    │ Name, size, hash    │
└────────────┴────────────────┴──────────────────┴─────────────────────┘
```

## Cache Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                     PreviewCacheService                              │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │ Manifest Cache                                             │    │
│  │ ConcurrentHashMap<FileHash, PreviewManifest>               │    │
│  │                                                            │    │
│  │ Key: "3a7f9b2c..." (SHA-256)                               │    │
│  │ Value: PreviewManifest {                                   │    │
│  │   availableTypes: [THUMBNAIL, TEXT_SNIPPET],               │    │
│  │   signature: "MEUCIQDx...",                                │    │
│  │   ...                                                      │    │
│  │ }                                                          │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │ Content Cache                                              │    │
│  │ ConcurrentHashMap<FileHash_Type, PreviewContent>           │    │
│  │                                                            │    │
│  │ Key: "3a7f9b2c..._THUMBNAIL"                               │    │
│  │ Value: PreviewContent {                                    │    │
│  │   data: byte[15360],                                       │    │
│  │   format: "jpg",                                           │    │
│  │   width: 200, height: 150                                  │    │
│  │ }                                                          │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │ File Cache                                                 │    │
│  │ ConcurrentHashMap<FileHash, File>                          │    │
│  │                                                            │    │
│  │ Key: "3a7f9b2c..."                                         │    │
│  │ Value: File("C:/path/to/vacation.jpg")                     │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

## Network Protocol

```
┌──────────────────────────────────────────────────────────────────────┐
│                   Preview Request/Response Protocol                  │
│                          (over TLS)                                  │
└──────────────────────────────────────────────────────────────────────┘

GET_MANIFEST Request:
┌────────────────────────────────────┐
│ PreviewRequest                     │
├────────────────────────────────────┤
│ type: GET_MANIFEST                 │
│ fileHash: "3a7f9b2c..."            │
│ requesterId: "peer-uuid-xyz"       │
└────────────────────────────────────┘
          │
          │ TLS Socket (port+100)
          ▼
┌────────────────────────────────────┐
│ PreviewResponse                    │
├────────────────────────────────────┤
│ success: true                      │
│ manifest: PreviewManifest {        │
│   fileHash: "3a7f9b2c...",         │
│   fileName: "vacation.jpg",        │
│   availableTypes: [THUMBNAIL],     │
│   signature: "MEUCIQDx..."         │
│ }                                  │
└────────────────────────────────────┘

GET_CONTENT Request:
┌────────────────────────────────────┐
│ PreviewRequest                     │
├────────────────────────────────────┤
│ type: GET_CONTENT                  │
│ fileHash: "3a7f9b2c..."            │
│ previewType: THUMBNAIL             │
│ requesterId: "peer-uuid-xyz"       │
└────────────────────────────────────┘
          │
          │ TLS Socket (port+100)
          ▼
┌────────────────────────────────────┐
│ PreviewResponse                    │
├────────────────────────────────────┤
│ success: true                      │
│ content: PreviewContent {          │
│   type: THUMBNAIL,                 │
│   data: byte[15360],               │
│   format: "jpg",                   │
│   width: 200, height: 150          │
│ }                                  │
└────────────────────────────────────┘
```

## Permission Control Flow

```
┌────────────────────────────────────────────────────────────────────┐
│              Preview Permission Check (Server Side)                │
└────────────────────────────────────────────────────────────────────┘

                      Request received
                            │
                            ▼
                   ┌─────────────────┐
                   │ Get manifest    │
                   └────────┬────────┘
                            │
                            ▼
                   ┌─────────────────┐
              ┌────┤ allowPreview?   │
              │    └─────────────────┘
              │            │ Yes
              │            ▼
              │    ┌─────────────────┐
              │    │ trustedPeersOnly│
              │    │ != null?        │
              │    └────────┬────────┘
              │             │
              │     ┌───────┴───────┐
              │     │ Yes           │ No
              │     ▼               ▼
              │  ┌─────────┐  ┌─────────┐
              │  │ Check   │  │ Allow   │
              │  │ peer ID │  │ all     │
              │  │ in set? │  │ peers   │
              │  └────┬────┘  └────┬────┘
              │       │            │
              │   ┌───┴───┐        │
              │   │Yes│No │        │
              │   ▼   ▼   ▼        │
              │  Allow  Deny       │
              │   │      │         │
              │   └──┬───┘         │
              │      │             │
              │      ▼             │
              │  Serve preview ◄───┘
              │
              │ No
              ▼
         ┌─────────┐
         │ Reject  │
         │ request │
         └─────────┘
```

---

**Legend:**

-  `┌─┐ └─┘` = Boxes/Components
-  `│ ─` = Connections/Flow
-  `▼ ►` = Direction arrows
-  `◄ ►` = Bidirectional communication
