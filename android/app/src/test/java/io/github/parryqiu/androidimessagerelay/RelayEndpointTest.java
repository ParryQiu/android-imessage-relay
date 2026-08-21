package io.github.parryqiu.androidimessagerelay;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class RelayEndpointTest {
    @Test
    public void normalizesBareHttpsEndpoint() {
        assertEquals("https://relay.example.com", RelayEndpoint.normalize(
                " https://RELAY.example.com/ "));
        assertEquals("https://relay.example.com:8443", RelayEndpoint.normalize(
                "https://relay.example.com:8443"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsCleartextEndpoint() {
        RelayEndpoint.normalize("http://relay.example.com");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsCredentialsInEndpoint() {
        RelayEndpoint.normalize("https://user:password@relay.example.com");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEndpointPath() {
        RelayEndpoint.normalize("https://relay.example.com/private");
    }
}
