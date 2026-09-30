package com.financeapp.desktop.ui.common;

import javafx.stage.Window;

import java.util.function.Consumer;

/**
 * Ce que chaque page recoit : services, formatage (confidentialite),
 * evenements de donnees, navigation et commandes de securite. Le filtre par
 * compte ({@code accountFilter}, {@code null} = tous) est partage entre les
 * ecrans : le compte choisi reste selectionne d'un ecran a l'autre.
 */
public record UiContext(AppServices services, Formats formats, DataEvents events,
                        Window window, Consumer<String> navigator, SecurityControls security,
                        javafx.beans.property.ObjectProperty<Long> accountFilter) {

    public UiContext(AppServices services, Formats formats, DataEvents events, Window window,
                     Consumer<String> navigator, SecurityControls security) {
        this(services, formats, events, window, navigator, security,
                new javafx.beans.property.SimpleObjectProperty<>());
    }

    public void navigate(String pageId) {
        navigator.accept(pageId);
    }
}
