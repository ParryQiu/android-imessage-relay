package io.github.parryqiu.androidimessagerelay;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

final class RelayApi {
    private static final int MAX_RESPONSE_BYTES = 8192;
    private final Context context;

    RelayApi(Context context) {
        this.context = context.getApplicationContext();
    }

    PairingRequest createPairingRequest() throws Exception {
        String publicKey = RelayCrypto.publicKeyBase64();
        String nonce = RelayCrypto.createPairingNonce();
        JSONObject request = new JSONObject()
                .put("devicePublicKey", publicKey)
                .put("nonce", nonce)
                .put("proof", RelayCrypto.pairingProof(publicKey, nonce));
        PairingRequest response = parsePairing(request("POST", "/v1/pairingRequests",
                request.toString().getBytes(StandardCharsets.UTF_8), null));
        if (!RelayCrypto.pairingDisplayCode(publicKey).equals(response.displayCode)) {
            throw new IOException("Relay returned an invalid pairing verification code");
        }
        return response;
    }

    PairingRequest getPairingRequest(String name) throws Exception {
        if (!name.matches("pairingRequests/[A-Za-z0-9_-]{16,128}")) {
            throw new IllegalArgumentException("Invalid pairing request name");
        }
        String path = "/v1/" + name;
        byte[] emptyBody = new byte[0];
        return parsePairing(request("GET", path, emptyBody,
                RelayCrypto.requestSignature("GET", path, emptyBody)));
    }

    void send(MessagePayload payload) throws Exception {
        RelayConfiguration configuration = RelayConfiguration.load(context);
        if (configuration == null || !RelayConfiguration.isReady(context)) {
            throw new IllegalStateException("Pairing is not complete");
        }
        long sentAt = System.currentTimeMillis() / 1000L;
        byte[] body = EncryptedEnvelope.create(configuration, payload, sentAt);
        String path = "/v1/messages";
        JSONObject response = request("POST", path, body,
                RelayCrypto.requestSignature("POST", path, body));
        if (!response.optString("name").equals("messages/" + payload.id)) {
            throw new IOException("Relay returned an invalid message resource");
        }
    }

    private JSONObject request(String method, String path, byte[] body, String signature)
            throws Exception {
        RelayConfiguration configuration = RelayConfiguration.load(context);
        if (configuration == null) {
            throw new IllegalStateException("Relay configuration is missing");
        }
        HttpsURLConnection connection = (HttpsURLConnection) new URL(
                configuration.endpoint + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("CF-Access-Client-Id", configuration.clientId);
        connection.setRequestProperty("CF-Access-Client-Secret", configuration.clientSecret);
        if (signature != null) {
            connection.setRequestProperty("X-Relay-Signature", signature);
        }
        if (!"GET".equals(method)) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
        }

        try {
            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            JSONObject response = new JSONObject(input == null ? "{}" : readResponse(input));
            if (status < 200 || status >= 300) {
                JSONObject error = response.optJSONObject("error");
                String safeStatus = error == null ? "UNKNOWN" : error.optString("status", "UNKNOWN");
                throw new IOException("Relay request failed: " + safeStatus + " (HTTP " + status + ")");
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private static PairingRequest parsePairing(JSONObject response) throws IOException {
        PairingRequest request = new PairingRequest(
                response.optString("name"),
                response.optString("state"),
                response.optString("displayCode"),
                response.optString("messageEncryptionPublicKey"),
                response.optString("messageEncryptionKeyFingerprint"));
        if (!request.name.matches("pairingRequests/[A-Za-z0-9_-]{16,128}")
                || request.state.isEmpty()) {
            throw new IOException("Relay returned an invalid pairing resource");
        }
        return request;
    }

    private static String readResponse(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = source.read(buffer)) != -1) {
                if (output.size() + read > MAX_RESPONSE_BYTES) {
                    throw new IOException("Relay response exceeded the size limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
