package io.github.parryqiu.androidimessagerelay;

import java.net.URI;

final class RelayEndpoint {
    private RelayEndpoint() {}

    static String normalize(String value) {
        try {
            URI uri = new URI(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
                throw new IllegalArgumentException("A bare HTTPS relay endpoint is required");
            }
            int port = uri.getPort();
            return new URI("https", null, uri.getHost().toLowerCase(), port, null, null, null).toString();
        } catch (Exception error) {
            if (error instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) error;
            }
            throw new IllegalArgumentException("Invalid relay endpoint", error);
        }
    }
}
