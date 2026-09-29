package com.financeapp.infra.security;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Enveloppe une cle avec une autre via AES-256-GCM (JCA standard). Le format
 * produit est {@code nonce (12 o) || texte chiffre || tag (16 o)}. Les donnees
 * associees (AAD) lient l'enveloppe a son usage : une enveloppe "recuperation"
 * ne peut pas etre presentee comme une enveloppe "mot de passe".
 */
public final class KeyWrapper {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private KeyWrapper() {
    }

    public static byte[] wrap(byte[] kek, byte[] key, byte[] aad) {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kek, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            byte[] sealed = cipher.doFinal(key);
            byte[] out = new byte[NONCE_BYTES + sealed.length];
            System.arraycopy(nonce, 0, out, 0, NONCE_BYTES);
            System.arraycopy(sealed, 0, out, NONCE_BYTES, sealed.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM indisponible", e);
        }
    }

    /** @throws InvalidSecretException si la cle d'enveloppe est mauvaise ou l'enveloppe alteree */
    public static byte[] unwrap(byte[] kek, byte[] wrapped, byte[] aad) throws InvalidSecretException {
        if (wrapped == null || wrapped.length <= NONCE_BYTES + TAG_BITS / 8) {
            throw new InvalidSecretException("Trousseau invalide");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kek, "AES"),
                    new GCMParameterSpec(TAG_BITS, wrapped, 0, NONCE_BYTES));
            cipher.updateAAD(aad);
            return cipher.doFinal(Arrays.copyOfRange(wrapped, NONCE_BYTES, wrapped.length));
        } catch (AEADBadTagException e) {
            throw new InvalidSecretException("Secret incorrect");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM indisponible", e);
        }
    }
}
