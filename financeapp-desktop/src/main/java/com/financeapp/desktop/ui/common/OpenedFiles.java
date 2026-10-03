package com.financeapp.desktop.ui.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Copies dechiffrees des justificatifs ouverts dans une autre application (lecteur PDF...).
 * Chaque ouverture a son propre sous-dossier ; tout est efface au verrouillage, a la
 * fermeture et au demarrage. Un fichier encore ouvert ailleurs (Windows le bloque) est
 * efface a la purge suivante.
 */
public final class OpenedFiles {

    private OpenedFiles() {
    }

    /** Ecrit une copie temporaire et renvoie son chemin. */
    public static Path write(Path dir, String fileName, byte[] content) throws IOException {
        Path folder = Files.createDirectories(dir.resolve(UUID.randomUUID().toString()));
        Path file = folder.resolve(fileName);
        Files.write(file, content);
        return file;
    }

    /** Efface toutes les copies temporaires (sans erreur si certaines sont encore ouvertes). */
    public static void purge(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(dir)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // encore ouvert dans une autre application : efface a la prochaine purge
                }
            });
        } catch (IOException e) {
            // dossier illisible : rien a faire de plus
        }
    }
}
