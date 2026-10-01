package com.financeapp.desktop;

import com.financeapp.infra.storage.AppDirectories;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Lecture minimale de la configuration AVANT le demarrage de Spring :
 * l'emplacement des donnees et le nom affiche doivent etre connus pour
 * appliquer une eventuelle restauration et afficher l'ecran de mot de passe.
 * Les proprietes systeme ({@code -Dapp.data-dir=...}) priment sur le fichier.
 */
final class Bootstrap {

    private Bootstrap() {
    }

    /** Dossier racine de l'application ; chaque utilisateur y a son propre sous-dossier (profil). */
    static java.nio.file.Path resolveRoot() {
        Properties props = load();
        String id = System.getProperty("app.id", props.getProperty("app.id", "financeapp"));
        String dataDir = System.getProperty("app.data-dir", props.getProperty("app.data-dir", ""));
        return AppDirectories.resolve(id, dataDir).root();
    }

    /** Nom affiche, necessaire a l'ecran de deverrouillage (avant le demarrage de Spring). */
    static String appName() {
        String name = System.getProperty("app.name", load().getProperty("app.name", "FinanceApp"));
        return name.isBlank() ? "FinanceApp" : name;
    }

    private static Properties load() {
        Properties props = new Properties();
        try (InputStream in = Bootstrap.class.getResourceAsStream("/application.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return props;
    }
}
