package com.financeapp.desktop.ui.common;

import java.util.ArrayList;
import java.util.List;

/** Notification "les donnees ont change" : les vues concernees se rechargent. */
public final class DataEvents {

    private final List<Runnable> listeners = new ArrayList<>();

    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    public void fireChanged() {
        List.copyOf(listeners).forEach(Runnable::run);
    }
}
