package com.englishpods.generation;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GenerationHttpClientTest {
    @Test void oversizeHasSpecificSafeMessageAndDoesNotRetry() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        server.createContext("/audio", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(200, 1024);
            exchange.getResponseBody().write(new byte[1024]); exchange.close();
        });
        server.start();
        try {
            var client = new GenerationHttpClient(HttpClient.newHttpClient(), 100);
            var error = assertThrows(GenerationException.class, () -> client.download("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/audio?signature=never-expose"));
            assertTrue(error.getMessage().contains("100"));
            assertEquals(1, calls.get());
            assertFalse(error.getMessage().contains("signature"));
            assertNull(error.getCause());
        } finally { server.stop(0); }
    }

    @Test void wrappedNetworkFailuresHaveDistinctCategoriesWithoutExceptionDetails() throws Exception {
        var timeout = new java.net.http.HttpTimeoutException("signed-url=secret");
        var tls = new javax.net.ssl.SSLHandshakeException("credential=secret");
        var dns = new java.net.ConnectException("url=secret");
        dns.initCause(new java.nio.channels.UnresolvedAddressException());
        var connect = new java.net.ConnectException("url=secret");
        var errors = java.util.List.of(timeout, tls, dns, connect, new IOException("body=secret"));
        var categories = new java.util.HashSet<String>();
        for (var underlying : errors) {
            var client = mock(HttpClient.class);
            when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                    .thenThrow(new IOException("outer secret", underlying));
            var error = assertThrows(GenerationException.class,
                    () -> new GenerationHttpClient(client, 100).download("https://example.invalid/audio?secret"));
            assertFalse(error.getMessage().contains("secret"));
            assertNull(error.getCause());
            categories.add(error.getMessage());
            verify(client, times(3)).send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        }
        assertEquals(5, categories.size());
    }
}
