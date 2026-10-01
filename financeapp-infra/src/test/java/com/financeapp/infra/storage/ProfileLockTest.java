package com.financeapp.infra.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ProfileLockTest {

    @TempDir
    Path root;

    @Test
    void aProfileCanOnlyBeOpenedOnceAndIsFreedOnClose() throws Exception {
        AppDirectories dirs = new AppDirectories(root);
        Optional<ProfileLock> first = ProfileLock.tryAcquire(dirs);
        assertTrue(first.isPresent());
        assertTrue(ProfileLock.tryAcquire(dirs).isEmpty(), "deja ouvert");

        AppDirectories other = new AppDirectories(root.resolve("profiles").resolve("x"));
        try (ProfileLock otherLock = ProfileLock.tryAcquire(other).orElseThrow()) {
            assertNotNull(otherLock, "un autre profil reste ouvrable");
        }

        first.get().close();
        try (ProfileLock again = ProfileLock.tryAcquire(dirs).orElseThrow()) {
            assertNotNull(again, "libere a la fermeture");
        }
    }

    /** Le cas reel : un second processus (deuxieme lancement de l'application). */
    @Test
    void anotherProcessCannotOpenTheSameProfile() throws Exception {
        AppDirectories dirs = new AppDirectories(root);
        try (ProfileLock held = ProfileLock.tryAcquire(dirs).orElseThrow()) {
            assertNotNull(held);
            assertEquals("busy", runInChildProcess(dirs));
        }
        assertEquals("free", runInChildProcess(dirs));
    }

    private static String runInChildProcess(AppDirectories dirs) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                LockProbe.class.getName(), dirs.root().toString()).redirectErrorStream(true).start();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        String out = new String(p.getInputStream().readAllBytes()).strip();
        return out.lines().reduce((a, b) -> b).orElse("");
    }

    /** Point d'entree du processus enfant. */
    public static final class LockProbe {
        public static void main(String[] args) throws Exception {
            Optional<ProfileLock> lock = ProfileLock.tryAcquire(new AppDirectories(Path.of(args[0])));
            System.out.println(lock.isPresent() ? "free" : "busy");
            if (lock.isPresent()) {
                lock.get().close();
            }
        }
    }
}
