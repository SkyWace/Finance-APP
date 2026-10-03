package com.financeapp.desktop.ui.common;

import javafx.application.HostServices;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

/** Ouverture d'une adresse dans le navigateur systeme (jamais dans une fenetre integree). */
public final class Browser {

    private static volatile HostServices hostServices;

    private Browser() {
    }

    public static void init(HostServices services) {
        hostServices = services;
    }

    /** @return {@code false} si aucun navigateur n'a pu etre sollicite (l'appelant propose alors de copier l'adresse) */
    public static boolean open(String url) {
        if (hostServices == null || !url.startsWith("https://")) {
            return false;
        }
        try {
            hostServices.showDocument(url);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Ouvre un fichier local avec l'application associee du systeme (lecteur PDF...). */
    public static boolean openFile(java.nio.file.Path file) {
        if (hostServices == null) {
            return false;
        }
        try {
            hostServices.showDocument(file.toUri().toString());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static void copy(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }
}
