package com.financeapp.desktop;

import com.financeapp.infra.storage.AppDirectories;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Lecture minimale de la configuration AVANT le demarrage de Spring :
 * l'emplacement des donnees doit etre connu pour configurer le fichier de
 * log et appliquer une eventuelle restauration avant l'ouverture de la base.
 * Les proprietes systeme ({@code -Dapp.data-dir=...}) priment sur le fichier.
 */
final class Bootstrap {

    private Bootstrap() {
    }

    static AppDirectories resolveDirectories() {
        Properties props = new Properties();
        try (InputStream in = Bootstrap.class.getResourceAsStream("/application.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String id = System.getProperty("app.id", props.getProperty("app.id", "financeapp"));
        String dataDir = System.getProperty("app.data-dir", props.getProperty("app.data-dir", ""));
        return AppDirectories.resolve(id, dataDir).createAll();
    }
}
