package com.financeapp.infra.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Verrou exclusif sur les donnees d'un profil, tenu tant que le profil est ouvert :
 * deux exemplaires de l'application (ou deux sessions Windows) ne peuvent pas
 * ecrire en meme temps dans la meme base ni dans les memes sauvegardes.
 *
 * <p>Verrou du systeme d'exploitation ({@link FileChannel#tryLock()}) : il est
 * libere automatiquement si l'application s'arrete brutalement ; le fichier
 * lui-meme peut rester, vide et sans importance.
 */
public final class ProfileLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private ProfileLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** @return le verrou, ou vide si ces donnees sont deja ouvertes ailleurs */
    public static Optional<ProfileLock> tryAcquire(AppDirectories directories) throws IOException {
        Files.createDirectories(directories.lockFile().getParent());
        FileChannel channel = FileChannel.open(directories.lockFile(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return Optional.empty();
            }
            return Optional.of(new ProfileLock(channel, lock));
        } catch (OverlappingFileLockException e) {
            channel.close(); // deja tenu par ce meme processus
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } finally {
            channel.close();
        }
    }
}
