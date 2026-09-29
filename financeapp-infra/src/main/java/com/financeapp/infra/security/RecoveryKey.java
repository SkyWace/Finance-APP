package com.financeapp.infra.security;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Cle de recuperation : 160 bits aleatoires, affiches en Base32 par groupes
 * de 4 caracteres (ex. {@code K7QF-2M9D-...}). Elle permet de redefinir le
 * mot de passe maitre s'il est oublie. Elle n'est stockee nulle part en clair.
 */
public final class RecoveryKey {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int BYTES = 20;

    private RecoveryKey() {
    }

    public static char[] generate() {
        byte[] raw = new byte[BYTES];
        new SecureRandom().nextBytes(raw);
        return format(base32(raw));
    }

    /** Forme normalisee (majuscules, sans separateurs) utilisee pour la derivation. */
    public static char[] normalize(String input) throws InvalidSecretException {
        String s = input == null ? "" : input.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "")
                .replace('0', 'O').replace('1', 'I').replace('8', 'B');
        if (s.length() != 32 || s.chars().anyMatch(c -> ALPHABET.indexOf(c) < 0)) {
            throw new InvalidSecretException("Format de clé de récupération invalide (32 caractères attendus)");
        }
        return s.toCharArray();
    }

    static char[] normalize(char[] formatted) throws InvalidSecretException {
        return normalize(new String(formatted));
    }

    private static char[] base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        char[] out = sb.toString().toCharArray();
        sb.setLength(0);
        return out;
    }

    private static char[] format(char[] plain) {
        char[] out = new char[plain.length + plain.length / 4 - 1];
        int j = 0;
        for (int i = 0; i < plain.length; i++) {
            if (i > 0 && i % 4 == 0) {
                out[j++] = '-';
            }
            out[j++] = plain[i];
        }
        java.util.Arrays.fill(plain, '\0');
        return out;
    }
}
