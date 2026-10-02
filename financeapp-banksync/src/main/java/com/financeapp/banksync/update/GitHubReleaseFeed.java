package com.financeapp.banksync.update;

import com.financeapp.core.port.ReleaseFeed;
import com.financeapp.core.update.AvailableRelease;
import com.financeapp.core.update.ReleaseVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Derniere release publiee sur GitHub ({@code GET /repos/<depot>/releases/latest}).
 * Requete anonyme : aucune donnee de l'utilisateur n'est envoyee. Le lien renvoye
 * n'est retenu que s'il pointe vers les releases du depot attendu.
 */
public final class GitHubReleaseFeed implements ReleaseFeed {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_BODY = 512 * 1024;

    private final URI endpoint;
    private final String repository;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    /** @param repository "proprietaire/depot", par exemple "SkyWace/Finance-APP" */
    public GitHubReleaseFeed(String repository) {
        this(URI.create("https://api.github.com/repos/" + repository + "/releases/latest"), repository,
                HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    public GitHubReleaseFeed(URI endpoint, String repository, HttpClient http) {
        if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Depot invalide : " + repository);
        }
        this.endpoint = endpoint;
        this.repository = repository;
        this.http = http;
    }

    @Override
    public Optional<AvailableRelease> latest() throws IOException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "FinanceApp-update-check")
                .GET().build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Verification interrompue", e);
        }
        if (response.statusCode() == 404) {
            return Optional.empty(); // aucune release publiee
        }
        if (response.statusCode() != 200) {
            throw new IOException("GitHub a repondu " + response.statusCode());
        }
        if (response.body().length > MAX_BODY) {
            throw new IOException("Reponse trop volumineuse");
        }
        JsonNode release = json.readTree(response.body());
        if (release.path("draft").asBoolean(false) || release.path("prerelease").asBoolean(false)) {
            return Optional.empty();
        }
        Optional<ReleaseVersion> version = ReleaseVersion.parse(release.path("tag_name").asString(""));
        String page = release.path("html_url").asString("");
        String expectedPrefix = "https://github.com/" + repository + "/releases/";
        if (version.isEmpty() || !page.startsWith(expectedPrefix)) {
            return Optional.empty(); // reponse inattendue : on ne propose aucun lien
        }
        return Optional.of(new AvailableRelease(version.get(), page, date(release.path("published_at").asString(""))));
    }

    private static LocalDate date(String text) {
        try {
            return text.isBlank() ? null : OffsetDateTime.parse(text).toLocalDate();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
