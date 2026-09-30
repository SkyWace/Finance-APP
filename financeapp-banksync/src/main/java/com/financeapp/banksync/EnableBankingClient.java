package com.financeapp.banksync;

import com.financeapp.core.banksync.BankInfo;
import com.financeapp.core.banksync.BankSession;
import com.financeapp.core.banksync.RemoteAccount;
import com.financeapp.core.banksync.RemoteTransaction;
import com.financeapp.core.port.BankSyncClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Client de l'API Enable Banking (information sur les comptes uniquement).
 * Aucun endpoint de paiement n'est appele. Les erreurs sont converties en
 * messages destines a l'utilisateur, sans donnees financieres.
 */
public final class EnableBankingClient implements BankSyncClient {

    public static final URI PRODUCTION = URI.create("https://api.enablebanking.com");
    static final int MAX_PAGES = 200;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final URI base;
    private final HttpClient http;
    private final JwtSigner signer;
    private final JsonMapper json;

    public EnableBankingClient(URI base, String applicationId, RSAPrivateKey key, HttpClient http, Clock clock) {
        this.base = checkedBase(base);
        this.http = http;
        this.json = JsonMapper.builder().build();
        this.signer = new JwtSigner(applicationId, key, clock, json);
    }

    /** HTTPS obligatoire ; HTTP n'est accepte que sur la boucle locale (tests, serveur simule). */
    static URI checkedBase(URI base) {
        String host = base.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
        if (!"https".equalsIgnoreCase(base.getScheme()) && !("http".equalsIgnoreCase(base.getScheme()) && loopback)) {
            throw new IllegalArgumentException("Adresse de l'API refusée (HTTPS obligatoire) : " + base);
        }
        String text = base.toString();
        return URI.create(text.endsWith("/") ? text.substring(0, text.length() - 1) : text);
    }

    @Override
    public List<BankInfo> banks(String country) {
        JsonNode body = send("GET", "/aspsps?country=" + encode(country), null);
        List<BankInfo> result = new ArrayList<>();
        for (JsonNode a : body.path("aspsps")) {
            JsonNode types = a.path("psu_types");
            boolean personal = types.isMissingNode() || types.isEmpty();
            for (JsonNode t : types) {
                personal |= "personal".equals(t.asString());
            }
            if (!personal) {
                continue;
            }
            long seconds = a.path("maximum_consent_validity").asLong(0);
            result.add(new BankInfo(a.path("name").asString(), a.path("country").asString(country),
                    (int) Math.min(Integer.MAX_VALUE, seconds / 86400), a.path("beta").asBoolean(false)));
        }
        return result;
    }

    @Override
    public String startAuthorization(BankInfo bank, String redirectUrl, String state, Instant validUntil) {
        ObjectNode body = json.createObjectNode();
        body.putObject("access").put("valid_until", validUntil.toString());
        body.putObject("aspsp").put("name", bank.name()).put("country", bank.country());
        body.put("state", state).put("redirect_url", redirectUrl).put("psu_type", "personal");
        String url = send("POST", "/auth", body).path("url").asString("");
        if (url.isBlank()) {
            throw new IllegalStateException("Réponse inattendue d'Enable Banking : adresse d'autorisation absente");
        }
        return url;
    }

    @Override
    public BankSession createSession(String code) {
        JsonNode s = send("POST", "/sessions", json.createObjectNode().put("code", code));
        List<RemoteAccount> accounts = new ArrayList<>();
        for (JsonNode a : s.path("accounts")) {
            String iban = a.path("account_id").path("iban").asString(null);
            String masked = maskIban(iban);
            String name = firstNonBlank(a.path("name").asString(null), a.path("product").asString(null),
                    a.path("details").asString(null), masked == null ? "Compte" : "Compte " + masked);
            accounts.add(new RemoteAccount(a.path("uid").asString(), name, masked, a.path("currency").asString(null)));
        }
        String until = s.path("access").path("valid_until").asString(null);
        return new BankSession(s.path("session_id").asString(), s.path("aspsp").path("name").asString(null),
                s.path("aspsp").path("country").asString(null), until == null ? null : parseInstant(until), accounts);
    }

    @Override
    public List<RemoteTransaction> transactions(String accountUid, LocalDate from) {
        List<RemoteTransaction> result = new ArrayList<>();
        Set<String> seenKeys = new LinkedHashSet<>();
        String continuation = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            String path = "/accounts/" + encode(accountUid) + "/transactions?date_from=" + from
                    + (continuation == null ? "" : "&continuation_key=" + encode(continuation));
            JsonNode body = send("GET", path, null);
            for (JsonNode t : body.path("transactions")) {
                result.add(transaction(t));
            }
            continuation = body.path("continuation_key").asString(null);
            if (continuation == null || continuation.isBlank() || !seenKeys.add(continuation)) {
                return result;
            }
        }
        throw new IllegalStateException("Trop de pages renvoyées par la banque : synchronisation interrompue");
    }

    @Override
    public void deleteSession(String sessionId) {
        send("DELETE", "/sessions/" + encode(sessionId), null);
    }

    // ------------------------------------------------------------ correspondance

    static RemoteTransaction transaction(JsonNode t) {
        String indicator = t.path("credit_debit_indicator").asString("");
        String rawAmount = t.path("transaction_amount").path("amount").asString(null);
        BigDecimal amount = null;
        if (rawAmount != null) {
            try {
                BigDecimal value = new BigDecimal(rawAmount.strip());
                amount = indicator.startsWith("CRDT") ? value.abs() : indicator.startsWith("DB") ? value.abs().negate() : value;
            } catch (NumberFormatException e) {
                amount = null;
            }
        }
        String status = t.path("status").asString(null);
        boolean booked = status == null || "BOOK".equals(status);
        LocalDate date = firstDate(t, "booking_date", "value_date", "transaction_date");

        List<String> parts = new ArrayList<>();
        for (JsonNode r : t.path("remittance_information")) {
            if (!r.asString("").isBlank()) {
                parts.add(r.asString().strip());
            }
        }
        String counterparty = indicator.startsWith("CRDT") ? t.path("debtor").path("name").asString(null)
                : t.path("creditor").path("name").asString(null);
        String label = parts.isEmpty() ? counterparty : String.join(" ", parts);
        if (label != null) {
            label = label.replaceAll("\\s+", " ").strip();
            if (label.length() > 200) {
                label = label.substring(0, 200);
            }
        }
        String id = firstNonBlank(t.path("transaction_id").asString(null), t.path("entry_reference").asString(null));
        return new RemoteTransaction(id == null ? null : "eb:" + id, date, amount,
                t.path("transaction_amount").path("currency").asString(null), label, booked);
    }

    static String maskIban(String iban) {
        if (iban == null) {
            return null;
        }
        String compact = iban.replaceAll("\\s", "").toUpperCase();
        if (compact.length() < 8) {
            return "••••";
        }
        return compact.substring(0, 4) + " •••• " + compact.substring(compact.length() - 4);
    }

    private static LocalDate firstDate(JsonNode t, String... fields) {
        for (String f : fields) {
            String value = t.path(f).asString(null);
            if (value != null && value.length() >= 10) {
                try {
                    return LocalDate.parse(value.substring(0, 10));
                } catch (DateTimeParseException ignored) {
                    // champ suivant
                }
            }
        }
        return null;
    }

    private static Instant parseInstant(String value) {
        try {
            return java.time.OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            return Instant.parse(value);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------ HTTP

    private JsonNode send(String method, String path, JsonNode body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + signer.token())
                .header("Accept", "application/json");
        if (body != null) {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<byte[]> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Connexion à Enable Banking impossible : vérifiez votre connexion Internet");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Synchronisation interrompue");
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            byte[] bytes = response.body();
            return bytes == null || bytes.length == 0 ? json.createObjectNode() : json.readTree(bytes);
        }
        throw new IllegalStateException(errorMessage(status, response.body()));
    }

    private String errorMessage(int status, byte[] body) {
        String detail = "";
        try {
            JsonNode node = body == null || body.length == 0 ? null : json.readTree(body);
            if (node != null) {
                detail = firstNonBlank(node.path("message").asString(null), node.path("error").asString(null), "");
            }
        } catch (RuntimeException ignored) {
            detail = "";
        }
        if (detail.length() > 200) {
            detail = detail.substring(0, 200);
        }
        String suffix = detail.isBlank() ? "" : " — " + detail;
        return switch (status) {
            case 401, 403 -> "Enable Banking refuse l'accès (HTTP " + status + ") : vérifiez l'identifiant d'application "
                    + "et la clé, et qu'au moins un compte est lié à l'application (mode restreint)" + suffix;
            case 404 -> "Session ou compte introuvable chez Enable Banking : reconnectez la banque" + suffix;
            case 408, 429 -> "La banque limite le nombre de consultations : réessayez dans quelques heures" + suffix;
            default -> status >= 500
                    ? "Enable Banking ou la banque est momentanément indisponible (HTTP " + status + ") : réessayez plus tard"
                    : "Requête refusée par Enable Banking (HTTP " + status + ")" + suffix;
        };
    }
}
