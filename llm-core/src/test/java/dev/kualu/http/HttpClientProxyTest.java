package dev.kualu.http;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpClientProxyTest {

    @Test
    void parsesHostPortAndSchemeForms() {
        for (String spec : List.of("127.0.0.1:7890", "http://127.0.0.1:7890", "https://127.0.0.1:7890")) {
            ProxySelector selector = HttpClient.parseProxy(spec);
            List<Proxy> proxies = selector.select(URI.create("https://example.com"));
            assertEquals(1, proxies.size());
            Proxy proxy = proxies.get(0);
            assertEquals(Proxy.Type.HTTP, proxy.type());
            InetSocketAddress address = (InetSocketAddress) proxy.address();
            assertEquals("127.0.0.1", address.getHostString());
            assertEquals(7890, address.getPort());
        }
    }

    @Test
    void hostWithoutPortDefaultsTo80() {
        Proxy proxy = HttpClient.parseProxy("proxy.local").select(URI.create("https://x")).get(0);
        assertEquals(80, ((InetSocketAddress) proxy.address()).getPort());
    }

    @Test
    void malformedSpecsAreRejected() {
        for (String spec : List.of("", "host:notaport", "host:0", "host:99999", ":7890")) {
            assertThrows(IllegalArgumentException.class, () -> HttpClient.parseProxy(spec), spec);
        }
    }

    @Test
    void explicitNullProxyBuildsDirectClient() {
        assertNotNull(new HttpClient(java.time.Duration.ofSeconds(1), null));
    }
}
