package org.example.p2psharefile.security;

/**
 * Test SecurityManager certificate generation
 * Run this to verify certificate creation works on your Java version
 */
public class SecurityManagerTest {
    
    public static void main(String[] args) {
        System.out.println("=== Security Manager Test ===\n");
        
        try {
            System.out.println("Java Version: " + System.getProperty("java.version"));
            System.out.println("Java Vendor: " + System.getProperty("java.vendor"));
            System.out.println();
            
            // Test 1: Tạo SecurityManager
            System.out.println("Kiểm tra 1: Tạo SecurityManager...");
            SecurityManager secMgr = new SecurityManager("test-peer-001", "TestPeer");
            System.out.println("✅ Đã tạo SecurityManager thành công!\n");
            
            // Test 2: Check keypair
            System.out.println("Test 2: Checking keypair...");
            System.out.println("  Algorithm: " + secMgr.getPublicKey().getAlgorithm());
            System.out.println("  Format: " + secMgr.getPublicKey().getFormat());
            System.out.println("  Public Key Length: " + secMgr.getPublicKeyEncoded().length() + " chars");
            System.out.println("✅ Keypair OK!\n");
            
            // Test 3: Check certificate
            System.out.println("Test 3: Checking certificate...");
            System.out.println("  Subject: " + secMgr.getSelfCertificate().getSubjectX500Principal());
            System.out.println("  Issuer: " + secMgr.getSelfCertificate().getIssuerX500Principal());
            System.out.println("  Valid From: " + secMgr.getSelfCertificate().getNotBefore());
            System.out.println("  Valid Until: " + secMgr.getSelfCertificate().getNotAfter());
            System.out.println("  Signature Algorithm: " + secMgr.getSelfCertificate().getSigAlgName());
            System.out.println("✅ Certificate OK!\n");
            
            // Test 4: Test signing and verification
            System.out.println("Test 4: Testing message signing...");
            String testMessage = "Hello P2P World!";
            String signature = secMgr.signMessage(testMessage);
            System.out.println("  Message: " + testMessage);
            System.out.println("  Signature: " + signature.substring(0, Math.min(40, signature.length())) + "...");
            
            boolean verified = secMgr.verifySignature(testMessage, signature, secMgr.getPublicKey());
            System.out.println("  Verification: " + (verified ? "✅ PASS" : "❌ FAIL"));
            
            if (!verified) {
                System.err.println("❌ Xác thực chữ ký thất bại!");
                System.exit(1);
            }
            System.out.println();
            
            // Test 5: Test SSL Context
            System.out.println("Test 5: Testing SSL Context...");
            System.out.println("  Protocol: " + secMgr.getSSLContext().getProtocol());
            System.out.println("  Provider: " + secMgr.getSSLContext().getProvider().getName());
            System.out.println("✅ SSL Context OK!\n");
            
            System.out.println("=================================");
            System.out.println("✅ ALL TESTS PASSED!");
            System.out.println("=================================");
            System.out.println("\nYour Java environment is compatible with P2P Security features.");
            
        } catch (Exception e) {
            System.err.println("\n❌ TEST FAILED!");
            System.err.println("Error: " + e.getMessage());
            System.err.println("\nStack trace:");
            e.printStackTrace();
            System.err.println("\n=== Troubleshooting ===");
            System.err.println("1. Check Java version (requires Java 8, 11, 17, or 21)");
            System.err.println("2. Ensure sun.security.x509 internal APIs are accessible");
            System.err.println("3. Consider adding Bouncy Castle dependency if issues persist");
            System.exit(1);
        }
    }
}
