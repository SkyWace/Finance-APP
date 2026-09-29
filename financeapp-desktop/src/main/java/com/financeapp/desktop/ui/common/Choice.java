package com.financeapp.desktop.ui.common;

import java.util.Objects;

/** Element de liste deroulante : une valeur et son libelle affiche. */
public record Choice<T>(T value, String label) {

    @Override
    public String toString() {
        return label;
    }

    public boolean is(T other) {
        return Objects.equals(value, other);
    }
}
