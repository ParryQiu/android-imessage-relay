package io.github.parryqiu.androidimessagerelay;

final class PairingRequest {
    final String name;
    final String state;
    final String displayCode;
    final String messageEncryptionPublicKey;
    final String messageEncryptionKeyFingerprint;

    PairingRequest(
            String name,
            String state,
            String displayCode,
            String messageEncryptionPublicKey,
            String messageEncryptionKeyFingerprint) {
        this.name = name;
        this.state = state;
        this.displayCode = displayCode;
        this.messageEncryptionPublicKey = messageEncryptionPublicKey;
        this.messageEncryptionKeyFingerprint = messageEncryptionKeyFingerprint;
    }

    boolean isApproved() {
        return "APPROVED".equals(state);
    }
}
