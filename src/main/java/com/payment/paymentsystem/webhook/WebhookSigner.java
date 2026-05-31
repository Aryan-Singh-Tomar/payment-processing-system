package com.payment.paymentsystem.webhook;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes and verifies HMAC-SHA256 signatures for webhook deliveries.
 *
 * The signature covers: timestamp + "." + body
 * This binds the signature to a specific moment in time, preventing replay
 * attacks within the timestamp tolerance window.
 *
 * Wire format of the signature header:
 *   X-Webhook-Signature: sha256=<hex-encoded HMAC>
 *
 * Receivers compute the same HMAC and compare with constant-time comparison.
 */
@Component
public class WebhookSigner {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256";

    /**
     * Compute the signature for a webhook delivery.
     *
     * @param secret      shared secret (between sender and receiver)
     * @param timestamp   epoch-seconds timestamp
     * @param body        the JSON body that will be sent
     * @return the value to put in the X-Webhook-Signature header
     */
    public String sign(String secret, long timestamp, String body){
        String signedPayload = timestamp + " " + body;
        byte[] hmac = computeHmac(secret, signedPayload);
        return SIGNATURE_PREFIX + HexFormat.of().formatHex(hmac);
    }

    /**
     * Verify a received signature.
     *
     * @param secret             the receiver's copy of the shared secret
     * @param timestamp          epoch-seconds from the X-Webhook-Timestamp header
     * @param body               the raw request body
     * @param receivedSignature  the value from the X-Webhook-Signature header
     * @return true if the signature is valid
     */
    public boolean verify(String secret, long timestamp, String body, String receivedSignature){
        if(receivedSignature == null || !receivedSignature.startsWith(SIGNATURE_PREFIX)){
            return false;
        }
        String expected = sign(secret, timestamp, body);
        return constantTimeEquals(expected, receivedSignature);
    }


    private byte[] computeHmac(String secret, String payload){
        try{
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException  |InvalidKeyException ex) {
            // HmacSHA256 is required by every JVM; this should be unreachable.
            throw new IllegalStateException("HMAC computation failed", ex);
        }
    }


    private boolean constantTimeEquals(String a, String b){
        if(a.length() != b.length()){
            return false;
        }
        int diff = 0;
        for(int i = 0; i < a.length(); i++){
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
