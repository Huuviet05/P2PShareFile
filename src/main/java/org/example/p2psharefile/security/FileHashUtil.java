package org.example.p2psharefile.security;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * FileHashUtil - Tiện ích tính hash cho file
 * 
 * Sử dụng SHA-256 để:
 * - Tạo unique identifier cho file (preview manifest)
 * - Verify file integrity sau khi transfer
 * - Detect file duplication
 */
public class FileHashUtil {
    
    private static final int BUFFER_SIZE = 8192; // 8KB buffer
    
    /**
     * Tính SHA-256 hash của file
     * 
     * @param file File cần tính hash
     * @return Hex string của SHA-256 hash
     * @throws IOException Nếu không đọc được file
     */
    public static String calculateSHA256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            
            try (InputStream fis = new FileInputStream(file);
                 BufferedInputStream bis = new BufferedInputStream(fis)) {
                
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                
                while ((bytesRead = bis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            
            byte[] hashBytes = digest.digest();
            return bytesToHex(hashBytes);
            
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
    
    /**
     * Tính SHA-256 hash của byte array
     * 
     * @param data Byte array cần tính hash
     * @return Hex string của SHA-256 hash
     */
    public static String calculateSHA256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(data);
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
    
    /**
     * Tính MD5 hash của file (cho backward compatibility với checksum)
     * 
     * @param file File cần tính hash
     * @return Hex string của MD5 hash
     * @throws IOException Nếu không đọc được file
     */
    public static String calculateMD5(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            
            try (InputStream fis = new FileInputStream(file);
                 BufferedInputStream bis = new BufferedInputStream(fis)) {
                
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                
                while ((bytesRead = bis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            
            byte[] hashBytes = digest.digest();
            return bytesToHex(hashBytes);
            
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }
    
    /**
     * Verify file hash
     * 
     * @param file File cần verify
     * @param expectedHash Expected SHA-256 hash
     * @return true nếu hash khớp
     * @throws IOException Nếu không đọc được file
     */
    public static boolean verifyHash(File file, String expectedHash) throws IOException {
        String actualHash = calculateSHA256(file);
        return actualHash.equalsIgnoreCase(expectedHash);
    }
    
    /**
     * Convert byte array to hex string
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    /**
     * Tính hash nhanh cho file nhỏ (< 10MB)
     * Đọc toàn bộ file vào memory
     */
    public static String calculateSHA256Fast(File file) throws IOException {
        if (file.length() > 10_000_000) { // > 10MB
            return calculateSHA256(file); // Dùng streaming
        }
        
        byte[] fileBytes = Files.readAllBytes(file.toPath());
        return calculateSHA256(fileBytes);
    }
}
