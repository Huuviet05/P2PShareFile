package org.example.p2psharefile.test;

import org.example.p2psharefile.model.PeerInfo;
import org.example.p2psharefile.model.SignedMessage;

import javax.net.ssl.*;
import java.io.ObjectOutputStream;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;

/**
 * FakeJoinAttackTrustAll - quick test: trust-all SSLContext (UNSAFE, chỉ dùng cho testing)
 */
public class FakeJoinAttackTrustAll {
    public static void main(String[] args) {
        String targetHost = "192.168.4.18";
        int targetPort = 8888;

        try {
            // PeerInfo giả
            PeerInfo fakePeer = new PeerInfo("HACKER-ID-0001", "192.168.4.100", 9999, "FAKE_PEER_HACKER");
            fakePeer.setPublicKey(Base64.getEncoder().encodeToString("FAKE_PUBLIC_KEY".getBytes("UTF-8")));

            // Signature giả (Base64)
            String fakeSignature = Base64.getEncoder().encodeToString("NOT_A_REAL_SIGNATURE".getBytes("UTF-8"));

            SignedMessage fakeMsg = new SignedMessage("JOIN", "HACKER-ID-0001", fakeSignature, fakePeer);

            // --- Create SSLContext that trusts all certs (INSECURE, for test only) ---
            TrustManager[] trustAll = new TrustManager[] {
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                    public void checkServerTrusted(X509Certificate[] certs, String authType) { }
                }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new SecureRandom());

            SSLSocketFactory factory = sslContext.getSocketFactory();
            SSLSocket socket = (SSLSocket) factory.createSocket(targetHost, targetPort);
            socket.startHandshake();

            ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
            oos.writeObject(fakeMsg);
            oos.flush();

            System.out.println("✅ Đã gửi fake SignedMessage (JOIN) tới " + targetHost + ":" + targetPort);
            oos.close();
            socket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}