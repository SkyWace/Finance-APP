package com.financeapp.banksync;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * Jeton d'authentification de l'application aupres d'Enable Banking : JWT RS256
 * ({@code kid} = identifiant d'application), signe avec {@code SHA256withRSA} du
 * JDK. Le jeton est reutilise jusqu'a 5 minutes avant son expiration (1 heure).
 */
final class JwtSigner {

    static final long LIFETIME_SECONDS = 3600;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final String applicationId;
    private final RSAPrivateKey key;
    private final Clock clock;
    private final JsonMapper json;
    private String token;
    private Instant expires = Instant.EPOCH;

    JwtSigner(String applicationId, RSAPrivateKey key, Clock clock, JsonMapper json) {
        this.applicationId = applicationId;
        this.key = key;
        this.clock = clock;
        this.json = json;
    }

    synchronized String token() {
        Instant now = clock.instant();
        if (token == null || now.isAfter(expires.minusSeconds(300))) {
            long iat = now.getEpochSecond();
            ObjectNode header = json.createObjectNode().put("typ", "JWT").put("alg", "RS256").put("kid", applicationId);
            ObjectNode claims = json.createObjectNode().put("iss", "enablebanking.com")
                    .put("aud", "api.enablebanking.com").put("iat", iat).put("exp", iat + LIFETIME_SECONDS);
            String signingInput = B64.encodeToString(json.writeValueAsBytes(header)) + "."
                    + B64.encodeToString(json.writeValueAsBytes(claims));
            try {
                Signature rsa = Signature.getInstance("SHA256withRSA");
                rsa.initSign(key);
                rsa.update(signingInput.getBytes(StandardCharsets.US_ASCII));
                token = signingInput + "." + B64.encodeToString(rsa.sign());
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Signature du jeton impossible avec cette clé", e);
            }
            expires = Instant.ofEpochSecond(iat + LIFETIME_SECONDS);
        }
        return token;
    }
}
