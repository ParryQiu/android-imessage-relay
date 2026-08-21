package io.github.parryqiu.androidimessagerelay;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class RelayConfiguration {
    private static final int FORMAT_VERSION = 1;
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "android_imessage_relay_configuration";
    private static final String PREFERENCES = "relay_configuration";
    private static final String ENCRYPTED_VALUE = "encrypted_value";
    private static final String IV = "iv";

    final String endpoint;
    final String clientId;
    final String clientSecret;
    final String messageEncryptionPublicKey;
    final String messageEncryptionKeyFingerprint;
    final String pairingRequestName;
    final String pairingDisplayCode;

    private RelayConfiguration(
            String endpoint,
            String clientId,
            String clientSecret,
            String messageEncryptionPublicKey,
            String messageEncryptionKeyFingerprint,
            String pairingRequestName,
            String pairingDisplayCode) {
        this.endpoint = endpoint;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.messageEncryptionPublicKey = messageEncryptionPublicKey;
        this.messageEncryptionKeyFingerprint = messageEncryptionKeyFingerprint;
        this.pairingRequestName = pairingRequestName;
        this.pairingDisplayCode = pairingDisplayCode;
    }

    static void saveCredentials(Context context, String endpoint, String clientId, String clientSecret)
            throws Exception {
        String normalizedEndpoint = RelayEndpoint.normalize(endpoint);
        String normalizedId = require(clientId, "Cloudflare Access Client ID", 512);
        String normalizedSecret = require(clientSecret, "Cloudflare Access Client Secret", 2048);
        save(context, new RelayConfiguration(
                normalizedEndpoint, normalizedId, normalizedSecret, "", "", "", ""));
    }

    static void savePendingPairing(Context context, String name, String displayCode) throws Exception {
        RelayConfiguration current = requireConfigured(context);
        save(context, new RelayConfiguration(
                current.endpoint,
                current.clientId,
                current.clientSecret,
                "",
                "",
                require(name, "Pairing request name", 256),
                require(displayCode, "Pairing display code", 32)));
    }

    static void completePairing(
            Context context, String publicKeyBase64, String expectedFingerprint) throws Exception {
        RelayConfiguration current = requireConfigured(context);
        byte[] der = Base64.decode(require(publicKeyBase64, "Message encryption public key", 8192), Base64.NO_WRAP);
        RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(der));
        if (publicKey.getModulus().bitLength() < 3072) {
            throw new IllegalArgumentException("The Mac RSA public key must be at least 3072 bits");
        }
        String fingerprint = RelayCrypto.sha256Hex(der);
        if (!fingerprint.equalsIgnoreCase(require(expectedFingerprint, "Key fingerprint", 128))) {
            throw new IllegalArgumentException("The Mac public-key fingerprint does not match");
        }
        save(context, new RelayConfiguration(
                current.endpoint,
                current.clientId,
                current.clientSecret,
                Base64.encodeToString(der, Base64.NO_WRAP),
                fingerprint,
                "",
                ""));
    }

    static RelayConfiguration load(Context context) throws Exception {
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        String encodedValue = preferences.getString(ENCRYPTED_VALUE, null);
        String encodedIv = preferences.getString(IV, null);
        if (encodedValue == null || encodedIv == null) {
            return null;
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                new GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP)));
        return deserialize(cipher.doFinal(Base64.decode(encodedValue, Base64.NO_WRAP)));
    }

    static boolean isConfigured(Context context) {
        try {
            return load(context) != null;
        } catch (Exception error) {
            return false;
        }
    }

    static boolean isReady(Context context) {
        try {
            RelayConfiguration configuration = load(context);
            return configuration != null && !configuration.messageEncryptionPublicKey.isEmpty();
        } catch (Exception error) {
            return false;
        }
    }

    boolean hasPendingPairing() {
        return !pairingRequestName.isEmpty();
    }

    RSAPublicKey messageEncryptionPublicKey() throws Exception {
        if (messageEncryptionPublicKey.isEmpty()) {
            throw new IllegalStateException("Pairing is not complete");
        }
        byte[] der = Base64.decode(messageEncryptionPublicKey, Base64.NO_WRAP);
        return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(der));
    }

    private static RelayConfiguration requireConfigured(Context context) throws Exception {
        RelayConfiguration configuration = load(context);
        if (configuration == null) {
            throw new IllegalStateException("Relay configuration is missing");
        }
        return configuration;
    }

    private static void save(Context context, RelayConfiguration configuration) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(serialize(configuration));
        boolean committed = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(ENCRYPTED_VALUE, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .commit();
        if (!committed) {
            throw new IllegalStateException("Unable to persist encrypted configuration");
        }
    }

    private static byte[] serialize(RelayConfiguration configuration) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(output)) {
            data.writeInt(FORMAT_VERSION);
            writeString(data, configuration.endpoint);
            writeString(data, configuration.clientId);
            writeString(data, configuration.clientSecret);
            writeString(data, configuration.messageEncryptionPublicKey);
            writeString(data, configuration.messageEncryptionKeyFingerprint);
            writeString(data, configuration.pairingRequestName);
            writeString(data, configuration.pairingDisplayCode);
        }
        return output.toByteArray();
    }

    private static RelayConfiguration deserialize(byte[] encoded) throws Exception {
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (data.readInt() != FORMAT_VERSION) {
                throw new IllegalStateException("Unsupported relay configuration version");
            }
            RelayConfiguration configuration = new RelayConfiguration(
                    readString(data, 2048),
                    readString(data, 512),
                    readString(data, 2048),
                    readString(data, 8192),
                    readString(data, 128),
                    readString(data, 256),
                    readString(data, 32));
            if (data.available() != 0) {
                throw new IllegalStateException("Invalid relay configuration");
            }
            return configuration;
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(
                    KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            generator.generateKey();
        }
        return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
    }

    private static String require(String value, String label, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return normalized;
    }

    private static void writeString(DataOutputStream data, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        data.writeInt(bytes.length);
        data.write(bytes);
    }

    private static String readString(DataInputStream data, int maxBytes) throws Exception {
        int length = data.readInt();
        if (length < 0 || length > maxBytes) {
            throw new IllegalStateException("Invalid relay configuration");
        }
        byte[] bytes = new byte[length];
        data.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
