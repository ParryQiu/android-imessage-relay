package io.github.parryqiu.androidimessagerelay;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;

final class RelayCrypto {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String SIGNING_ALIAS = "android_imessage_relay_signing_key";
    private static final SecureRandom RANDOM = new SecureRandom();

    private RelayCrypto() {}

    static KeyPair getOrCreateSigningKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (!keyStore.containsAlias(SIGNING_ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
            generator.initialize(new KeyGenParameterSpec.Builder(
                    SIGNING_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build());
            generator.generateKeyPair();
        }
        PrivateKey privateKey = (PrivateKey) keyStore.getKey(SIGNING_ALIAS, null);
        return new KeyPair(keyStore.getCertificate(SIGNING_ALIAS).getPublicKey(), privateKey);
    }

    static String publicKeyBase64() throws Exception {
        return Base64.encodeToString(getOrCreateSigningKey().getPublic().getEncoded(), Base64.NO_WRAP);
    }

    static String createPairingNonce() {
        byte[] nonce = new byte[32];
        RANDOM.nextBytes(nonce);
        return Base64.encodeToString(nonce, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
    }

    static String pairingProof(String publicKey, String nonce) throws Exception {
        return sign(("PAIRING_V1\n" + publicKey + "\n" + nonce)
                .getBytes(StandardCharsets.UTF_8));
    }

    static String pairingDisplayCode(String publicKey) throws Exception {
        String digest = sha256Hex(publicKey.getBytes(StandardCharsets.US_ASCII)).toUpperCase();
        return digest.substring(0, 4) + "-" + digest.substring(4, 8);
    }

    static String requestSignature(String method, String path, byte[] body) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String bodyHash = sha256Hex(body);
        return sign((method + "\n" + path + "\n" + bodyHash)
                .getBytes(StandardCharsets.UTF_8));
    }

    static String sign(byte[] body) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(getOrCreateSigningKey().getPrivate());
        signature.update(body);
        return Base64.encodeToString(signature.sign(), Base64.NO_WRAP);
    }

    static String sha256Hex(byte[] value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder(hash.length * 2);
        for (byte item : hash) {
            result.append(String.format("%02x", item));
        }
        return result.toString();
    }
}
