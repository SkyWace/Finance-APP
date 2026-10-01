package com.financeapp.infra.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProfileRegistryTest {

    @TempDir
    Path root;

    @Test
    void freshInstallHasNoProfileAndEachNewOneGetsItsOwnFolder() {
        ProfileRegistry registry = new ProfileRegistry(root);
        assertTrue(registry.list().isEmpty());

        Profile alice = registry.create("  Alice  ");
        Profile bob = registry.create("Bob");
        assertEquals("Alice", alice.name());
        assertNotEquals(alice.directories().root(), bob.directories().root());
        assertTrue(alice.directories().root().startsWith(root.resolve("profiles")));
        assertTrue(Files.isDirectory(bob.directories().databaseFile().getParent()));
        assertNotEquals(alice.directories().keystoreFile(), bob.directories().keystoreFile(),
                "chaque utilisateur a son propre trousseau");

        ProfileRegistry reopened = new ProfileRegistry(root);
        assertEquals(2, reopened.list().size());
        assertEquals("Alice", reopened.list().getFirst().name(), "ordre de creation");
        assertTrue(reopened.lastUsed().isEmpty());
        reopened.markUsed(bob.id());
        assertEquals(bob.id(), new ProfileRegistry(root).lastUsed().orElseThrow().id());
    }

    @Test
    void existingDataBecomesTheFirstProfileWithoutMovingFiles() throws Exception {
        AppDirectories legacy = new AppDirectories(root).createAll();
        Files.writeString(legacy.keystoreFile(), "x");

        ProfileRegistry registry = new ProfileRegistry(root);
        Profile main = registry.list().getFirst();
        assertEquals(ProfileRegistry.LEGACY_ID, main.id());
        assertEquals(ProfileRegistry.LEGACY_NAME, main.name());
        assertEquals(legacy.keystoreFile(), main.directories().keystoreFile(), "les fichiers restent en place");
        assertEquals(main.id(), registry.lastUsed().orElseThrow().id());

        Profile second = registry.create("Conjoint");
        assertEquals(2, registry.list().size());
        assertFalse(second.directories().root().equals(root));
    }

    @Test
    void namesAreRequiredUniqueAndRenamable() {
        ProfileRegistry registry = new ProfileRegistry(root);
        Profile a = registry.create("Alice");
        assertThrows(IllegalArgumentException.class, () -> registry.create(" "));
        assertThrows(IllegalArgumentException.class, () -> registry.create("alice"), "insensible a la casse");
        assertThrows(IllegalArgumentException.class, () -> registry.create("x".repeat(41)));

        assertEquals("Alice D.", registry.rename(a.id(), "Alice D.").name());
        assertEquals("Alice D.", new ProfileRegistry(root).find(a.id()).orElseThrow().name());
        assertEquals("ALICE D.", registry.rename(a.id(), "ALICE D.").name(), "son propre nom n'est pas un doublon");
        assertThrows(IllegalArgumentException.class, () -> registry.rename("inconnu", "Zoé"));
    }

    @Test
    void aTamperedFolderCannotPointOutsideTheApplicationFolder() throws Exception {
        Files.writeString(root.resolve(ProfileRegistry.FILE_NAME), "order=evil\nprofile.evil.name=Evil\nprofile.evil.dir=../../etc\n");
        assertThrows(IllegalStateException.class, () -> new ProfileRegistry(root).list());
    }
}
