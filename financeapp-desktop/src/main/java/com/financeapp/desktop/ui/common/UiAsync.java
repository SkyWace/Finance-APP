package com.financeapp.desktop.ui.common;

import javafx.application.Platform;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Execute les calculs (tableau de bord, previsions...) hors du thread JavaFX
 * pour que l'interface reste fluide, puis applique le resultat sur le thread
 * JavaFX. Un seul thread de travail : les chargements sont serialises.
 */
public final class UiAsync {

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "financeapp-worker");
        t.setDaemon(true);
        return t;
    });

    private UiAsync() {
    }

    public static <T> void load(Supplier<T> work, Consumer<T> onSuccess) {
        load(work, onSuccess, null);
    }

    /** Variante ou les erreurs metier sont affichees par l'appelant ({@code onBusinessError}) plutot qu'en fenetre. */
    public static <T> void load(Supplier<T> work, Consumer<T> onSuccess, Consumer<Throwable> onBusinessError) {
        WORKER.submit(() -> {
            try {
                T result = work.get();
                Platform.runLater(() -> onSuccess.accept(result));
            } catch (com.financeapp.core.service.BusinessException e) {
                Platform.runLater(() -> {
                    if (onBusinessError != null) {
                        onBusinessError.accept(e);
                    } else {
                        Dialogs.error(null, e);
                    }
                });
            } catch (Throwable e) {
                Platform.runLater(() -> Dialogs.error(null, e));
            }
        });
    }
}
