package com.financeapp.infra.security;

import com.financeapp.core.export.Json;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sauvegarde au format de la version web : un profil chiffre, restaurable dans le
 * navigateur ("Importer une sauvegarde") puis ouvert avec le mot de passe choisi ici.
 *
 * <p>Meme schema que le coffre web (WebCrypto) : cle de donnees AES-256 aleatoire ;
 * donnees chiffrees en AES-256-GCM (IV 12 octets, identifiant du profil en donnees
 * authentifiees) ; cle enveloppee en AES-GCM par une cle derivee du mot de passe
 * (PBKDF2-HMAC-SHA-256, 600 000 iterations, sel 16 octets) et par une cle derivee d'une
 * cle de recuperation (100 000 iterations). Uniquement la cryptographie du JDK.
 */
public final class WebBackupWriter {

    public static final int MIN_PASSWORD_LENGTH = 10;
    static final int PASSWORD_ITERATIONS = 600_000;
    static final int RECOVERY_ITERATIONS = 100_000;
    private static final String FORMAT = "financeapp-web-backup";
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** Fichier produit et cle de recuperation a remettre a l'utilisateur (une seule fois). */
    public record Result(String json, String recoveryKey) {
    }

    private final SecureRandom random;
    private final int passwordIterations;

    public WebBackupWriter() {
        this(new SecureRandom(), PASSWORD_ITERATIONS);
    }

    /** Pour les tests : iterations reduites (le format les enregistre, la lecture les respecte). */
    WebBackupWriter(SecureRandom random, int passwordIterations) {
        this.random = random;
        this.passwordIterations = passwordIterations;
    }

    /**
     * @param profileName nom du profil cree dans le navigateur (40 caracteres au plus)
     * @param dataJson    donnees au format web (voir WebExportService)
     */
    public Result write(String profileName, String dataJson, char[] password) throws GeneralSecurityException {
        if (password == null || password.length < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Le mot de passe doit contenir au moins " + MIN_PASSWORD_LENGTH
                    + " caractères.");
        }
        String name = profileName == null || profileName.isBlank() ? "Profil desktop" : profileName.strip();
        if (name.length() > 40) {
            name = name.substring(0, 40);
        }
        String id = UUID.randomUUID().toString();
        byte[] dek = bytes(32);
        try {
            String recoveryKey = newRecoveryKey();
            String now = Instant.now().toString();
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("id", id);
            profile.put("name", name);
            profile.put("createdAt", now);
            profile.put("updatedAt", now);
            profile.put("password", wrap(dek, password, passwordIterations));
            char[] recovery = recoveryKey.toCharArray();
            try {
                profile.put("recovery", wrap(dek, recovery, RECOVERY_ITERATIONS));
            } finally {
                Arrays.fill(recovery, '\0');
            }
            profile.put("payload", seal(dek, id.getBytes(StandardCharsets.UTF_8),
                    dataJson.getBytes(StandardCharsets.UTF_8)));
            Map<String, Object> backup = new LinkedHashMap<>();
            backup.put("format", FORMAT);
            backup.put("version", 1);
            backup.put("exportedAt", now);
            backup.put("profile", profile);
            return new Result(Json.write(backup), recoveryKey);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    // ------------------------------------------------------------------ lecture (verification)

    /**
     * Dechiffre les donnees d'un fichier produit par {@link #write} avec son mot de passe.
     * Sert aux verifications ; la version web a sa propre lecture (WebCrypto).
     */
    public static String readPayload(String profileId, String salt, int iterations, String wrapIv, String wrapData,
                                     String payloadIv, String payloadData, char[] password)
            throws GeneralSecurityException {
        SecretKey kek = derive(password, b64(salt), iterations);
        byte[] dek = gcm(Cipher.DECRYPT_MODE, kek, b64(wrapIv), null, b64(wrapData));
        try {
            return new String(gcm(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"), b64(payloadIv),
                    profileId.getBytes(StandardCharsets.UTF_8), b64(payloadData)), StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    // ------------------------------------------------------------------ primitives (JDK)

    private Map<String, Object> wrap(byte[] dek, char[] secret, int iterations) throws GeneralSecurityException {
        byte[] salt = bytes(16);
        SecretKey kek = derive(secret, salt, iterations);
        Map<String, Object> key = encrypt(kek, null, dek);
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("salt", b64(salt));
        w.put("iterations", iterations);
        w.put("key", key);
        return w;
    }

    private Map<String, Object> seal(byte[] dek, byte[] aad, byte[] plain) throws GeneralSecurityException {
        return encrypt(new SecretKeySpec(dek, "AES"), aad, plain);
    }

    private Map<String, Object> encrypt(SecretKey key, byte[] aad, byte[] plain) throws GeneralSecurityException {
        byte[] iv = bytes(12);
        Map<String, Object> sealed = new LinkedHashMap<>();
        sealed.put("iv", b64(iv));
        sealed.put("data", b64(gcm(Cipher.ENCRYPT_MODE, key, iv, aad, plain)));
        return sealed;
    }

    /** AES-GCM, etiquette de 128 bits a la fin (comme WebCrypto). */
    private static byte[] gcm(int mode, SecretKey key, byte[] iv, byte[] aad, byte[] input)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, iv));
        if (aad != null) {
            cipher.updateAAD(aad);
        }
        return cipher.doFinal(input); // AEADBadTagException : mot de passe incorrect ou fichier altere
    }

    /** PBKDF2-HMAC-SHA-256 sur le secret normalise (NFC) encode en UTF-8, comme la version web. */
    private static SecretKey derive(char[] secret, byte[] salt, int iterations) throws GeneralSecurityException {
        char[] normalized = Normalizer.normalize(java.nio.CharBuffer.wrap(secret), Normalizer.Form.NFC).toCharArray();
        PBEKeySpec spec = new PBEKeySpec(normalized, salt, iterations, 256);
        try {
            byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            try {
                return new SecretKeySpec(raw, "AES");
            } finally {
                Arrays.fill(raw, (byte) 0);
            }
        } finally {
            spec.clearPassword();
            Arrays.fill(normalized, '\0');
        }
    }

    private String newRecoveryKey() {
        byte[] raw = bytes(20);
        StringBuilder out = new StringBuilder();
        int bits = 0;
        int value = 0;
        for (byte b : raw) {
            value = (value << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET.charAt((value >>> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        Arrays.fill(raw, (byte) 0);
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < out.length(); i += 4) {
            if (i > 0) {
                grouped.append('-');
            }
            grouped.append(out, i, i + 4);
        }
        return grouped.toString();
    }

    private byte[] bytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] b64(String text) {
        return Base64.getDecoder().decode(text);
    }
}
