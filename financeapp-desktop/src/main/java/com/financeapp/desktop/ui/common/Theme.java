package com.financeapp.desktop.ui.common;

import javafx.collections.ObservableList;
import javafx.scene.Scene;

import java.util.Objects;
import java.util.prefs.Preferences;

/**
 * Theme sombre ("Nuit &amp; Saphir", par defaut) ou clair ("Lin &amp; Prune").
 * Le choix est global a l'installation (il s'applique aussi a l'ecran de
 * deverrouillage, avant l'ouverture d'un profil) et memorise dans les
 * preferences utilisateur du systeme.
 */
public final class Theme {

    private static final String BASE = "/com/financeapp/desktop/theme.css";
    private static final String LIGHT = "/com/financeapp/desktop/theme-light.css";
    private static final String KEY = "theme.light";

    private static Scene scene;
    private static Boolean light;

    private Theme() {
    }

    /** Applique le theme a la fenetre principale et la suit lors des changements. */
    public static void install(Scene mainScene) {
        scene = mainScene;
        apply(mainScene.getStylesheets());
    }

    /** Remplace les feuilles de style du theme dans la liste donnee (scene ou dialogue). */
    public static void apply(ObservableList<String> stylesheets) {
        String base = url(BASE);
        String lightSheet = url(LIGHT);
        stylesheets.removeAll(base, lightSheet);
        stylesheets.add(base);
        if (isLight()) {
            stylesheets.add(lightSheet);
        }
    }

    public static boolean isLight() {
        if (light == null) {
            try {
                light = prefs().getBoolean(KEY, false);
            } catch (RuntimeException e) {
                light = false;
            }
        }
        return light;
    }

    public static void setLight(boolean on) {
        light = on;
        try {
            prefs().putBoolean(KEY, on);
        } catch (RuntimeException e) {
            // preferences indisponibles : le choix vaut pour la session en cours
        }
        if (scene != null) {
            apply(scene.getStylesheets());
        }
    }

    private static Preferences prefs() {
        return Preferences.userNodeForPackage(Theme.class);
    }

    private static String url(String path) {
        return Objects.requireNonNull(Theme.class.getResource(path), path + " introuvable").toExternalForm();
    }
}
