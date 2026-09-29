package com.financeapp.infra.security;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Properties;

/**
 * Trousseau : la cle de la base (DEK), enveloppee deux fois — par le mot de
 * passe maitre et par la cle de recuperation. Il ne contient AUCUN secret
 * exploitable sans l'un des deux : il peut etre copie a cote des sauvegardes.
 *
 * @param keyId identifiant aleatoire de la DEK (pas derive d'elle) : permet de savoir si
 *              une sauvegarde utilise la meme cle que l'installation, sans rien dechiffrer
 */
public record Keystore(
        String keyId,
        Argon2Params params,
        byte[] passwordSalt,
        byte[] passwordWrappedKey,
        byte[] recoverySalt,
        byte[] recoveryWrappedKey) {

    static final String FORMAT = "financeapp-keystore";
    static final int VERSION = 1;

    byte[] passwordAad() {
        return aad("password");
    }

    byte[] recoveryAad() {
        return aad("recovery");
    }

    private byte[] aad(String purpose) {
        return (FORMAT + ":v" + VERSION + ":" + purpose + ":" + keyId).getBytes(StandardCharsets.UTF_8);
    }

    Keystore withPassword(byte[] salt, byte[] wrapped) {
        return new Keystore(keyId, params, salt, wrapped, recoverySalt, recoveryWrappedKey);
    }

    Keystore withRecovery(byte[] salt, byte[] wrapped) {
        return new Keystore(keyId, params, passwordSalt, passwordWrappedKey, salt, wrapped);
    }

    public static Keystore load(Path file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        if (!FORMAT.equals(p.getProperty("format")) || !Integer.toString(VERSION).equals(p.getProperty("version"))) {
            throw new IOException("Trousseau non reconnu : " + file.getFileName());
        }
        try {
            Base64.Decoder b64 = Base64.getDecoder();
            return new Keystore(
                    require(p, "keyId"),
                    new Argon2Params(
                            Integer.parseInt(require(p, "kdf.memoryKiB")),
                            Integer.parseInt(require(p, "kdf.iterations")),
                            Integer.parseInt(require(p, "kdf.parallelism"))),
                    b64.decode(require(p, "password.salt")),
                    b64.decode(require(p, "password.wrappedKey")),
                    b64.decode(require(p, "recovery.salt")),
                    b64.decode(require(p, "recovery.wrappedKey")));
        } catch (IllegalArgumentException e) {
            throw new IOException("Trousseau endommage : " + file.getFileName(), e);
        }
    }

    /** Ecriture atomique (fichier temporaire puis renommage) : jamais de trousseau a moitie ecrit. */
    public void save(Path file) throws IOException {
        Properties p = new Properties();
        Base64.Encoder b64 = Base64.getEncoder();
        p.setProperty("format", FORMAT);
        p.setProperty("version", Integer.toString(VERSION));
        p.setProperty("cipher", "sqlcipher-v4-rawkey");
        p.setProperty("keyId", keyId);
        p.setProperty("kdf", "argon2id");
        p.setProperty("kdf.memoryKiB", Integer.toString(params.memoryKiB()));
        p.setProperty("kdf.iterations", Integer.toString(params.iterations()));
        p.setProperty("kdf.parallelism", Integer.toString(params.parallelism()));
        p.setProperty("password.salt", b64.encodeToString(passwordSalt));
        p.setProperty("password.wrappedKey", b64.encodeToString(passwordWrappedKey));
        p.setProperty("recovery.salt", b64.encodeToString(recoverySalt));
        p.setProperty("recovery.wrappedKey", b64.encodeToString(recoveryWrappedKey));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            p.store(out, "Trousseau FinanceApp - ne contient aucun secret en clair. Ne pas modifier.");
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String require(Properties p, String key) throws IOException {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IOException("Trousseau incomplet (" + key + ")");
        }
        return v;
    }
}
