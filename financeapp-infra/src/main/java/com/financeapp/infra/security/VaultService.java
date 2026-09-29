package com.financeapp.infra.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Mot de passe maitre et cle de la base.
 *
 * <pre>
 *   mot de passe ──Argon2id(sel)──► KEK ──AES-GCM──┐
 *   cle de recuperation ─Argon2id(sel)─► KEK' ─────┤──► DEK (256 bits aleatoires) ──► base SQLCipher
 * </pre>
 *
 * La DEK ne change jamais : changer de mot de passe revient a la
 * re-envelopper, sans rechiffrer la base ; toutes les sauvegardes (chiffrees
 * avec la meme DEK) restent lisibles. Aucun mot de passe n'est stocke, ni en
 * clair ni sous forme d'empreinte : seule une enveloppe AES-GCM permet de
 * verifier qu'il est correct.
 */
public final class VaultService {

    private static final Logger log = LoggerFactory.getLogger(VaultService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final int MIN_PASSWORD_LENGTH = 10;

    public enum Status {
        /** Premier lancement : ni base ni trousseau. */
        NEW,
        /** Donnees d'une version precedente, non chiffrees : a chiffrer a la creation du mot de passe. */
        PLAINTEXT_DATA,
        /** Base chiffree : mot de passe requis. */
        LOCKED,
        /** Base chiffree mais trousseau absent : il faut le restaurer depuis une sauvegarde (.key). */
        KEYSTORE_MISSING
    }

    /** @param recoveryKey a afficher UNE fois a l'utilisateur, puis effacer */
    public record Creation(byte[] dek, char[] recoveryKey) {
    }

    private final Path keystoreFile;
    private final Path databaseFile;
    private final Path backupsDir;
    private final Argon2Params params;

    /** @throws IllegalStateException si le pilote SQLite ne sait pas chiffrer */
    public VaultService(Path keystoreFile, Path databaseFile, Path backupsDir, Argon2Params params) {
        log.info("Chiffrement disponible : SQLite3MultipleCiphers {}", DatabaseEncryption.requireCipherSupport());
        this.keystoreFile = keystoreFile;
        this.databaseFile = databaseFile;
        this.backupsDir = backupsDir;
        this.params = params;
    }

    public Path keystoreFile() {
        return keystoreFile;
    }

    public Status status() throws IOException {
        if (Files.exists(keystoreFile)) {
            return Status.LOCKED;
        }
        if (!Files.exists(databaseFile)) {
            return Status.NEW;
        }
        return DatabaseEncryption.isPlaintextSqlite(databaseFile) ? Status.PLAINTEXT_DATA : Status.KEYSTORE_MISSING;
    }

    /** Regles minimales ; le message d'erreur est destine a l'utilisateur. */
    public static void checkPasswordPolicy(char[] password) {
        if (password == null || password.length < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Le mot de passe doit contenir au moins " + MIN_PASSWORD_LENGTH + " caractères.");
        }
        char first = password[0];
        boolean allSame = true;
        for (char c : password) {
            allSame &= c == first;
        }
        if (allSame) {
            throw new IllegalArgumentException("Ce mot de passe est trop simple.");
        }
    }

    /**
     * Cree le trousseau (nouvelle DEK), puis chiffre les donnees en clair
     * existantes (base et sauvegardes de la V1). Le trousseau est ecrit AVANT
     * tout chiffrement : une interruption ne peut jamais laisser une base
     * chiffree sans sa cle ; la migration reprend au prochain deverrouillage.
     */
    public Creation create(char[] password) throws IOException, SQLException {
        checkPasswordPolicy(password);
        if (Files.exists(keystoreFile)) {
            throw new IllegalStateException("Un mot de passe maitre existe deja");
        }
        byte[] dek = random(KeyDerivation.KEY_BYTES);
        char[] recovery = RecoveryKey.generate();
        Keystore ks = new Keystore(UUID.randomUUID().toString(), params, null, null, null, null);
        ks = wrapPassword(ks, password, dek);
        ks = wrapRecovery(ks, recovery, dek);
        ks.save(keystoreFile);
        log.info("Mot de passe maitre cree");
        encryptLegacyData(dek);
        return new Creation(dek, recovery);
    }

    /** @return la DEK (a effacer par l'appelant) */
    public byte[] unlock(char[] password) throws InvalidSecretException, IOException, SQLException {
        Keystore ks = Keystore.load(keystoreFile);
        byte[] dek = unwrapWithPassword(ks, password);
        encryptLegacyData(dek);
        return dek;
    }

    /** Mot de passe oublie : la cle de recuperation permet d'en definir un nouveau. */
    public byte[] recover(String recoveryKey, char[] newPassword) throws InvalidSecretException, IOException, SQLException {
        checkPasswordPolicy(newPassword);
        Keystore ks = Keystore.load(keystoreFile);
        char[] normalized = RecoveryKey.normalize(recoveryKey);
        byte[] kek = KeyDerivation.argon2id(normalized, ks.recoverySalt(), ks.params());
        Arrays.fill(normalized, '\0');
        byte[] dek;
        try {
            dek = KeyWrapper.unwrap(kek, ks.recoveryWrappedKey(), ks.recoveryAad());
        } catch (InvalidSecretException e) {
            throw new InvalidSecretException("Clé de récupération incorrecte");
        } finally {
            Arrays.fill(kek, (byte) 0);
        }
        wrapPassword(ks, newPassword, dek).save(keystoreFile);
        log.info("Mot de passe maitre redefini avec la cle de recuperation");
        encryptLegacyData(dek);
        return dek;
    }

    public void changePassword(char[] current, char[] newPassword) throws InvalidSecretException, IOException {
        checkPasswordPolicy(newPassword);
        Keystore ks = Keystore.load(keystoreFile);
        byte[] dek = unwrapWithPassword(ks, current);
        try {
            wrapPassword(ks, newPassword, dek).save(keystoreFile);
            log.info("Mot de passe maitre modifie");
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /** Invalide l'ancienne cle de recuperation et en renvoie une nouvelle (a afficher une fois). */
    public char[] regenerateRecoveryKey(char[] password) throws InvalidSecretException, IOException {
        Keystore ks = Keystore.load(keystoreFile);
        byte[] dek = unwrapWithPassword(ks, password);
        try {
            char[] recovery = RecoveryKey.generate();
            wrapRecovery(ks, recovery, dek).save(keystoreFile);
            log.info("Nouvelle cle de recuperation generee");
            return recovery;
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /** Remet en place un trousseau sauvegarde (fichier .key a cote d'une sauvegarde). */
    public void importKeystore(Path source) throws IOException {
        Keystore.load(source);
        Files.createDirectories(keystoreFile.toAbsolutePath().getParent());
        Files.copy(source, keystoreFile, StandardCopyOption.REPLACE_EXISTING);
        log.info("Trousseau importe");
    }

    public static byte[] unwrapWithPassword(Keystore ks, char[] password) throws InvalidSecretException {
        byte[] kek = KeyDerivation.argon2id(password, ks.passwordSalt(), ks.params());
        try {
            return KeyWrapper.unwrap(kek, ks.passwordWrappedKey(), ks.passwordAad());
        } catch (InvalidSecretException e) {
            throw new InvalidSecretException("Mot de passe incorrect");
        } finally {
            Arrays.fill(kek, (byte) 0);
        }
    }

    private Keystore wrapPassword(Keystore ks, char[] password, byte[] dek) {
        byte[] salt = random(16);
        byte[] kek = KeyDerivation.argon2id(password, salt, ks.params());
        try {
            return ks.withPassword(salt, KeyWrapper.wrap(kek, dek, ks.passwordAad()));
        } finally {
            Arrays.fill(kek, (byte) 0);
        }
    }

    private Keystore wrapRecovery(Keystore ks, char[] recoveryKey, byte[] dek) {
        byte[] salt = random(16);
        char[] normalized;
        try {
            normalized = RecoveryKey.normalize(recoveryKey);
        } catch (InvalidSecretException e) {
            throw new IllegalStateException(e);
        }
        byte[] kek = KeyDerivation.argon2id(normalized, salt, ks.params());
        Arrays.fill(normalized, '\0');
        try {
            return ks.withRecovery(salt, KeyWrapper.wrap(kek, dek, ks.recoveryAad()));
        } finally {
            Arrays.fill(kek, (byte) 0);
        }
    }

    /** Chiffre la base et les sauvegardes encore en clair (idempotent). */
    private void encryptLegacyData(byte[] dek) throws IOException, SQLException {
        if (DatabaseEncryption.isPlaintextSqlite(databaseFile)) {
            DatabaseEncryption.encryptInPlace(databaseFile, dek);
        }
        if (!Files.isDirectory(backupsDir)) {
            return;
        }
        List<Path> legacy = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(backupsDir, "*.db")) {
            for (Path f : files) {
                if (DatabaseEncryption.isPlaintextSqlite(f)) {
                    legacy.add(f);
                }
            }
        }
        for (Path f : legacy) {
            try {
                DatabaseEncryption.encryptInPlace(f, dek);
                Files.copy(keystoreFile, f.resolveSibling(f.getFileName() + ".key"), StandardCopyOption.REPLACE_EXISTING);
            } catch (SQLException | IOException e) {
                log.warn("Sauvegarde ancienne non chiffree (fichier illisible ?) : {}", f.getFileName());
            }
        }
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }
}
