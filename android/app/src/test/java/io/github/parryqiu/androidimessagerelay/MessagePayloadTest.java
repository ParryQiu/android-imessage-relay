package io.github.parryqiu.androidimessagerelay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public final class MessagePayloadTest {
    @Test
    public void randomIdsAreOpaqueAndUnique() {
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < 1000; index++) {
            String id = MessagePayload.randomId();
            assertEquals(64, id.length());
            assertFalse(ids.contains(id));
            ids.add(id);
        }
    }

    @Test
    public void binaryQueuePayloadRoundTrips() throws Exception {
        MessagePayload original = new MessagePayload(
                "a".repeat(64), "Example sender", "Chinese verification code 123456", 456L);
        MessagePayload restored = MessagePayload.deserialize(original.serialize());
        assertEquals(original.id, restored.id);
        assertEquals(original.sender, restored.sender);
        assertEquals(original.body, restored.body);
        assertEquals(original.sentAt, restored.sentAt);
    }

    @Test
    public void encryptedContentExcludesTransportMetadata() {
        MessagePayload payload = new MessagePayload(
                "b".repeat(64), "Example sender", "Message body", 456L);
        assertEquals(
                "{\"sender\":\"Example sender\",\"body\":\"Message body\"}",
                new String(payload.toEncryptedContentJson(), StandardCharsets.UTF_8));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidMessageId() {
        new MessagePayload("predictable", "Example sender", "Message", 456L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOversizedMessageBody() {
        new MessagePayload("c".repeat(64), "Example sender", "x".repeat(32 * 1024 + 1), 456L);
    }
}
