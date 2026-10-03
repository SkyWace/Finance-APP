package com.financeapp.desktop;

import javafx.application.Application;

/**
 * Point d'entree. Volontairement distinct de la classe {@link Application} :
 * lance depuis un simple classpath (jar, jpackage), JavaFX refuse de demarrer
 * une classe principale qui etend directement {@code Application}.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        // Verification des mises a jour : passer par le proxy configure dans le systeme, s'il y en a un.
        if (System.getProperty("java.net.useSystemProxies") == null) {
            System.setProperty("java.net.useSystemProxies", "true");
        }
        Application.launch(FinanceFxApplication.class, args);
    }
}
