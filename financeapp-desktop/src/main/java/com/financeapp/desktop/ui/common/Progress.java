package com.financeapp.desktop.ui.common;

import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/**
 * Barre de progression. La valeur est toujours doublee d'un texte (montants,
 * pourcentage, etat) par l'appelant : la couleur seule ne porte aucune information.
 */
public final class Progress {

    private Progress() {
    }

    /**
     * @param ratio      avancement (0 a 1, plafonne ; au-dela = depassement)
     * @param stateClass classe CSS de l'etat : progress-ok, progress-warning, progress-danger, progress-done
     */
    public static ProgressBar bar(double ratio, String stateClass) {
        ProgressBar bar = new ProgressBar(Math.max(0, Math.min(1, ratio)));
        bar.getStyleClass().addAll("fa-progress", stateClass);
        bar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(bar, Priority.ALWAYS);
        return bar;
    }
}
