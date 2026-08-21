package io.github.parryqiu.androidimessagerelay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.KeyPair;
import java.security.KeyPairGenerator;

@RunWith(AndroidJUnit4.class)
public final class SecurityStateTest {
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("relay_configuration", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("queue_metrics", Context.MODE_PRIVATE).edit().clear().commit();
        context.deleteDatabase("relay_queue.db");
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("relay_configuration", Context.MODE_PRIVATE).edit().clear().commit();
        context.deleteDatabase("relay_queue.db");
    }

    @Test
    public void configurationIsFailClosedAndEncryptedAtRest() throws Exception {
        assertFalse(RelayConfiguration.isConfigured(context));
        assertFalse(RelayConfiguration.isReady(context));
        RelayConfiguration.saveCredentials(
                context, "https://relay.example.com", "client-id", "client-secret");
        assertTrue(RelayConfiguration.isConfigured(context));
        assertFalse(RelayConfiguration.isReady(context));
        String encrypted = context.getSharedPreferences("relay_configuration", Context.MODE_PRIVATE)
                .getString("encrypted_value", "");
        assertFalse(encrypted.contains("client-secret"));
        assertFalse(encrypted.contains("relay.example.com"));
    }

    @Test
    public void pairingPinsVerifiedRsaKey() throws Exception {
        RelayConfiguration.saveCredentials(
                context, "https://relay.example.com", "client-id", "client-secret");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        KeyPair keyPair = generator.generateKeyPair();
        String encoded = android.util.Base64.encodeToString(
                keyPair.getPublic().getEncoded(), android.util.Base64.NO_WRAP);
        String fingerprint = RelayCrypto.sha256Hex(keyPair.getPublic().getEncoded());
        RelayConfiguration.completePairing(context, encoded, fingerprint);
        assertTrue(RelayConfiguration.isReady(context));
        assertEquals(fingerprint, RelayConfiguration.load(context).messageEncryptionKeyFingerprint);
    }

    @Test
    public void queueDropsExpiredMessagesWithoutPersistingBodiesInMetrics() throws Exception {
        MessagePayload payload = new MessagePayload(
                MessagePayload.randomId(), "Example sender", "Code 123456", 100L);
        try (SecureQueue queue = new SecureQueue(context)) {
            queue.enqueue(payload);
            queue.getWritableDatabase().execSQL("UPDATE queue SET created_at = 0");
            assertNull(queue.nextReady(System.currentTimeMillis() / 1000L));
            assertEquals(1L, queue.droppedCount());
            assertFalse(context.getSharedPreferences("queue_metrics", Context.MODE_PRIVATE)
                    .getAll().toString().contains("123456"));
        }
    }

    @Test
    public void queueEnforcesRecordLimit() throws Exception {
        try (SecureQueue queue = new SecureQueue(context)) {
            for (int index = 0; index <= SecureQueue.MAX_MESSAGES; index++) {
                queue.enqueue(new MessagePayload(
                        MessagePayload.randomId(), "Example sender", "Synthetic message", 100L + index));
            }
            assertEquals(SecureQueue.MAX_MESSAGES, queue.count());
            assertEquals(1L, queue.droppedCount());
        }
    }
}
