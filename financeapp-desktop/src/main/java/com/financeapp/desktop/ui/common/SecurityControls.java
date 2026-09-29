package com.financeapp.desktop.ui.common;

import com.financeapp.infra.security.VaultService;
import javafx.beans.property.IntegerProperty;

/** Operations de securite accessibles depuis l'interface. */
public interface SecurityControls {

    /** Verrouille immediatement : cle effacee de la memoire, ecran de deverrouillage. */
    void lockNow();

    VaultService vault();

    /** Delai d'inactivite avant verrouillage automatique (minutes, 0 = jamais). */
    IntegerProperty autoLockMinutesProperty();
}
