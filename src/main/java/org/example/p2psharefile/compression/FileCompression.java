package org.example.p2psharefile.compression;

import java.io.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Module 3: FileCompression - Nén và giải nén file bằng GZIP
 * 
 * GZIP là thuật toán nén dữ liệu phổ biến:
 * - Giảm kích thước file trước khi truyền qua mạng
 * - Tiết kiệm băng thông và thời gian truyền
 * - Đặc biệt hiệu quả với text file (có thể giảm 70-90%)
 * 
 * Quy trình:
 * 1. Nén file trước khi gửi
 * 2. Truyền file đã nén qua mạng
 * 3. Giải nén file khi nhận
 */
public class FileCompression {
    
    private static final int BUFFER_SIZE = 8192; // 8KB buffer
    
    /**
     * Nén dữ liệu byte array bằng GZIP
     * 
     * @param data Dữ liệu gốc
     * @return Dữ liệu đã nén
     */
    public static byte[] compress(byte[] data) throws IOException {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        
        try (GZIPOutputStream gzipStream = new GZIPOutputStream(byteStream)) {
            gzipStream.write(data);
            gzipStream.finish(); // Hoàn thành quá trình nén
        }
        
        return byteStream.toByteArray();
    }
    
    /**
     * Giải nén dữ liệu đã nén bằng GZIP
     * 
     * @param compressedData Dữ liệu đã nén
     * @return Dữ liệu gốc
     */
    public static byte[] decompress(byte[] compressedData) throws IOException {
        ByteArrayInputStream byteStream = new ByteArrayInputStream(compressedData);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        
        try (GZIPInputStream gzipStream = new GZIPInputStream(byteStream)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = gzipStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, len);
            }
        }
        
        return outputStream.toByteArray();
    }
    
    /**
     * Nén file và lưu thành file mới
     * 
     * @param sourceFile File gốc cần nén
     * @param compressedFile File output (thường có đuôi .gz)
     */
    public static void compressFile(File sourceFile, File compressedFile) throws IOException {
        try (FileInputStream fis = new FileInputStream(sourceFile);
             FileOutputStream fos = new FileOutputStream(compressedFile);
             GZIPOutputStream gzipStream = new GZIPOutputStream(fos)) {
            
            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                gzipStream.write(buffer, 0, len);
            }
            
            gzipStream.finish();
        }
    }
    
    /**
     * Giải nén file đã nén
     * 
     * @param compressedFile File đã nén (.gz)
     * @param decompressedFile File output
     */
    public static void decompressFile(File compressedFile, File decompressedFile) throws IOException {
        try (FileInputStream fis = new FileInputStream(compressedFile);
             GZIPInputStream gzipStream = new GZIPInputStream(fis);
             FileOutputStream fos = new FileOutputStream(decompressedFile)) {
            
            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = gzipStream.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        }
    }
    
    /**
     * Tính tỷ lệ nén (để hiển thị cho người dùng)
     * 
     * @param originalSize Kích thước gốc
     * @param compressedSize Kích thước sau nén
     * @return Tỷ lệ nén (%)
     */
    public static double getCompressionRatio(long originalSize, long compressedSize) {
        if (originalSize == 0) return 0;
        return (1.0 - ((double) compressedSize / originalSize)) * 100;
    }
    
    /**
     * Kiểm tra xem file có nên nén hay không
     * Một số file đã được nén sẵn (như .jpg, .mp4, .zip) không nên nén thêm
     * 
     * @param fileName Tên file
     * @return true nếu nên nén
     */
    public static boolean shouldCompress(String fileName) {
        // Các định dạng đã nén sẵn
        String[] alreadyCompressed = {
            ".zip", ".rar", ".7z", ".gz", ".bz2",  // Archive
            ".jpg", ".jpeg", ".png", ".gif",        // Image
            ".mp3", ".mp4", ".avi", ".mkv",         // Media
            ".pdf", ".docx", ".xlsx"                // Document
        };
        
        String lowerName = fileName.toLowerCase();
        for (String ext : alreadyCompressed) {
            if (lowerName.endsWith(ext)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Nén và trả về thông tin chi tiết
     */
    public static CompressionResult compressWithInfo(byte[] data) throws IOException {
        long startTime = System.currentTimeMillis();
        byte[] compressed = compress(data);
        long endTime = System.currentTimeMillis();
        
        return new CompressionResult(
            data.length,
            compressed.length,
            endTime - startTime,
            compressed
        );
    }
    
    /**
     * Class chứa kết quả nén
     */
    public static class CompressionResult {
        private final long originalSize;
        private final long compressedSize;
        private final long compressionTime;
        private final byte[] compressedData;
        
        public CompressionResult(long originalSize, long compressedSize, 
                                long compressionTime, byte[] compressedData) {
            this.originalSize = originalSize;
            this.compressedSize = compressedSize;
            this.compressionTime = compressionTime;
            this.compressedData = compressedData;
        }
        
        public long getOriginalSize() { return originalSize; }
        public long getCompressedSize() { return compressedSize; }
        public long getCompressionTime() { return compressionTime; }
        public byte[] getCompressedData() { return compressedData; }
        
        public double getCompressionRatio() {
            return FileCompression.getCompressionRatio(originalSize, compressedSize);
        }
        
        @Override
        public String toString() {
            return String.format("Nén: %d bytes → %d bytes (%.1f%%) trong %d ms",
                originalSize, compressedSize, getCompressionRatio(), compressionTime);
        }
    }
}
