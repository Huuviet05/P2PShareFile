package org.example.p2psharefile.model;

import java.io.Serializable;

/**
 * SignedMessage - Message đã được ký để chống impersonation
 * 
 * Sử dụng cho:
 * - JOIN/HEARTBEAT messages trong PeerDiscovery
 * - PIN messages trong PINCodeService
 * - Các control messages khác
 * 
 * Format: messageType:content → signature (ECDSA)
 */
public class SignedMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final String messageType;   // "JOIN", "HEARTBEAT", "PIN", etc.
    private final String senderId;      // Peer ID của người gửi
    private final String signature;     // ECDSA signature (Base64)
    private final Object payload;       // Payload data (PeerInfo, ShareSession, etc.)
    
    public SignedMessage(String messageType, String senderId, String signature, Object payload) {
        this.messageType = messageType;
        this.senderId = senderId;
        this.signature = signature;
        this.payload = payload;
    }
    
    public String getMessageType() {
        return messageType;
    }
    
    public String getSenderId() {
        return senderId;
    }
    
    public String getSignature() {
        return signature;
    }
    
    public Object getPayload() {
        return payload;
    }
    
    /**
     * Lấy message cần verify (messageType:senderId:payloadHash)
     */
    public String getMessageToVerify() {
        return messageType + ":" + senderId + ":" + payload.toString();
    }
    
    @Override
    public String toString() {
        return "SignedMessage{" +
                "type='" + messageType + '\'' +
                ", sender='" + senderId + '\'' +
                ", signature='" + signature.substring(0, Math.min(20, signature.length())) + "...'" +
                '}';
    }
}
