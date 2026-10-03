package com.financeapp.infra.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Emplacements des fichiers de l'application, conformes aux conventions de
 * chaque systeme :
 * <ul>
 *   <li>Windows : {@code %APPDATA%\<appDirName>}</li>
 *   <li>macOS : {@code ~/Library/Application Support/<appDirName>}</li>
 *   <li>Linux : {@code $XDG_DATA_HOME/<appDirName>} ou {@code ~/.local/share/<appDirName>}</li>
 * </ul>
 */
public record AppDirectories(Path root) {

    public static AppDirectories resolve(String appDirName, String overrideRoot) {
        if (overrideRoot != null && !overrideRoot.isBlank()) {
            return new AppDirectories(Path.of(overrideRoot).toAbsolutePath());
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home"));
        Path base;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            base = appData != null ? Path.of(appData) : home.resolve("AppData").resolve("Roaming");
        } else if (os.contains("mac")) {
            base = home.resolve("Library").resolve("Application Support");
        } else {
            String xdg = System.getenv("XDG_DATA_HOME");
            base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : home.resolve(".local").resolve("share");
        }
        return new AppDirectories(base.resolve(appDirName));
    }

    public Path databaseFile() {
        return root.resolve("data").resolve("financeapp.db");
    }

    public Path backupsDir() {
        return root.resolve("backups");
    }

    public Path logsDir() {
        return root.resolve("logs");
    }

    /** Trousseau : cle de la base enveloppee par le mot de passe maitre et la cle de recuperation. */
    public Path keystoreFile() {
        return root.resolve("data").resolve("keystore.properties");
    }

    /** Trousseau associe a une restauration en attente (sauvegarde d'une autre cle). */
    public Path pendingRestoreKeystore() {
        return root.resolve("data").resolve("pending-restore.keystore");
    }

    /** Sauvegarde validee en attente de restauration au prochain demarrage. */
    public Path pendingRestoreFile() {
        return root.resolve("data").resolve("pending-restore.db");
    }

    /**
     * Copies dechiffrees temporaires des justificatifs ouverts dans une autre application
     * (PDF...). Videe au verrouillage, a la fermeture et au demarrage.
     */
    public Path openedAttachmentsDir() {
        return root.resolve("data").resolve("ouverts");
    }

    /** Verrou d'ouverture : un seul exemplaire de l'application a la fois sur ces donnees. */
    public Path lockFile() {
        return root.resolve("data").resolve("financeapp.lock");
    }

    public AppDirectories createAll() {
        try {
            Files.createDirectories(databaseFile().getParent());
            Files.createDirectories(backupsDir());
            Files.createDirectories(logsDir());
            return this;
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible de creer les dossiers de l'application dans " + root, e);
        }
    }
}
