package com.financeapp.banksync.update;

import com.financeapp.core.port.ReleaseFeed;
import com.financeapp.core.update.AvailableRelease;
import com.financeapp.core.update.ReleaseVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Derniere release publiee sur GitHub ({@code GET /repos/<depot>/releases/latest}).
 * Requete anonyme : aucune donnee de l'utilisateur n'est envoyee. Le lien renvoye
 * n'est retenu que s'il pointe vers les releases du depot attendu.
 *
 * <p>Si l'API ne repond pas (delai depasse, limite de requetes, hote filtre), on lit la
 * redirection de la page publique {@code https://github.com/<depot>/releases/latest}.
 * HTTP/1.1 et proxy du systeme : certains antivirus et proxys bloquent HTTP/2.
 */
public final class GitHubReleaseFeed implements ReleaseFeed {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_BODY = 512 * 1024;

    private final URI endpoint;
    private final URI fallback;
    private final String repository;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    /** @param repository "proprietaire/depot", par exemple "SkyWace/Finance-APP" */
    public GitHubReleaseFeed(String repository) {
        this(URI.create("https://api.github.com/repos/" + repository + "/releases/latest"),
                URI.create("https://github.com/" + repository + "/releases/latest"), repository,
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .proxy(ProxySelector.getDefault())
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build());
    }

    /** Sans second essai (tests). */
    public GitHubReleaseFeed(URI endpoint, String repository, HttpClient http) {
        this(endpoint, null, repository, http);
    }

    /**
     * @param fallback page publique qui redirige vers la derniere release (null : pas de second essai).
     *                 Le client ne doit pas suivre les redirections.
     */
    public GitHubReleaseFeed(URI endpoint, URI fallback, String repository, HttpClient http) {
        if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Depot invalide : " + repository);
        }
        this.endpoint = endpoint;
        this.fallback = fallback;
        this.repository = repository;
        this.http = http;
    }

    @Override
    public Optional<AvailableRelease> latest() throws IOException {
        try {
            return fromApi();
        } catch (IOException apiError) {
            if (fallback == null) {
                throw readable(apiError);
            }
            try {
                return fromPage();
            } catch (IOException pageError) {
                throw readable(apiError);
            }
        }
    }

    private Optional<AvailableRelease> fromApi() throws IOException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "FinanceApp-update-check")
                .GET().build();
        HttpResponse<byte[]> response = send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 404) {
            return Optional.empty(); // aucune release publiee
        }
        if (response.statusCode() != 200) {
            throw new IOException("GitHub a répondu " + response.statusCode());
        }
        if (response.body().length > MAX_BODY) {
            throw new IOException("réponse trop volumineuse");
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

    /** Page publique : {@code 302 Location: https://github.com/<depot>/releases/tag/vX.Y.Z}. */
    private Optional<AvailableRelease> fromPage() throws IOException {
        HttpRequest request = HttpRequest.newBuilder(fallback)
                .timeout(TIMEOUT)
                .header("User-Agent", "FinanceApp-update-check")
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<Void> response = send(request, HttpResponse.BodyHandlers.discarding());
        int code = response.statusCode();
        if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) {
            throw new IOException("GitHub a répondu " + code);
        }
        String location = response.headers().firstValue("Location").orElse("");
        String tagPrefix = "https://github.com/" + repository + "/releases/tag/";
        if (!location.startsWith(tagPrefix)) {
            return Optional.empty(); // aucune release publiee, ou redirection inattendue
        }
        String tag = location.substring(tagPrefix.length());
        Optional<ReleaseVersion> version = ReleaseVersion.parse(tag);
        if (version.isEmpty() || !tag.matches("[A-Za-z0-9_.-]+")) {
            return Optional.empty();
        }
        return Optional.of(new AvailableRelease(version.get(), location, null));
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            return http.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("vérification interrompue", e);
        }
    }

    /** Message comprehensible a la place de "request timed out" ou d'un message vide. */
    static IOException readable(IOException e) {
        if (e instanceof HttpTimeoutException) {
            return new IOException("GitHub n'a pas répondu à temps", e);
        }
        if (e instanceof java.net.ConnectException || e instanceof java.net.UnknownHostException
                || e.getCause() instanceof java.nio.channels.UnresolvedAddressException) {
            return new IOException("impossible de joindre GitHub", e);
        }
        if (e.getMessage() == null || e.getMessage().isBlank()) {
            return new IOException(e.getClass().getSimpleName(), e);
        }
        return e;
    }

    private static LocalDate date(String text) {
        try {
            return text.isBlank() ? null : OffsetDateTime.parse(text).toLocalDate();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
