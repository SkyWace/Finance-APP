package com.financeapp.infra.storage;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * Liste des utilisateurs (profils) de l'application, dans {@code profiles.properties}
 * a la racine du dossier de l'application. Ce fichier ne contient que les noms
 * et les dossiers : aucune donnee financiere, aucun secret.
 *
 * <p>Une installation anterieure aux profils (donnees directement a la racine)
 * devient le premier profil, sans deplacer aucun fichier. Les profils suivants
 * sont crees dans {@code profiles/<id>}.
 */
public final class ProfileRegistry {

    public static final String FILE_NAME = "profiles.properties";
    public static final String LEGACY_ID = "main";
    public static final String LEGACY_NAME = "Profil principal";
    public static final int MAX_NAME_LENGTH = 40;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path root;
    private final Path file;

    public ProfileRegistry(Path root) {
        this.root = root.toAbsolutePath();
        this.file = this.root.resolve(FILE_NAME);
    }

    /** Profils dans l'ordre de creation. */
    public synchronized List<Profile> list() {
        Properties p = load();
        List<Profile> result = new ArrayList<>();
        for (String id : ids(p)) {
            result.add(profile(p, id));
        }
        return result;
    }

    public synchronized Optional<Profile> find(String id) {
        Properties p = load();
        return ids(p).contains(id) ? Optional.of(profile(p, id)) : Optional.empty();
    }

    /** Dernier profil ouvert (propose par defaut), s'il existe encore. */
    public synchronized Optional<Profile> lastUsed() {
        Properties p = load();
        String id = p.getProperty("last", "");
        return ids(p).contains(id) ? Optional.of(profile(p, id)) : Optional.empty();
    }

    public synchronized void markUsed(String id) {
        Properties p = load();
        if (ids(p).contains(id)) {
            p.setProperty("last", id);
            store(p);
        }
    }

    /** Cree un profil vide (son mot de passe maitre sera demande a la premiere ouverture). */
    public synchronized Profile create(String name) {
        Properties p = load();
        String clean = validName(p, name, null);
        String id;
        do {
            id = HexFormat.of().formatHex(randomBytes(4));
        } while (ids(p).contains(id) || id.equals(LEGACY_ID));
        p.setProperty("profile." + id + ".name", clean);
        p.setProperty("profile." + id + ".dir", "profiles/" + id);
        p.setProperty("order", String.join(",", append(ids(p), id)));
        store(p);
        Profile created = profile(p, id);
        created.directories().createAll();
        return created;
    }

    public synchronized Profile rename(String id, String name) {
        Properties p = load();
        if (!ids(p).contains(id)) {
            throw new IllegalArgumentException("Profil introuvable");
        }
        p.setProperty("profile." + id + ".name", validName(p, name, id));
        store(p);
        return profile(p, id);
    }

    /**
     * Supprime definitivement un profil : base chiffree, trousseau, restauration en
     * attente et sauvegardes d'abord (si l'un d'eux ne peut pas etre efface, le profil
     * reste en place), puis son entree dans la liste. Les journaux (aucune donnee
     * financiere) sont effaces au mieux : un fichier encore ouvert peut subsister.
     * La base doit avoir ete fermee par l'appelant.
     *
     * @throws IOException si les donnees ou les sauvegardes n'ont pas pu etre effacees
     */
    public synchronized void delete(String id) throws IOException {
        Properties p = load();
        if (!ids(p).contains(id)) {
            throw new IllegalArgumentException("Profil introuvable");
        }
        AppDirectories dirs = profile(p, id).directories();
        deleteTree(dirs.databaseFile().getParent());
        deleteTree(dirs.backupsDir());

        List<String> remaining = new ArrayList<>(ids(p));
        remaining.remove(id);
        p.setProperty("order", String.join(",", remaining));
        p.remove("profile." + id + ".name");
        p.remove("profile." + id + ".dir");
        if (id.equals(p.getProperty("last"))) {
            p.remove("last");
        }
        store(p);

        try {
            deleteTree(dirs.logsDir());
            if (!dirs.root().equals(root)) {
                Files.deleteIfExists(dirs.root()); // le profil principal partage la racine : jamais supprimee
            }
        } catch (IOException e) {
            // journal encore ouvert (Windows) : il sera sans profil associe, sans donnee financiere
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            List<Path> all = paths.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path path : all) {
                Files.delete(path);
            }
        }
    }

    // ------------------------------------------------------------------ interne

    private Properties load() {
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException("Liste des profils illisible : " + file, e);
            }
        } else if (hasLegacyData()) {
            p.setProperty("profile." + LEGACY_ID + ".name", LEGACY_NAME);
            p.setProperty("profile." + LEGACY_ID + ".dir", ".");
            p.setProperty("order", LEGACY_ID);
            p.setProperty("last", LEGACY_ID);
            store(p);
        }
        return p;
    }

    /** Donnees d'avant les profils : base, trousseau ou restauration en attente a la racine. */
    private boolean hasLegacyData() {
        AppDirectories legacy = new AppDirectories(root);
        return Files.exists(legacy.databaseFile()) || Files.exists(legacy.keystoreFile())
                || Files.exists(legacy.pendingRestoreFile());
    }

    private static List<String> ids(Properties p) {
        List<String> ids = new ArrayList<>();
        for (String id : p.getProperty("order", "").split(",")) {
            String s = id.strip();
            if (!s.isEmpty() && p.getProperty("profile." + s + ".dir") != null && !ids.contains(s)) {
                ids.add(s);
            }
        }
        return ids;
    }

    private Profile profile(Properties p, String id) {
        String dir = p.getProperty("profile." + id + ".dir");
        Path path = root.resolve(dir).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalStateException("Dossier de profil hors du dossier de l'application");
        }
        return new Profile(id, p.getProperty("profile." + id + ".name", id), new AppDirectories(path));
    }

    private static String validName(Properties p, String name, String exceptId) {
        String clean = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Indiquez un nom");
        }
        if (clean.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Nom trop long (" + MAX_NAME_LENGTH + " caractères maximum)");
        }
        for (String id : ids(p)) {
            if (!id.equals(exceptId) && p.getProperty("profile." + id + ".name", "")
                    .toLowerCase(Locale.ROOT).equals(clean.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Un utilisateur porte déjà ce nom");
            }
        }
        return clean;
    }

    private static List<String> append(List<String> ids, String id) {
        List<String> copy = new ArrayList<>(ids);
        copy.add(id);
        return copy;
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    /** Ecriture atomique : fichier temporaire puis remplacement. */
    private void store(Properties p) {
        try {
            Files.createDirectories(root);
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                p.store(out, "Utilisateurs de l'application (noms uniquement, aucune donnee financiere)");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible d'enregistrer la liste des profils", e);
        }
    }
}
