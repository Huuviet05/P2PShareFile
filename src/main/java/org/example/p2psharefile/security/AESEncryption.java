package org.example.p2psharefile.security;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.IvParameterSpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Module 2: AESEncryption - Mã hóa và giải mã file bằng AES
 * 
 * AES (Advanced Encryption Standard) là thuật toán mã hóa đối xứng
 * - Sử dụng cùng 1 key để mã hóa và giải mã
 * - AES-256: key dài 256 bit = 32 bytes
 * - CBC Mode: Cipher Block Chaining với IV (Initialization Vector)
 * 
 * Cách hoạt động:
 * 1. Tạo key ngẫu nhiên hoặc từ password
 * 2. Tạo IV ngẫu nhiên (16 bytes)
 * 3. Mã hóa data với key + IV
 * 4. Khi giải mã cần cùng key + IV
 */
public class AESEncryption {
    
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final int KEY_SIZE = 256; // AES-256
    private static final int IV_SIZE = 16;   // 16 bytes cho AES
    
    /**
     * Tạo key AES ngẫu nhiên 256-bit
     * Dùng khi bạn muốn tạo key mới cho mỗi file
     */
    public static SecretKey generateKey() throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(ALGORITHM);
        keyGenerator.init(KEY_SIZE);
        return keyGenerator.generateKey();
    }
    
    /**
     * Tạo key từ String (để dễ chia sẻ key giữa các peer)
     * String phải có độ dài 32 ký tự (256 bit)
     */
    public static SecretKey createKeyFromString(String keyString) {
        // Đảm bảo key có đúng 32 bytes
        byte[] keyBytes = new byte[32];
        byte[] inputBytes = keyString.getBytes();
        System.arraycopy(inputBytes, 0, keyBytes, 0, 
                        Math.min(inputBytes.length, keyBytes.length));
        return new SecretKeySpec(keyBytes, ALGORITHM);
    }
    
    /**
     * Chuyển SecretKey sang String để lưu trữ hoặc truyền đi
     */
    public static String keyToString(SecretKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }
    
    /**
     * Chuyển String về SecretKey
     */
    public static SecretKey stringToKey(String keyString) {
        byte[] decodedKey = Base64.getDecoder().decode(keyString);
        return new SecretKeySpec(decodedKey, 0, decodedKey.length, ALGORITHM);
    }
    
    /**
     * Tạo IV (Initialization Vector) ngẫu nhiên
     * IV phải khác nhau cho mỗi lần mã hóa
     */
    private static byte[] generateIV() {
        byte[] iv = new byte[IV_SIZE];
        new SecureRandom().nextBytes(iv);
        return iv;
    }
    
    /**
     * Mã hóa dữ liệu
     * 
     * @param data Dữ liệu cần mã hóa (byte array)
     * @param key Key AES để mã hóa
     * @return Dữ liệu đã mã hóa (IV + encrypted data)
     */
    public static byte[] encrypt(byte[] data, SecretKey key) throws Exception {
        // Tạo IV ngẫu nhiên
        byte[] iv = generateIV();
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        
        // Khởi tạo cipher để mã hóa
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec);
        
        // Mã hóa data
        byte[] encryptedData = cipher.doFinal(data);
        
        // Ghép IV vào đầu encrypted data
        // Format: [IV 16 bytes][Encrypted Data]
        byte[] result = new byte[iv.length + encryptedData.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(encryptedData, 0, result, iv.length, encryptedData.length);
        
        return result;
    }
    
    /**
     * Giải mã dữ liệu
     * 
     * @param encryptedData Dữ liệu đã mã hóa (IV + encrypted data)
     * @param key Key AES để giải mã
     * @return Dữ liệu gốc
     */
    public static byte[] decrypt(byte[] encryptedData, SecretKey key) throws Exception {
        // Tách IV từ 16 bytes đầu
        byte[] iv = new byte[IV_SIZE];
        System.arraycopy(encryptedData, 0, iv, 0, IV_SIZE);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        
        // Lấy phần encrypted data (bỏ IV)
        byte[] actualEncryptedData = new byte[encryptedData.length - IV_SIZE];
        System.arraycopy(encryptedData, IV_SIZE, actualEncryptedData, 0, 
                        actualEncryptedData.length);
        
        // Khởi tạo cipher để giải mã
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, ivSpec);
        
        // Giải mã và trả về data gốc
        return cipher.doFinal(actualEncryptedData);
    }
    
    /**
     * Mã hóa String
     */
    public static String encryptString(String plainText, SecretKey key) throws Exception {
        byte[] encrypted = encrypt(plainText.getBytes(), key);
        return Base64.getEncoder().encodeToString(encrypted);
    }
    
    /**
     * Giải mã String
     */
    public static String decryptString(String encryptedText, SecretKey key) throws Exception {
        byte[] encryptedBytes = Base64.getDecoder().decode(encryptedText);
        byte[] decrypted = decrypt(encryptedBytes, key);
        return new String(decrypted);
    }
}
