package org.example.p2psharefile.security;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.*;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.*;
import java.security.cert.Certificate;
import java.security.spec.*;
import java.util.*;

/**
 * SecurityManager - Quản lý bảo mật cho P2P với TLS + Peer Authentication
 * 
 * Chức năng:
 * 1. Tạo và quản lý keypair (ECDSA) cho mỗi peer
 * 2. Tạo self-signed certificate cho TLS
 * 3. Tạo SSLContext cho SSLSocket/SSLServerSocket
 * 4. Ký và verify messages (JOIN, HEARTBEAT, PIN) để chống impersonation
 * 5. Quản lý truststore (trust tất cả peer certificates trong mạng LAN)
 * 
 * Security Model:
 * - Mỗi peer có keypair ECDSA (private key + public key)
 * - Public key được share trong PeerInfo
 * - Tất cả control messages được ký bằng private key
 * - Peer nhận message verify signature bằng public key
 * - TLS channels bảo vệ confidentiality và integrity
 */
public class SecurityManager {
    
    private static final String KEY_ALGORITHM = "RSA";          // RSA (easier cert generation)
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final String CERT_ALGORITHM = "X.509";
    private static final int KEY_SIZE = 2048;                   // RSA 2048-bit
    
    private final KeyPair keyPair;
    private final X509Certificate selfCertificate;
    private final String peerId;
    
    // Truststore: lưu public keys/certificates của peers khác
    private final Map<String, PublicKey> trustedPeerKeys;
    private final KeyStore keyStore;
    private final KeyStore trustStore;
    
    private SSLContext sslContext;
    
    /**
     * Constructor - Tạo keypair và self-signed certificate
     */
    public SecurityManager(String peerId, String peerName) throws Exception {
        this.peerId = peerId;
        this.trustedPeerKeys = new HashMap<>();
        
        // Add Bouncy Castle provider
        Security.addProvider(new BouncyCastleProvider());
        
        // Tạo keypair RSA
        System.out.println("🔐 [Bảo mật] Đang tạo cặp khóa RSA (2048-bit)...");
        this.keyPair = generateKeyPair();
        
        // Tạo self-signed certificate (cho TLS)
        System.out.println("🔐 [Bảo mật] Đang tạo chứng chỉ tự ký...");
        this.selfCertificate = generateSelfSignedCertificate(keyPair, peerName);
        
        // Tạo keystore và truststore
        this.keyStore = createKeyStore();
        this.trustStore = createTrustStore();
        
        // Tạo SSLContext
        this.sslContext = createSSLContext();
        
        System.out.println("✅ [Bảo mật] Trình quản lý bảo mật đã khởi tạo");
        System.out.println("   → Khóa công khai: " + encodePublicKey(keyPair.getPublic()).substring(0, 40) + "...");
    }
    
    /**
     * Tạo keypair RSA
     */
    private KeyPair generateKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance(KEY_ALGORITHM);
        keyGen.initialize(KEY_SIZE, new SecureRandom());
        return keyGen.generateKeyPair();
    }
    
    /**
     * Tạo self-signed X.509 certificate cho TLS sử dụng Bouncy Castle
     */
    private X509Certificate generateSelfSignedCertificate(KeyPair keyPair, String commonName) 
            throws Exception {
        
        // Tạo certificate builder
        long now = System.currentTimeMillis();
        Date startDate = new Date(now);
        Date expiryDate = new Date(now + 365L * 24 * 60 * 60 * 1000); // 1 năm
        
        // Subject/Issuer DN
        X500Name dnName = new X500Name("CN=" + commonName + ", OU=P2P, O=P2PShareFile, C=VN");
        
        // Serial number
        BigInteger serialNumber = new BigInteger(64, new SecureRandom());
        
        // Public key info
        SubjectPublicKeyInfo subjectPublicKeyInfo = SubjectPublicKeyInfo.getInstance(
                keyPair.getPublic().getEncoded());
        
        // Build certificate
        X509v3CertificateBuilder certBuilder = new X509v3CertificateBuilder(
                dnName,                 // Issuer
                serialNumber,           // Serial
                startDate,              // Not before
                expiryDate,             // Not after
                dnName,                 // Subject (self-signed)
                subjectPublicKeyInfo    // Public key
        );
        
        // Sign certificate
        ContentSigner contentSigner = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                .setProvider("BC")
                .build(keyPair.getPrivate());
        
        X509CertificateHolder certHolder = certBuilder.build(contentSigner);
        
        // Convert to X509Certificate
        X509Certificate cert = new JcaX509CertificateConverter()
                .setProvider("BC")
                .getCertificate(certHolder);
        
        System.out.println("✅ [Bảo mật] Đã tạo chứng chỉ tự ký (Bouncy Castle)");
        return cert;
    }
    
    /**
     * Tạo KeyStore chứa private key và certificate của mình
     */
    private KeyStore createKeyStore() throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null); // Empty keystore
        
        // Thêm private key entry
        Certificate[] certChain = new Certificate[] { selfCertificate };
        ks.setKeyEntry("self", keyPair.getPrivate(), "password".toCharArray(), certChain);
        
        return ks;
    }
    
    /**
     * Tạo TrustStore - trust tất cả peer certificates
     * Trong LAN, ta trust all peers (vì không có CA)
     */
    private KeyStore createTrustStore() throws Exception {
        KeyStore ts = KeyStore.getInstance(KeyStore.getDefaultType());
        ts.load(null, null);
        
        // Thêm self certificate vào trust store
        ts.setCertificateEntry("self", selfCertificate);
        
        return ts;
    }
    
    /**
     * Tạo SSLContext cho SSLSocket/SSLServerSocket
     */
    private SSLContext createSSLContext() throws Exception {
        // KeyManager sử dụng keyStore (private key + cert của mình)
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "password".toCharArray());
        
        // TrustManager - trust all certificates (LAN environment)
        TrustManager[] trustAllCerts = new TrustManager[] {
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return null; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                public void checkServerTrusted(X509Certificate[] certs, String authType) { }
            }
        };
        
        // Tạo SSLContext
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), trustAllCerts, new SecureRandom());
        
        return ctx;
    }
    
    /**
     * Lấy SSLContext để tạo SSLSocket/SSLServerSocket
     */
    public SSLContext getSSLContext() {
        return sslContext;
    }
    
    /**
     * Tạo SSLServerSocket
     */
    public SSLServerSocket createSSLServerSocket(int port) throws IOException {
        SSLServerSocketFactory factory = sslContext.getServerSocketFactory();
        SSLServerSocket serverSocket = (SSLServerSocket) factory.createServerSocket(port);
        
        // Enable all cipher suites và protocols
        serverSocket.setEnabledCipherSuites(serverSocket.getSupportedCipherSuites());
        serverSocket.setNeedClientAuth(false); // Không bắt buộc client cert (vì trust all)
        
        return serverSocket;
    }
    
    /**
     * Tạo SSLSocket kết nối đến peer
     */
    public SSLSocket createSSLSocket(String host, int port) throws IOException {
        SSLSocketFactory factory = sslContext.getSocketFactory();
        SSLSocket socket = (SSLSocket) factory.createSocket();
        
        // Enable all cipher suites
        socket.setEnabledCipherSuites(socket.getSupportedCipherSuites());
        
        return socket;
    }
    
    // ========== Signature Methods ==========
    
    /**
     * Ký message (JOIN, HEARTBEAT, PIN, etc.)
     * 
     * @param message Message cần ký (String)
     * @return Signature (Base64 encoded)
     */
    public String signMessage(String message) throws Exception {
        Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
        sig.initSign(keyPair.getPrivate());
        sig.update(message.getBytes("UTF-8"));
        byte[] signatureBytes = sig.sign();
        return Base64.getEncoder().encodeToString(signatureBytes);
    }
    
    /**
     * Verify signature của message từ peer khác
     * 
     * @param message Message gốc
     * @param signature Signature (Base64 encoded)
     * @param publicKey Public key của peer gửi
     * @return true nếu signature hợp lệ
     */
    public boolean verifySignature(String message, String signature, PublicKey publicKey) {
        try {
            Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
            sig.initVerify(publicKey);
            sig.update(message.getBytes("UTF-8"));
            byte[] signatureBytes = Base64.getDecoder().decode(signature);
            return sig.verify(signatureBytes);
        } catch (Exception e) {
            System.err.println("❌ [Bảo mật] Xác thực chữ ký thất bại: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Thêm public key của peer vào trust list
     */
    public void addTrustedPeerKey(String peerId, PublicKey publicKey) {
        trustedPeerKeys.put(peerId, publicKey);
        System.out.println("✓ [Bảo mật] Đã thêm peer đáng tin cậy: " + peerId);
    }
    
    /**
     * Lấy public key của peer đã trust
     */
    public PublicKey getTrustedPeerKey(String peerId) {
        return trustedPeerKeys.get(peerId);
    }
    
    /**
     * Kiểm tra peer có được trust không
     */
    public boolean isTrustedPeer(String peerId) {
        return trustedPeerKeys.containsKey(peerId);
    }
    
    // ========== Public Key Encoding/Decoding ==========
    
    /**
     * Encode public key thành Base64 string (để gửi trong PeerInfo)
     */
    public static String encodePublicKey(PublicKey publicKey) {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }
    
    /**
     * Decode public key từ Base64 string
     */
    public static PublicKey decodePublicKey(String encodedKey) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(encodedKey);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance(KEY_ALGORITHM);
        return kf.generatePublic(spec);
    }
    
    // ========== Getters ==========
    
    public PublicKey getPublicKey() {
        return keyPair.getPublic();
    }
    
    public PrivateKey getPrivateKey() {
        return keyPair.getPrivate();
    }
    
    public String getPublicKeyEncoded() {
        return encodePublicKey(keyPair.getPublic());
    }
    
    public X509Certificate getSelfCertificate() {
        return selfCertificate;
    }
    
    /**
     * Tạo signed message object (message + signature)
     */
    public SignedMessage createSignedMessage(String messageType, String content) throws Exception {
        String signature = signMessage(messageType + ":" + content);
        return new SignedMessage(messageType, content, peerId, signature);
    }
    
    /**
     * Verify signed message
     */
    public boolean verifySignedMessage(SignedMessage signedMsg, PublicKey senderPublicKey) {
        String message = signedMsg.getMessageType() + ":" + signedMsg.getContent();
        return verifySignature(message, signedMsg.getSignature(), senderPublicKey);
    }
    
    /**
     * Inner class: SignedMessage
     */
    public static class SignedMessage implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private final String messageType;
        private final String content;
        private final String senderId;
        private final String signature;
        
        public SignedMessage(String messageType, String content, String senderId, String signature) {
            this.messageType = messageType;
            this.content = content;
            this.senderId = senderId;
            this.signature = signature;
        }
        
        public String getMessageType() { return messageType; }
        public String getContent() { return content; }
        public String getSenderId() { return senderId; }
        public String getSignature() { return signature; }
    }
}
