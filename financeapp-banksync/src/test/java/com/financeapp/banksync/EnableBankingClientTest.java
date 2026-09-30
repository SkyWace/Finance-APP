package com.financeapp.banksync;

import com.financeapp.core.banksync.BankInfo;
import com.financeapp.core.banksync.BankSession;
import com.financeapp.core.banksync.BankSyncCredentials;
import com.financeapp.core.banksync.RemoteTransaction;
import com.financeapp.core.port.BankSyncClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Client Enable Banking contre un serveur simule local qui verifie chaque jeton. */
class EnableBankingClientTest {

    private static final String APP_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final Instant NOW = Instant.parse("2026-09-30T08:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static KeyPair keys;
    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private final List<JsonNode> bodies = new ArrayList<>();
    private BankSyncClient client;

    @BeforeEach
    void setUp() throws Exception {
        if (keys == null) {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            keys = g.generateKeyPair();
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        client = new EnableBankingClientFactory(base, Clock.fixed(NOW, ZoneOffset.UTC))
                .create(new BankSyncCredentials(APP_ID, pem(), "https://localhost/financeapp"));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    static String pem() {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(keys.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n";
    }

    // ---------------------------------------------------------------- serveur simule

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().toString();
        requests.add(ex.getRequestMethod() + " " + path);
        byte[] in = ex.getRequestBody().readAllBytes();
        bodies.add(in.length == 0 ? null : JSON.readTree(in));
        if (!validToken(ex.getRequestHeaders().getFirst("Authorization"))) {
            reply(ex, 401, "{\"message\":\"Invalid JWT\"}");
            return;
        }
        String response;
        int status = 200;
        if (path.startsWith("/aspsps")) {
            response = """
                    {"aspsps":[
                      {"name":"Banque Populaire","country":"FR","psu_types":["personal","business"],
                       "maximum_consent_validity":15552000,"beta":false},
                      {"name":"Banque Pro","country":"FR","psu_types":["business"],"maximum_consent_validity":7776000}
                    ]}""";
        } else if (path.equals("/auth")) {
            response = "{\"url\":\"https://tilisy.enablebanking.com/welcome?sessionid=abc\",\"authorization_id\":\"x\"}";
        } else if (path.equals("/sessions")) {
            response = """
                    {"session_id":"sess-42","aspsp":{"name":"Banque Populaire","country":"FR"},"psu_type":"personal",
                     "access":{"valid_until":"2027-03-29T08:00:00.000000+00:00"},
                     "accounts":[{"uid":"uid-1","account_id":{"iban":"FR76 3000 6000 0112 3456 7890 189"},
                                  "name":"Compte chèque","currency":"EUR"},
                                 {"uid":"uid-2","account_id":{"iban":"FR7612345000019876543210987"},"currency":"EUR"}]}""";
        } else if (path.startsWith("/accounts/uid-1/transactions") && !path.contains("continuation_key")) {
            response = """
                    {"transactions":[
                      {"transaction_id":"T1","transaction_amount":{"amount":"12.50","currency":"EUR"},
                       "credit_debit_indicator":"DBIT","status":"BOOK","booking_date":"2026-09-28",
                       "remittance_information":["CB  BOULANGERIE", "PAUL 27/09"]},
                      {"entry_reference":"E2","transaction_amount":{"amount":"1850.00","currency":"EUR"},
                       "credit_debit_indicator":"CRDT","status":"BOOK","value_date":"2026-09-28",
                       "debtor":{"name":"SOCIETE X"}}
                    ],"continuation_key":"k2"}""";
        } else if (path.startsWith("/accounts/uid-1/transactions")) {
            response = """
                    {"transactions":[
                      {"transaction_id":"T3","transaction_amount":{"amount":"40","currency":"EUR"},
                       "credit_debit_indicator":"DBIT","status":"PDNG","transaction_date":"2026-09-30T09:12:00",
                       "creditor":{"name":"STATION TOTAL"}}
                    ]}""";
        } else if (path.startsWith("/accounts/limited/")) {
            status = 429;
            response = "{\"message\":\"ASPSP_RATE_LIMIT_EXCEEDED\"}";
        } else if (path.startsWith("/sessions/")) {
            response = "{}";
        } else {
            status = 404;
            response = "{\"message\":\"Not found\"}";
        }
        reply(ex, status, response);
    }

    private static boolean validToken(String header) {
        try {
            String[] parts = header.substring("Bearer ".length()).split("\\.");
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initVerify(keys.getPublic());
            rsa.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!rsa.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                return false;
            }
            JsonNode h = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
            JsonNode c = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            return "RS256".equals(h.path("alg").asString()) && APP_ID.equals(h.path("kid").asString())
                    && "enablebanking.com".equals(c.path("iss").asString())
                    && "api.enablebanking.com".equals(c.path("aud").asString())
                    && c.path("iat").asLong() == NOW.getEpochSecond()
                    && c.path("exp").asLong() - c.path("iat").asLong() == 3600;
        } catch (Exception e) {
            return false;
        }
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    // ---------------------------------------------------------------- tests

    @Test
    void listsPersonalBanksWithTheirConsentDuration() {
        List<BankInfo> banks = client.banks("FR");
        assertEquals(1, banks.size(), "banque reservee aux professionnels exclue");
        assertEquals(new BankInfo("Banque Populaire", "FR", 180, false), banks.getFirst());
        assertEquals("GET /aspsps?country=FR", requests.getFirst());
    }

    @Test
    void authorizationAndSessionFollowTheApi() {
        BankInfo bank = new BankInfo("Banque Populaire", "FR", 180, false);
        Instant until = NOW.plusSeconds(180L * 86400);
        String url = client.startAuthorization(bank, "https://localhost/financeapp", "state-1", until);
        assertTrue(url.startsWith("https://"));
        JsonNode auth = bodies.getLast();
        assertEquals("2027-03-29T08:00:00Z", auth.path("access").path("valid_until").asString());
        assertEquals("Banque Populaire", auth.path("aspsp").path("name").asString());
        assertEquals("state-1", auth.path("state").asString());
        assertEquals("https://localhost/financeapp", auth.path("redirect_url").asString());
        assertEquals("personal", auth.path("psu_type").asString());

        BankSession session = client.createSession("code-1");
        assertEquals("code-1", bodies.getLast().path("code").asString());
        assertEquals("sess-42", session.sessionId());
        assertEquals(Instant.parse("2027-03-29T08:00:00Z"), session.validUntil());
        assertEquals("FR76 •••• 0189", session.accounts().getFirst().maskedIban(), "IBAN jamais complet");
        assertEquals("Compte FR76 •••• 0987", session.accounts().get(1).name(), "nom par defaut");

        client.deleteSession("sess-42");
        assertEquals("DELETE /sessions/sess-42", requests.getLast());
    }

    @Test
    void transactionsAreMappedAcrossPages() {
        List<RemoteTransaction> list = client.transactions("uid-1", LocalDate.of(2026, 7, 2));
        assertEquals("GET /accounts/uid-1/transactions?date_from=2026-07-02", requests.getFirst());
        assertEquals("GET /accounts/uid-1/transactions?date_from=2026-07-02&continuation_key=k2", requests.get(1));
        assertEquals(3, list.size());

        RemoteTransaction debit = list.get(0);
        assertEquals("eb:T1", debit.externalId());
        assertEquals(new BigDecimal("-12.50"), debit.amount());
        assertEquals("CB BOULANGERIE PAUL 27/09", debit.label());
        assertEquals(LocalDate.of(2026, 9, 28), debit.date());
        assertTrue(debit.booked());

        RemoteTransaction credit = list.get(1);
        assertEquals("eb:E2", credit.externalId(), "entry_reference a defaut de transaction_id");
        assertEquals(new BigDecimal("1850.00"), credit.amount());
        assertEquals("SOCIETE X", credit.label(), "contrepartie a defaut de libelle");

        RemoteTransaction pending = list.get(2);
        assertFalse(pending.booked());
        assertEquals(LocalDate.of(2026, 9, 30), pending.date());
        assertEquals("STATION TOTAL", pending.label());
    }

    @Test
    void errorsBecomeReadableMessages() {
        IllegalStateException limited = assertThrows(IllegalStateException.class,
                () -> client.transactions("limited", LocalDate.of(2026, 9, 1)));
        assertTrue(limited.getMessage().contains("limite"), limited.getMessage());

        BankSyncClient wrongKey;
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair other = g.generateKeyPair();
            String otherPem = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder().encodeToString(other.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----";
            wrongKey = new EnableBankingClientFactory(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    Clock.fixed(NOW, ZoneOffset.UTC)).create(new BankSyncCredentials(APP_ID, otherPem, "https://x.test/"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        IllegalStateException denied = assertThrows(IllegalStateException.class, () -> wrongKey.banks("FR"));
        assertTrue(denied.getMessage().contains("refuse l'accès"), denied.getMessage());
        assertTrue(denied.getMessage().contains("Invalid JWT"));

        IllegalStateException unreachable = assertThrows(IllegalStateException.class, () -> {
            server.stop(0);
            client.banks("FR");
        });
        assertTrue(unreachable.getMessage().contains("Connexion"));
    }

    @Test
    void keysAndAddressesAreValidated() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> PemKeys.readPrivateKey(
                "-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----"));
        assertThrows(IllegalArgumentException.class, () -> PemKeys.readPrivateKey("n'importe quoi"));
        assertThrows(IllegalArgumentException.class, () -> PemKeys.readPrivateKey(
                "-----BEGIN PRIVATE KEY-----\n@@@\n-----END PRIVATE KEY-----"));
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(1024);
        String weak = "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(
                g.generateKeyPair().getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----";
        assertThrows(IllegalArgumentException.class, () -> PemKeys.readPrivateKey(weak));

        assertThrows(IllegalArgumentException.class, () -> EnableBankingClient.checkedBase(URI.create("http://example.com")));
        assertEquals(EnableBankingClient.PRODUCTION, EnableBankingClient.checkedBase(EnableBankingClient.PRODUCTION));
        assertThrows(IllegalArgumentException.class, () -> new EnableBankingClientFactory(Clock.systemUTC())
                .create(new BankSyncCredentials("pas un id !", pem(), "https://localhost/financeapp")));
    }
}
