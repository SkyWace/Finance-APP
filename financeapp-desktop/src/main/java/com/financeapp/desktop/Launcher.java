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
        Application.launch(FinanceFxApplication.class, args);
    }
}
