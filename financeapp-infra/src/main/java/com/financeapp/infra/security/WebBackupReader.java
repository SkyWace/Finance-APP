package com.financeapp.infra.security;

import com.financeapp.core.export.Json;

import java.security.GeneralSecurityException;
import java.util.Map;

/**
 * Lecture d'une sauvegarde de la version web (Parametres → Sauvegardes du site, ou fichier
 * produit par "Exporter pour la version web") : verification du format puis dechiffrement
 * avec le mot de passe du profil web. Meme schema que {@link WebBackupWriter}.
 */
public final class WebBackupReader {

    /** Taille maximale acceptee pour un fichier de sauvegarde du site. */
    public static final long MAX_FILE_SIZE = 64L * 1024 * 1024;
    /** Borne haute des iterations PBKDF2 lues dans le fichier (un fichier malveillant pourrait bloquer l'application). */
    private static final long MAX_ITERATIONS = 5_000_000;

    /** Contenu dechiffre : nom du profil web, date de la sauvegarde, donnees (FinanceData, JSON). */
    public record Content(String profileName, String exportedAt, String dataJson) {
    }

    /** Fichier qui n'est pas une sauvegarde du site (message montre a l'utilisateur). */
    public static final class NotAWebBackupException extends Exception {
        public NotAWebBackupException(String message) {
            super(message);
        }
    }

    private WebBackupReader() {
    }

    /** Nom du profil, lisible sans mot de passe (pour l'afficher avant de le demander). */
    public static String profileName(String backupJson) throws NotAWebBackupException {
        return text(map(envelope(backupJson), "profile"), "name");
    }

    /**
     * @throws NotAWebBackupException   fichier d'un autre format
     * @throws GeneralSecurityException mot de passe incorrect ou fichier altere ({@code AEADBadTagException})
     */
    public static Content read(String backupJson, char[] password) throws NotAWebBackupException,
            GeneralSecurityException {
        Map<String, Object> root = envelope(backupJson);
        Map<String, Object> profile = map(root, "profile");
        Map<String, Object> wrap = map(profile, "password");
        Map<String, Object> key = map(wrap, "key");
        Map<String, Object> payload = map(profile, "payload");
        long iterations = wrap.get("iterations") instanceof Long l ? l : -1;
        if (iterations < 1 || iterations > MAX_ITERATIONS) {
            throw new NotAWebBackupException("Sauvegarde du site illisible (paramètres de chiffrement inattendus).");
        }
        try {
            String data = WebBackupWriter.readPayload(text(profile, "id"), text(wrap, "salt"), (int) iterations,
                    text(key, "iv"), text(key, "data"), text(payload, "iv"), text(payload, "data"), password);
            return new Content(text(profile, "name"), root.get("exportedAt") instanceof String s ? s : null, data);
        } catch (IllegalArgumentException e) {
            // base64 ou longueurs invalides : fichier abime
            throw new NotAWebBackupException("Sauvegarde du site illisible (fichier abîmé).");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> envelope(String backupJson) throws NotAWebBackupException {
        Object parsed;
        try {
            parsed = Json.parse(backupJson.strip());
        } catch (IllegalArgumentException e) {
            throw new NotAWebBackupException("Ce fichier n'est pas une sauvegarde de la version web.");
        }
        if (!(parsed instanceof Map<?, ?> m) || !"financeapp-web-backup".equals(m.get("format"))) {
            throw new NotAWebBackupException("Ce fichier n'est pas une sauvegarde de la version web.");
        }
        if (!Long.valueOf(1).equals(m.get("version"))) {
            throw new NotAWebBackupException("Sauvegarde du site d'une version plus récente : mettez l'application à jour.");
        }
        return (Map<String, Object>) parsed;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> parent, String key) throws NotAWebBackupException {
        if (parent.get(key) instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new NotAWebBackupException("Sauvegarde du site illisible (« " + key + " » manquant).");
    }

    private static String text(Map<String, Object> parent, String key) throws NotAWebBackupException {
        if (parent.get(key) instanceof String s) {
            return s;
        }
        throw new NotAWebBackupException("Sauvegarde du site illisible (« " + key + " » manquant).");
    }
}
