package io.github.parryqiu.androidimessagerelay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

final class MessagePayload {
    private static final SecureRandom RANDOM = new SecureRandom();

    final String id;
    final String sender;
    final String body;
    final long sentAt;

    MessagePayload(String id, String sender, String body, long sentAt) {
        if (id == null || !id.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid message ID");
        }
        if (sender == null || sender.isEmpty()
                || sender.getBytes(StandardCharsets.UTF_8).length > 1024) {
            throw new IllegalArgumentException("Invalid sender");
        }
        if (body == null || body.isEmpty()
                || body.getBytes(StandardCharsets.UTF_8).length > 32 * 1024) {
            throw new IllegalArgumentException("Invalid message body");
        }
        if (sentAt <= 0) {
            throw new IllegalArgumentException("Invalid message time");
        }
        this.id = id;
        this.sender = sender;
        this.body = body;
        this.sentAt = sentAt;
    }

    static String randomId() {
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        StringBuilder result = new StringBuilder(64);
        for (byte item : value) {
            result.append(String.format("%02x", item));
        }
        return result.toString();
    }

    byte[] toEncryptedContentJson() {
        String json = "{\"sender\":\"" + escape(sender)
                + "\",\"body\":\"" + escape(body) + "\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    byte[] serialize() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(output)) {
            writeString(data, id);
            writeString(data, sender);
            writeString(data, body);
            data.writeLong(sentAt);
        }
        return output.toByteArray();
    }

    static MessagePayload deserialize(byte[] encoded) throws IOException {
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(encoded))) {
            String id = readString(data, 256);
            String sender = readString(data, 1024);
            String body = readString(data, 32 * 1024);
            long sentAt = data.readLong();
            if (data.available() != 0) {
                throw new IOException("Unexpected trailing payload data");
            }
            try {
                return new MessagePayload(id, sender, body, sentAt);
            } catch (IllegalArgumentException error) {
                throw new IOException("Invalid message payload", error);
            }
        }
    }

    static String escape(String value) {
        StringBuilder result = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"': result.append("\\\""); break;
                case '\\': result.append("\\\\"); break;
                case '\b': result.append("\\b"); break;
                case '\f': result.append("\\f"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (character < 0x20) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
            }
        }
        return result.toString();
    }

    private static void writeString(DataOutputStream data, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        data.writeInt(bytes.length);
        data.write(bytes);
    }

    private static String readString(DataInputStream data, int maxBytes) throws IOException {
        int length = data.readInt();
        if (length < 0 || length > maxBytes) {
            throw new IOException("Invalid string length");
        }
        byte[] bytes = new byte[length];
        data.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
