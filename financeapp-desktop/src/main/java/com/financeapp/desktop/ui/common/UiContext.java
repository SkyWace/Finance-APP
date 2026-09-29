package com.financeapp.desktop.ui.common;

import javafx.stage.Window;

import java.util.function.Consumer;

/**
 * Ce que chaque page recoit : services, formatage (confidentialite),
 * evenements de donnees et navigation.
 */
public record UiContext(AppServices services, Formats formats, DataEvents events,
                        Window window, Consumer<String> navigator) {

    public void navigate(String pageId) {
        navigator.accept(pageId);
    }
}
