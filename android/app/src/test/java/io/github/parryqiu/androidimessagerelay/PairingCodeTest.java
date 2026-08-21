package io.github.parryqiu.androidimessagerelay;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class PairingCodeTest {
    @Test
    public void displayCodeIsDerivedFromDevicePublicKey() throws Exception {
        assertEquals("F363-A114", RelayCrypto.pairingDisplayCode("synthetic-device-public-key"));
    }
}
