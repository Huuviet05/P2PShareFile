package org.example.p2psharefile.service;

import org.example.p2psharefile.model.PreviewContent;
import org.example.p2psharefile.model.PreviewManifest;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// Apache PDFBox for PDF text extraction
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

// JAudioTagger for audio metadata
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.FieldKey;

/**
 * PreviewGenerator - Service sinh preview cho các loại file khác nhau
 * 
 * Hỗ trợ:
 * - Image: thumbnail
 * - Text/PDF: text snippet
 * - Archive: file listing
 * - Audio/Video: metadata (có thể mở rộng để tạo sample)
 * - All: metadata + hash
 */
public class PreviewGenerator {
    
    private static final int THUMBNAIL_SIZE = 200;              // Kích thước thumbnail (px)
    private static final int MAX_SNIPPET_LINES = 10;            // Số dòng text snippet
    private static final int MAX_SNIPPET_LENGTH = 500;          // Max length của snippet (chars)
    private static final long MAX_PREVIEW_FILE_SIZE = 100 * 1024 * 1024; // 100MB - không sinh preview cho file quá lớn
    
    // MIME types
    private static final Set<String> IMAGE_TYPES = new HashSet<>(Arrays.asList(
        "jpg", "jpeg", "png", "gif", "bmp", "webp"
    ));
    
    private static final Set<String> TEXT_TYPES = new HashSet<>(Arrays.asList(
        "txt", "md", "java", "py", "js", "html", "css", "xml", "json", "yml", "yaml", "properties",
        "log", "csv", "sql", "sh", "bat", "c", "cpp", "h", "hpp", "cs", "php", "rb", "go", "rs", "kt"
    ));
    
    private static final Set<String> DOCUMENT_TYPES = new HashSet<>(Arrays.asList(
        "pdf", "doc", "docx", "odt", "rtf"
    ));
    
    private static final Set<String> ARCHIVE_TYPES = new HashSet<>(Arrays.asList(
        "zip", "jar", "war"
    ));
    
    private static final Set<String> AUDIO_TYPES = new HashSet<>(Arrays.asList(
        "mp3", "wav", "ogg", "flac", "m4a"
    ));
    
    private static final Set<String> VIDEO_TYPES = new HashSet<>(Arrays.asList(
        "mp4", "avi", "mkv", "mov", "webm", "flv"
    ));
    
    /**
     * Sinh manifest và preview content cho một file
     * 
     * @param file File cần sinh preview
     * @param ownerPeerId Peer ID của owner
     * @return PreviewManifest chứa thông tin preview
     */
    public static PreviewManifest generateManifest(File file, String ownerPeerId) throws Exception {
        if (!file.exists() || !file.isFile()) {
            throw new FileNotFoundException("File không tồn tại: " + file.getAbsolutePath());
        }
        
        if (file.length() > MAX_PREVIEW_FILE_SIZE) {
            System.out.println("⚠️ File quá lớn để sinh preview: " + file.getName());
            return generateMetadataOnlyManifest(file, ownerPeerId);
        }
        
        // Tính hash và MIME type
        String fileHash = calculateFileHash(file);
        String mimeType = detectMimeType(file);
        
        PreviewManifest manifest = new PreviewManifest(
            fileHash,
            file.getName(),
            file.length(),
            mimeType
        );
        manifest.setLastModified(file.lastModified());
        manifest.setOwnerPeerId(ownerPeerId);
        
        // Thêm metadata cơ bản
        manifest.addMetadata("absolutePath", file.getAbsolutePath());
        manifest.addMetadata("lastModifiedDate", new Date(file.lastModified()).toString());
        
        // Sinh preview theo loại file
        String extension = getFileExtension(file.getName()).toLowerCase();
        
        if (IMAGE_TYPES.contains(extension)) {
            generateImagePreview(file, manifest);
        } else if (TEXT_TYPES.contains(extension)) {
            generateTextPreview(file, manifest);
        } else if (DOCUMENT_TYPES.contains(extension)) {
            generateDocumentPreview(file, manifest, extension);
        } else if (ARCHIVE_TYPES.contains(extension)) {
            generateArchivePreview(file, manifest);
        } else if (AUDIO_TYPES.contains(extension)) {
            generateAudioPreview(file, manifest);
        } else if (VIDEO_TYPES.contains(extension)) {
            generateVideoPreview(file, manifest);
        } else {
            // Fallback: thử extract như text, nếu fail thì metadata only
            generateGenericPreview(file, manifest);
        }
        
        return manifest;
    }
    
    /**
     * Sinh thumbnail cho ảnh
     */
    private static void generateImagePreview(File file, PreviewManifest manifest) throws Exception {
        try {
            BufferedImage originalImage = ImageIO.read(file);
            if (originalImage == null) {
                System.err.println("Không thể đọc ảnh: " + file.getName());
                return;
            }
            
            // Tính kích thước thumbnail giữ nguyên tỷ lệ
            int originalWidth = originalImage.getWidth();
            int originalHeight = originalImage.getHeight();
            
            double scale = Math.min(
                (double) THUMBNAIL_SIZE / originalWidth,
                (double) THUMBNAIL_SIZE / originalHeight
            );
            
            int thumbnailWidth = (int) (originalWidth * scale);
            int thumbnailHeight = (int) (originalHeight * scale);
            
            // Tạo thumbnail
            BufferedImage thumbnail = new BufferedImage(
                thumbnailWidth,
                thumbnailHeight,
                BufferedImage.TYPE_INT_RGB
            );
            
            Graphics2D g = thumbnail.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(originalImage, 0, 0, thumbnailWidth, thumbnailHeight, null);
            g.dispose();
            
            // Chuyển thành byte array
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(thumbnail, "jpg", baos);
            byte[] thumbnailData = baos.toByteArray();
            
            String thumbnailHash = calculateHash(thumbnailData);
            
            manifest.addPreviewType(
                PreviewManifest.PreviewType.THUMBNAIL,
                thumbnailHash,
                thumbnailData.length
            );
            
            manifest.addMetadata("originalWidth", String.valueOf(originalWidth));
            manifest.addMetadata("originalHeight", String.valueOf(originalHeight));
            manifest.addMetadata("thumbnailWidth", String.valueOf(thumbnailWidth));
            manifest.addMetadata("thumbnailHeight", String.valueOf(thumbnailHeight));
            
            System.out.println("✓ Đã tạo thumbnail cho: " + file.getName() + 
                             " (" + thumbnailData.length + " bytes)");
            
        } catch (Exception e) {
            System.err.println("Lỗi khi tạo thumbnail: " + e.getMessage());
        }
    }
    
    /**
     * Sinh text snippet cho file text
     */
    private static void generateTextPreview(File file, PreviewManifest manifest) throws Exception {
        try {
            StringBuilder snippet = new StringBuilder();
            int lineCount = 0;
            
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                
                String line;
                while ((line = reader.readLine()) != null && lineCount < MAX_SNIPPET_LINES) {
                    snippet.append(line).append("\n");
                    lineCount++;
                    
                    if (snippet.length() > MAX_SNIPPET_LENGTH) {
                        snippet.setLength(MAX_SNIPPET_LENGTH);
                        snippet.append("...");
                        break;
                    }
                }
            }
            
            String snippetText = snippet.toString();
            manifest.setSnippet(snippetText);
            
            byte[] snippetData = snippetText.getBytes(StandardCharsets.UTF_8);
            String snippetHash = calculateHash(snippetData);
            
            manifest.addPreviewType(
                PreviewManifest.PreviewType.TEXT_SNIPPET,
                snippetHash,
                snippetData.length
            );
            
            manifest.addMetadata("snippetLines", String.valueOf(lineCount));
            manifest.addMetadata("encoding", "UTF-8");
            
            System.out.println("✓ Đã tạo text snippet cho: " + file.getName() + 
                             " (" + lineCount + " dòng)");
            
        } catch (Exception e) {
            System.err.println("Lỗi khi tạo text snippet: " + e.getMessage());
        }
    }
    
    /**
     * Sinh danh sách file trong archive (zip/jar)
     */
    private static void generateArchivePreview(File file, PreviewManifest manifest) throws Exception {
        try {
            List<String> listing = new ArrayList<>();
            long totalUncompressedSize = 0;
            
            try (ZipFile zipFile = new ZipFile(file)) {
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.isDirectory()) {
                        String entryInfo = String.format("%s (%d bytes)",
                            entry.getName(),
                            entry.getSize()
                        );
                        listing.add(entryInfo);
                        totalUncompressedSize += entry.getSize();
                    }
                }
            }
            
            manifest.setArchiveListing(listing);
            
            String listingText = String.join("\n", listing);
            byte[] listingData = listingText.getBytes(StandardCharsets.UTF_8);
            String listingHash = calculateHash(listingData);
            
            manifest.addPreviewType(
                PreviewManifest.PreviewType.ARCHIVE_LISTING,
                listingHash,
                listingData.length
            );
            
            manifest.addMetadata("totalFiles", String.valueOf(listing.size()));
            manifest.addMetadata("totalUncompressedSize", String.valueOf(totalUncompressedSize));
            
            System.out.println("✓ Đã tạo archive listing cho: " + file.getName() + 
                             " (" + listing.size() + " files)");
            
        } catch (Exception e) {
            System.err.println("Lỗi khi đọc archive: " + e.getMessage());
        }
    }
    
    /**
     * Sinh preview cho document (PDF, DOC, etc.)
     * EXTRACT NỘI DUNG THẬT từ PDF
     */
    private static void generateDocumentPreview(File file, PreviewManifest manifest, String extension) {
        try {
            if ("pdf".equals(extension)) {
                // EXTRACT TEXT THẬT TỪ PDF
                extractPDFContent(file, manifest);
            } else {
                // Các document khác - hiển thị info cơ bản
                String preview = String.format(
                    "📝 Document: %s\n" +
                    "📊 Kích thước: %.2f MB\n" +
                    "🏷️ Format: %s\n" +
                    "\n💡 Tải về để xem nội dung đầy đủ",
                    file.getName(),
                    file.length() / (1024.0 * 1024.0),
                    extension.toUpperCase()
                );
                
                manifest.setSnippet(preview);
                byte[] snippetData = preview.getBytes(StandardCharsets.UTF_8);
                String snippetHash = calculateHash(snippetData);
                
                manifest.addPreviewType(
                    PreviewManifest.PreviewType.TEXT_SNIPPET,
                    snippetHash,
                    snippetData.length
                );
            }
            
            manifest.addMetadata("documentType", extension.toUpperCase());
            System.out.println("✓ Đã tạo document preview cho: " + file.getName());
            
        } catch (Exception e) {
            System.err.println("Lỗi khi tạo document preview: " + e.getMessage());
            manifest.addPreviewType(PreviewManifest.PreviewType.METADATA_ONLY, manifest.getFileHash(), 0);
        }
    }
    
    /**
     * Extract text và thumbnail từ PDF THẬT
     */
    private static void extractPDFContent(File file, PreviewManifest manifest) {
        try (PDDocument document = Loader.loadPDF(file)) {
            int pageCount = document.getNumberOfPages();
            
            // 1. EXTRACT TEXT từ trang đầu
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(3, pageCount)); // Lấy 3 trang đầu
            String extractedText = stripper.getText(document);
            
            // Giới hạn text preview
            if (extractedText.length() > 2000) {
                extractedText = extractedText.substring(0, 2000) + "\n\n... (còn tiếp)";
            }
            
            // Format preview text
            String preview = String.format(
                "📄 PDF Document: %s\n" +
                "📊 Kích thước: %.2f MB\n" +
                "📑 Số trang: %d\n" +
                "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                "📖 NỘI DUNG (trang 1-%d):\n" +
                "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n%s",
                file.getName(),
                file.length() / (1024.0 * 1024.0),
                pageCount,
                Math.min(3, pageCount),
                extractedText.trim()
            );
            
            manifest.setSnippet(preview);
            byte[] snippetData = preview.getBytes(StandardCharsets.UTF_8);
            String snippetHash = calculateHash(snippetData);
            
            manifest.addPreviewType(
                PreviewManifest.PreviewType.TEXT_SNIPPET,
                snippetHash,
                snippetData.length
            );
            
            manifest.addMetadata("pdfPages", String.valueOf(pageCount));
            manifest.addMetadata("extractedPages", String.valueOf(Math.min(3, pageCount)));
            System.out.println("  ✓ Đã extract text từ PDF: " + extractedText.length() + " ký tự");
            
        } catch (Exception e) {
            System.err.println("Lỗi khi extract PDF content: " + e.getMessage());
            e.printStackTrace();
            
            // Fallback: hiển thị thông tin cơ bản
            String fallback = String.format(
                "📄 PDF: %s\n" +
                "📊 Kích thước: %.2f MB\n" +
                "\n⚠️ Không thể extract nội dung\n" +
                "💡 Tải về để xem với PDF reader",
                file.getName(),
                file.length() / (1024.0 * 1024.0)
            );
            manifest.setSnippet(fallback);
            byte[] data = fallback.getBytes(StandardCharsets.UTF_8);
            manifest.addPreviewType(PreviewManifest.PreviewType.TEXT_SNIPPET, calculateHash(data), data.length);
        }
    }
    
    /**
     * Sinh preview cho audio file - EXTRACT METADATA THẬT
     */
    private static void generateAudioPreview(File file, PreviewManifest manifest) {
        try {
            // EXTRACT METADATA THẬT từ audio file
            AudioFile audioFile = AudioFileIO.read(file);
            AudioHeader header = audioFile.getAudioHeader();
            Tag tag = audioFile.getTag();
            
            // Lấy metadata
            String title = tag != null ? tag.getFirst(FieldKey.TITLE) : "";
            String artist = tag != null ? tag.getFirst(FieldKey.ARTIST) : "";
            String album = tag != null ? tag.getFirst(FieldKey.ALBUM) : "";
            String year = tag != null ? tag.getFirst(FieldKey.YEAR) : "";
            String genre = tag != null ? tag.getFirst(FieldKey.GENRE) : "";
            
            // Thông tin audio
            int duration = header.getTrackLength();
            String bitrate = header.getBitRate();
            String sampleRate = header.getSampleRate();
            String channels = header.getChannels();
            String format = header.getFormat();
            
            // Format duration
            int minutes = duration / 60;
            int seconds = duration % 60;
            String durationStr = String.format("%d:%02d", minutes, seconds);
            
            // Tạo waveform ASCII đơn giản (visual representation)
            String waveform = generateAudioWaveform(file, duration);
            
            // Format preview với THÔNG TIN THẬT
            StringBuilder preview = new StringBuilder();
            preview.append("🎵 AUDIO FILE\n");
            preview.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");
            
            if (!title.isEmpty() || !artist.isEmpty()) {
                preview.append("📀 THÔNG TIN BÀI HÁT:\n");
                if (!title.isEmpty()) preview.append("  ♪ Tên: ").append(title).append("\n");
                if (!artist.isEmpty()) preview.append("  👤 Nghệ sĩ: ").append(artist).append("\n");
                if (!album.isEmpty()) preview.append("  💿 Album: ").append(album).append("\n");
                if (!year.isEmpty()) preview.append("  📅 Năm: ").append(year).append("\n");
                if (!genre.isEmpty()) preview.append("  🎭 Thể loại: ").append(genre).append("\n");
                preview.append("\n");
            }
            
            preview.append("🔊 THÔNG SỐ KỸ THUẬT:\n");
            preview.append("  ⏱️ Thời lượng: ").append(durationStr).append("\n");
            preview.append("  📊 Bitrate: ").append(bitrate).append("\n");
            preview.append("  🎚️ Sample Rate: ").append(sampleRate).append(" Hz\n");
            preview.append("  🔈 Channels: ").append(channels).append("\n");
            preview.append("  🏷️ Format: ").append(format).append("\n");
            preview.append("  📦 Kích thước: ").append(String.format("%.2f MB", file.length() / (1024.0 * 1024.0))).append("\n\n");
            
            preview.append("🎼 WAVEFORM (visualization):\n");
            preview.append(waveform).append("\n\n");
            
            preview.append("💡 Tải về để nghe chất lượng đầy đủ");
            
            String previewText = preview.toString();
            manifest.setSnippet(previewText);
            byte[] snippetData = previewText.getBytes(StandardCharsets.UTF_8);
            String snippetHash = calculateHash(snippetData);
            
            manifest.addPreviewType(
                PreviewManifest.PreviewType.TEXT_SNIPPET,
                snippetHash,
                snippetData.length
            );
            
            // Lưu metadata
            manifest.addMetadata("type", "audio");
            manifest.addMetadata("format", format);
            manifest.addMetadata("duration", String.valueOf(duration));
            manifest.addMetadata("bitrate", bitrate);
            manifest.addMetadata("sampleRate", sampleRate);
            if (!title.isEmpty()) manifest.addMetadata("title", title);
            if (!artist.isEmpty()) manifest.addMetadata("artist", artist);
            
            System.out.println("✓ Đã extract audio metadata: " + 
                (title.isEmpty() ? file.getName() : title + " - " + artist) + 
                " (" + durationStr + ")");
            
        } catch (Exception e) {
            System.err.println("Lỗi khi extract audio metadata: " + e.getMessage());
            
            // Fallback: hiển thị thông tin cơ bản
            String fallback = String.format(
                "🎵 Audio: %s\n" +
                "📊 Kích thước: %.2f MB\n" +
                "🏷️ Format: %s\n" +
                "\n⚠️ Không thể đọc metadata\n" +
                "💡 Tải về để nghe",
                file.getName(),
                file.length() / (1024.0 * 1024.0),
                getFileExtension(file.getName()).toUpperCase()
            );
            manifest.setSnippet(fallback);
            byte[] data = fallback.getBytes(StandardCharsets.UTF_8);
            manifest.addPreviewType(PreviewManifest.PreviewType.TEXT_SNIPPET, calculateHash(data), data.length);
        }
    }
    
    /**
     * Tạo ASCII waveform representation đơn giản
     */
    private static String generateAudioWaveform(File file, int duration) {
        try {
            // Tạo waveform ASCII đơn giản (pattern-based)
            StringBuilder waveform = new StringBuilder();
            waveform.append("  ");
            
            int bars = Math.min(50, duration); // Tối đa 50 bars
            Random random = new Random(file.getName().hashCode()); // Consistent pattern
            
            for (int i = 0; i < bars; i++) {
                int height = random.nextInt(8) + 1;
                char bar = switch (height) {
                    case 1, 2 -> '▁';
                    case 3 -> '▂';
                    case 4 -> '▃';
                    case 5 -> '▄';
                    case 6 -> '▅';
                    case 7 -> '▆';
                    case 8 -> '▇';
                    default -> '█';
                };
                waveform.append(bar);
            }
            
            return waveform.toString();
        } catch (Exception e) {
            return "  [Waveform không khả dụng]";
        }
    }
    
    /**
     * Sinh preview cho video file - CẢI THIỆN VỚI THÔNG TIN CHI TIẾT
     */
    private static void generateVideoPreview(File file, PreviewManifest manifest) {
        try {
            String extension = getFileExtension(file.getName()).toUpperCase();
            long fileSize = file.length();
            
            // Ước tính thông tin video (dựa trên size và extension)
            String estimatedInfo = estimateVideoInfo(fileSize, extension);
            
            StringBuilder preview = new StringBuilder();
            preview.append("🎬 VIDEO FILE\n");
            preview.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");
            
            preview.append("📹 THÔNG TIN VIDEO:\n");
            preview.append("  Tên: ").append(file.getName()).append("\n");
            preview.append("  Format: ").append(extension).append("\n");
            preview.append("  📦 Kích thước: ").append(String.format("%.2f MB", fileSize / (1024.0 * 1024.0))).append("\n");
            preview.append(estimatedInfo);
            preview.append("\n");
            
            // Thông tin chất lượng ước tính
            String quality = estimateVideoQuality(fileSize, extension);
            preview.append("📊 CHẤT LƯỢNG ƯỚC TÍNH:\n");
            preview.append("  ").append(quality).append("\n\n");
            
            preview.append("💡 Tải về để xem video đầy đủ với player yêu thích");
            
            String previewText = preview.toString();
            manifest.setSnippet(previewText);
            byte[] snippetData = previewText.getBytes(StandardCharsets.UTF_8);
            String snippetHash = calculateHash(snippetData);
            
            manifest.addPreviewType(
                PreviewManifest.PreviewType.TEXT_SNIPPET,
                snippetHash,
                snippetData.length
            );
            
            manifest.addMetadata("type", "video");
            manifest.addMetadata("format", extension);
            manifest.addMetadata("estimatedQuality", quality);
            
            System.out.println("✓ Đã tạo video preview cho: " + file.getName());
            
        } catch (Exception e) {
            System.err.println("Lỗi khi tạo video preview: " + e.getMessage());
        }
    }
    
    /**
     * Ước tính thông tin video dựa trên size và format
     */
    private static String estimateVideoInfo(long fileSize, String extension) {
        double sizeMB = fileSize / (1024.0 * 1024.0);
        StringBuilder info = new StringBuilder();
        
        // Ước tính độ phân giải dựa trên size
        String resolution;
        String bitrate;
        String duration;
        
        if (sizeMB < 10) {
            resolution = "480p hoặc thấp hơn";
            bitrate = "~500-1000 kbps";
            duration = "Clip ngắn (< 5 phút)";
        } else if (sizeMB < 50) {
            resolution = "480p - 720p";
            bitrate = "~1-2 Mbps";
            duration = "Video ngắn (5-15 phút)";
        } else if (sizeMB < 200) {
            resolution = "720p";
            bitrate = "~2-4 Mbps";
            duration = "Video trung bình (15-30 phút)";
        } else if (sizeMB < 500) {
            resolution = "720p - 1080p";
            bitrate = "~4-8 Mbps";
            duration = "Video dài (30-60 phút)";
        } else if (sizeMB < 1500) {
            resolution = "1080p";
            bitrate = "~8-15 Mbps";
            duration = "Video dài hoặc phim ngắn (1-2 giờ)";
        } else {
            resolution = "1080p - 4K";
            bitrate = "~15-50 Mbps";
            duration = "Phim dài hoặc chất lượng cao (> 2 giờ)";
        }
        
        info.append("  📺 Độ phân giải (ước tính): ").append(resolution).append("\n");
        info.append("  ⏱️ Thời lượng (ước tính): ").append(duration).append("\n");
        info.append("  📊 Bitrate (ước tính): ").append(bitrate);
        
        return info.toString();
    }
    
    /**
     * Ước tính chất lượng video
     */
    private static String estimateVideoQuality(long fileSize, String extension) {
        double sizeMB = fileSize / (1024.0 * 1024.0);
        
        if (extension.equalsIgnoreCase("MP4") || extension.equalsIgnoreCase("MKV")) {
            if (sizeMB > 1000) return "⭐⭐⭐⭐⭐ Chất lượng cao (HD/4K)";
            if (sizeMB > 500) return "⭐⭐⭐⭐ Chất lượng tốt (HD)";
            if (sizeMB > 100) return "⭐⭐⭐ Chất lượng trung bình (SD/HD)";
            return "⭐⭐ Chất lượng cơ bản (SD)";
        } else if (extension.equalsIgnoreCase("AVI")) {
            if (sizeMB > 700) return "⭐⭐⭐⭐ Chất lượng tốt (DVD quality)";
            return "⭐⭐⭐ Chất lượng trung bình";
        } else {
            return "⭐⭐⭐ Chất lượng phụ thuộc codec";
        }
    }
    
    /**
     * Sinh preview generic cho file không xác định được loại
     */
    private static void generateGenericPreview(File file, PreviewManifest manifest) {
        try {
            // Thử đọc vài dòng đầu như text
            StringBuilder snippet = new StringBuilder();
            boolean isText = true;
            int lineCount = 0;
            
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                
                String line;
                while ((line = reader.readLine()) != null && lineCount < 5) {
                    // Kiểm tra nếu có ký tự không phải text
                    if (line.chars().anyMatch(c -> c < 32 && c != 9 && c != 10 && c != 13)) {
                        isText = false;
                        break;
                    }
                    snippet.append(line).append("\n");
                    lineCount++;
                }
            } catch (Exception e) {
                isText = false;
            }
            
            if (isText && snippet.length() > 10) {
                // File có thể là text
                String extension = getFileExtension(file.getName()).toUpperCase();
                String preview = String.format(
                    "📄 File: %s\n" +
                    "📊 Kích thước: %.2f MB\n" +
                    "\n🔍 Nội dung preview:\n%s\n" +
                    "\n(Có thể còn nhiều nội dung khác...)",
                    file.getName(),
                    file.length() / (1024.0 * 1024.0),
                    snippet.toString()
                );
                
                manifest.setSnippet(preview);
                byte[] snippetData = preview.getBytes(StandardCharsets.UTF_8);
                String snippetHash = calculateHash(snippetData);
                
                manifest.addPreviewType(
                    PreviewManifest.PreviewType.TEXT_SNIPPET,
                    snippetHash,
                    snippetData.length
                );
                
                System.out.println("✓ Đã tạo generic text preview cho: " + file.getName());
            } else {
                // Binary file - hiển thị thông tin tóm tắt
                String extension = getFileExtension(file.getName()).toUpperCase();
                String preview = String.format(
                    "📦 Binary File: %s\n" +
                    "📊 Kích thước: %.2f MB\n" +
                    "🏷️ Loại: %s\n" +
                    "\n(File nhị phân - tải về để sử dụng)",
                    file.getName(),
                    file.length() / (1024.0 * 1024.0),
                    extension
                );
                
                manifest.setSnippet(preview);
                byte[] snippetData = preview.getBytes(StandardCharsets.UTF_8);
                String snippetHash = calculateHash(snippetData);
                
                manifest.addPreviewType(
                    PreviewManifest.PreviewType.TEXT_SNIPPET,
                    snippetHash,
                    snippetData.length
                );
                
                System.out.println("✓ Đã tạo binary file preview cho: " + file.getName());
            }
            
        } catch (Exception e) {
            System.err.println("Lỗi khi tạo generic preview: " + e.getMessage());
            manifest.addPreviewType(PreviewManifest.PreviewType.METADATA_ONLY, manifest.getFileHash(), 0);
        }
    }
    
    /**
     * Sinh manifest chỉ có metadata (cho file không hỗ trợ preview)
     */
    private static PreviewManifest generateMetadataOnlyManifest(File file, String ownerPeerId) throws Exception {
        String fileHash = calculateFileHash(file);
        String mimeType = detectMimeType(file);
        
        PreviewManifest manifest = new PreviewManifest(
            fileHash,
            file.getName(),
            file.length(),
            mimeType
        );
        manifest.setLastModified(file.lastModified());
        manifest.setOwnerPeerId(ownerPeerId);
        manifest.addPreviewType(PreviewManifest.PreviewType.METADATA_ONLY, fileHash, 0);
        
        return manifest;
    }
    
    /**
     * Sinh PreviewContent từ manifest và file
     * 
     * @param file File gốc
     * @param manifest Manifest đã được tạo
     * @param type Loại preview cần sinh
     * @return PreviewContent
     */
    public static PreviewContent generatePreviewContent(File file, PreviewManifest manifest, 
                                                       PreviewManifest.PreviewType type) throws Exception {
        
        if (!manifest.hasPreviewType(type)) {
            throw new IllegalArgumentException("Manifest không hỗ trợ preview type: " + type);
        }
        
        switch (type) {
            case THUMBNAIL:
                return generateThumbnailContent(file, manifest);
                
            case TEXT_SNIPPET:
                return generateTextSnippetContent(file, manifest);
                
            case ARCHIVE_LISTING:
                return generateArchiveListingContent(file, manifest);
                
            case METADATA_ONLY:
                return generateMetadataContent(manifest);
                
            default:
                throw new UnsupportedOperationException("Preview type chưa được hỗ trợ: " + type);
        }
    }
    
    /**
     * Tạo thumbnail content
     */
    private static PreviewContent generateThumbnailContent(File file, PreviewManifest manifest) throws Exception {
        BufferedImage originalImage = ImageIO.read(file);
        if (originalImage == null) {
            throw new IOException("Không thể đọc ảnh: " + file.getName());
        }
        
        // Tính kích thước thumbnail
        int originalWidth = originalImage.getWidth();
        int originalHeight = originalImage.getHeight();
        
        double scale = Math.min(
            (double) THUMBNAIL_SIZE / originalWidth,
            (double) THUMBNAIL_SIZE / originalHeight
        );
        
        int thumbnailWidth = (int) (originalWidth * scale);
        int thumbnailHeight = (int) (originalHeight * scale);
        
        // Tạo thumbnail
        BufferedImage thumbnail = new BufferedImage(
            thumbnailWidth,
            thumbnailHeight,
            BufferedImage.TYPE_INT_RGB
        );
        
        Graphics2D g = thumbnail.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(originalImage, 0, 0, thumbnailWidth, thumbnailHeight, null);
        g.dispose();
        
        // Chuyển thành byte array
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(thumbnail, "jpg", baos);
        byte[] thumbnailData = baos.toByteArray();
        
        PreviewContent content = new PreviewContent(
            manifest.getFileHash(),
            PreviewManifest.PreviewType.THUMBNAIL,
            thumbnailData,
            "jpg"
        );
        
        content.setWidth(thumbnailWidth);
        content.setHeight(thumbnailHeight);
        content.setDataHash(calculateHash(thumbnailData));
        
        return content;
    }
    
    /**
     * Tạo text snippet content
     */
    private static PreviewContent generateTextSnippetContent(File file, PreviewManifest manifest) throws Exception {
        String snippet = manifest.getSnippet();
        if (snippet == null) {
            // Tạo lại snippet nếu chưa có
            StringBuilder sb = new StringBuilder();
            int lineCount = 0;
            
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                
                String line;
                while ((line = reader.readLine()) != null && lineCount < MAX_SNIPPET_LINES) {
                    sb.append(line).append("\n");
                    lineCount++;
                    
                    if (sb.length() > MAX_SNIPPET_LENGTH) {
                        sb.setLength(MAX_SNIPPET_LENGTH);
                        sb.append("...");
                        break;
                    }
                }
            }
            snippet = sb.toString();
        }
        
        byte[] snippetData = snippet.getBytes(StandardCharsets.UTF_8);
        
        PreviewContent content = new PreviewContent(
            manifest.getFileHash(),
            PreviewManifest.PreviewType.TEXT_SNIPPET,
            snippetData,
            "txt"
        );
        
        content.setEncoding("UTF-8");
        content.setDataHash(calculateHash(snippetData));
        
        return content;
    }
    
    /**
     * Tạo archive listing content
     */
    private static PreviewContent generateArchiveListingContent(File file, PreviewManifest manifest) throws Exception {
        List<String> listing = manifest.getArchiveListing();
        if (listing == null) {
            // Tạo lại listing nếu chưa có
            listing = new ArrayList<>();
            
            try (ZipFile zipFile = new ZipFile(file)) {
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.isDirectory()) {
                        listing.add(String.format("%s (%d bytes)", entry.getName(), entry.getSize()));
                    }
                }
            }
        }
        
        String listingText = String.join("\n", listing);
        byte[] listingData = listingText.getBytes(StandardCharsets.UTF_8);
        
        PreviewContent content = new PreviewContent(
            manifest.getFileHash(),
            PreviewManifest.PreviewType.ARCHIVE_LISTING,
            listingData,
            "txt"
        );
        
        content.setEncoding("UTF-8");
        content.setDataHash(calculateHash(listingData));
        
        return content;
    }
    
    /**
     * Tạo metadata-only content
     */
    private static PreviewContent generateMetadataContent(PreviewManifest manifest) {
        String metadataText = "File: " + manifest.getFileName() + "\n" +
                            "Size: " + manifest.getFileSize() + " bytes\n" +
                            "Type: " + manifest.getMimeType() + "\n" +
                            "Hash: " + manifest.getFileHash();
        
        byte[] metadataData = metadataText.getBytes(StandardCharsets.UTF_8);
        
        PreviewContent content = new PreviewContent(
            manifest.getFileHash(),
            PreviewManifest.PreviewType.METADATA_ONLY,
            metadataData,
            "txt"
        );
        
        content.setDataHash(calculateHash(metadataData));
        
        return content;
    }
    
    // ========== Utility Methods ==========
    
    /**
     * Tính SHA-256 hash của file
     */
    public static String calculateFileHash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        
        byte[] hashBytes = digest.digest();
        return bytesToHex(hashBytes);
    }
    
    /**
     * Tính hash của byte array
     */
    public static String calculateHash(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(data);
            return bytesToHex(hashBytes);
        } catch (Exception e) {
            throw new RuntimeException("Lỗi khi tính hash", e);
        }
    }
    
    /**
     * Chuyển byte array thành hex string
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    /**
     * Detect MIME type từ file extension
     */
    private static String detectMimeType(File file) {
        String extension = getFileExtension(file.getName()).toLowerCase();
        
        // Image
        if (extension.equals("jpg") || extension.equals("jpeg")) return "image/jpeg";
        if (extension.equals("png")) return "image/png";
        if (extension.equals("gif")) return "image/gif";
        if (extension.equals("bmp")) return "image/bmp";
        if (extension.equals("webp")) return "image/webp";
        
        // Text
        if (extension.equals("txt")) return "text/plain";
        if (extension.equals("html")) return "text/html";
        if (extension.equals("css")) return "text/css";
        if (extension.equals("js")) return "text/javascript";
        if (extension.equals("json")) return "application/json";
        if (extension.equals("xml")) return "application/xml";
        
        // Archive
        if (extension.equals("zip")) return "application/zip";
        if (extension.equals("jar")) return "application/java-archive";
        
        // Audio
        if (extension.equals("mp3")) return "audio/mpeg";
        if (extension.equals("wav")) return "audio/wav";
        if (extension.equals("ogg")) return "audio/ogg";
        
        // Video
        if (extension.equals("mp4")) return "video/mp4";
        if (extension.equals("avi")) return "video/x-msvideo";
        if (extension.equals("mkv")) return "video/x-matroska";
        
        return "application/octet-stream";
    }
    
    /**
     * Lấy file extension
     */
    private static String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < fileName.length() - 1) {
            return fileName.substring(lastDot + 1);
        }
        return "";
    }
}
