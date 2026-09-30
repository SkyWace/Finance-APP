package com.financeapp.banksync;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/** Lecture de la cle privee RSA fournie par l'agregateur (PEM PKCS#8), avec les API du JDK. */
public final class PemKeys {

    private static final String BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String END = "-----END PRIVATE KEY-----";

    private PemKeys() {
    }

    public static RSAPrivateKey readPrivateKey(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("Clé privée manquante : choisissez le fichier .pem téléchargé lors de "
                    + "l'enregistrement de l'application");
        }
        String text = pem.strip();
        if (text.contains("BEGIN ENCRYPTED PRIVATE KEY")) {
            throw new IllegalArgumentException("Clé privée protégée par mot de passe : utilisez le fichier .pem non "
                    + "chiffré fourni par Enable Banking");
        }
        if (text.contains("BEGIN RSA PRIVATE KEY")) {
            throw new IllegalArgumentException("Clé au format PKCS#1 : convertissez-la en PKCS#8 "
                    + "(openssl pkcs8 -topk8 -nocrypt -in cle.pem -out cle-pkcs8.pem)");
        }
        int start = text.indexOf(BEGIN);
        int end = text.indexOf(END);
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Fichier de clé non reconnu : il doit contenir « " + BEGIN + " »");
        }
        String base64 = text.substring(start + BEGIN.length(), end).replaceAll("\\s", "");
        java.security.PrivateKey key;
        try {
            key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (IllegalArgumentException | java.security.GeneralSecurityException e) {
            throw new IllegalArgumentException("Clé privée illisible (RSA PKCS#8 attendu)");
        }
        if (!(key instanceof RSAPrivateKey rsa)) {
            throw new IllegalArgumentException("La clé n'est pas une clé RSA");
        }
        if (rsa.getModulus().bitLength() < 2048) {
            throw new IllegalArgumentException("Clé RSA trop courte (2048 bits minimum)");
        }
        return rsa;
    }
}
