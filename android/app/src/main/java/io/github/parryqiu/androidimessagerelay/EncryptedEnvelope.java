package io.github.parryqiu.androidimessagerelay;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

final class EncryptedEnvelope {
    private static final SecureRandom RANDOM = new SecureRandom();

    private EncryptedEnvelope() {}

    static byte[] create(RelayConfiguration configuration, MessagePayload payload, long sentAt)
            throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
        keyGenerator.init(256);
        SecretKey messageKey = keyGenerator.generateKey();

        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        byte[] aad = (payload.id + "\n" + sentAt).getBytes(StandardCharsets.UTF_8);
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, messageKey, new GCMParameterSpec(128, iv));
        aes.updateAAD(aad);
        byte[] ciphertext = aes.doFinal(payload.toEncryptedContentJson());

        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, configuration.messageEncryptionPublicKey(),
                new OAEPParameterSpec(
                        "SHA-256",
                        "MGF1",
                        MGF1ParameterSpec.SHA256,
                        PSource.PSpecified.DEFAULT));
        byte[] encryptedKey = rsa.doFinal(messageKey.getEncoded());

        String json = "{\"messageId\":\"" + MessagePayload.escape(payload.id)
                + "\",\"sentAt\":" + sentAt
                + ",\"encryptedKey\":\"" + encode(encryptedKey)
                + "\",\"iv\":\"" + encode(iv)
                + "\",\"ciphertext\":\"" + encode(ciphertext) + "\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String encode(byte[] value) {
        return Base64.encodeToString(value, Base64.NO_WRAP);
    }
}
