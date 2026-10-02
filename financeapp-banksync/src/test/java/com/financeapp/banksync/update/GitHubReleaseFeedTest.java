package com.financeapp.banksync.update;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class GitHubReleaseFeedTest {

    private HttpServer server;
    private volatile int status = 200;
    private volatile String body = "";
    private volatile String userAgent;

    private GitHubReleaseFeed feed() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/releases/latest");
        return new GitHubReleaseFeed(uri, "SkyWace/Finance-APP", HttpClient.newHttpClient());
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readsTheLatestRelease() throws Exception {
        GitHubReleaseFeed feed = feed();
        body = """
                {"tag_name":"v0.2.4","html_url":"https://github.com/SkyWace/Finance-APP/releases/tag/v0.2.4",
                 "published_at":"2026-10-20T08:00:00Z","draft":false,"prerelease":false,"body":"..."}""";
        var release = feed.latest().orElseThrow();
        assertEquals("0.2.4", release.version().toString());
        assertEquals("https://github.com/SkyWace/Finance-APP/releases/tag/v0.2.4", release.pageUrl());
        assertEquals(LocalDate.of(2026, 10, 20), release.publishedOn());
        assertEquals("FinanceApp-update-check", userAgent, "aucune information personnelle dans la requete");
    }

    @Test
    void rejectsUnexpectedLinksAndHandlesErrors() throws Exception {
        GitHubReleaseFeed feed = feed();
        body = """
                {"tag_name":"v9.9.9","html_url":"https://evil.example/download.exe"}""";
        assertTrue(feed.latest().isEmpty(), "lien hors du depot : rien n'est propose");

        body = """
                {"tag_name":"v0.3.0","html_url":"https://github.com/SkyWace/Finance-APP/releases/tag/v0.3.0","prerelease":true}""";
        assertTrue(feed.latest().isEmpty(), "pre-version ignoree");

        status = 404;
        body = "";
        assertTrue(feed.latest().isEmpty(), "aucune release");

        status = 500;
        assertThrows(IOException.class, feed::latest);
    }

    @Test
    void refusesAMalformedRepositoryName() {
        assertThrows(IllegalArgumentException.class, () -> new GitHubReleaseFeed("../../evil"));
    }
}
