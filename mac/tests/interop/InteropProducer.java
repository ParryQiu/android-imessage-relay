import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

public final class InteropProducer {
    private InteropProducer() {}

    public static void main(String[] arguments) throws Exception {
        byte[] relayKeyDer = Base64.getDecoder().decode(arguments[0]);
        PublicKey relayKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(relayKeyDer));
        KeyPairGenerator signingGenerator = KeyPairGenerator.getInstance("EC");
        signingGenerator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair signingKey = signingGenerator.generateKeyPair();
        String devicePublicKey = Base64.getEncoder().encodeToString(signingKey.getPublic().getEncoded());

        byte[] randomId = new byte[32];
        new SecureRandom().nextBytes(randomId);
        String messageId = hex(randomId);
        long sentAt = Long.parseLong(arguments[1]);
        byte[] plaintext = "{\"sender\":\"Example sender\",\"body\":\"Code 123456\"}"
                .getBytes(StandardCharsets.UTF_8);

        KeyGenerator aesGenerator = KeyGenerator.getInstance("AES");
        aesGenerator.init(256);
        SecretKey messageKey = aesGenerator.generateKey();
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, messageKey, new GCMParameterSpec(128, iv));
        aes.updateAAD((messageId + "\n" + sentAt).getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = aes.doFinal(plaintext);

        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, relayKey, new OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        byte[] encryptedKey = rsa.doFinal(messageKey.getEncoded());
        String envelope = "{\"messageId\":\"" + messageId + "\",\"sentAt\":" + sentAt
                + ",\"encryptedKey\":\"" + Base64.getEncoder().encodeToString(encryptedKey)
                + "\",\"iv\":\"" + Base64.getEncoder().encodeToString(iv)
                + "\",\"ciphertext\":\"" + Base64.getEncoder().encodeToString(ciphertext) + "\"}";
        String canonical = "POST\n/v1/messages\n" + hex(MessageDigest.getInstance("SHA-256")
                .digest(envelope.getBytes(StandardCharsets.UTF_8)));
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(signingKey.getPrivate());
        signature.update(canonical.getBytes(StandardCharsets.UTF_8));

        System.out.println(devicePublicKey);
        System.out.println(envelope);
        System.out.println(Base64.getEncoder().encodeToString(signature.sign()));
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(String.format("%02x", item));
        }
        return result.toString();
    }
}
