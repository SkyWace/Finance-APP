package com.financeapp.desktop.ui.pages;

import com.financeapp.desktop.ui.common.UiContext;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;

/** Ecran de l'application. Le contenu est (re)construit par {@link #refresh()}. */
public abstract class Page {

    protected final UiContext ctx;
    protected final VBox content = new VBox(18);
    private final ScrollPane scroll = new ScrollPane(content);

    protected Page(UiContext ctx) {
        this.ctx = ctx;
        content.getStyleClass().add("page");
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("page-scroll");
    }

    public abstract String title();

    /** Recharge les donnees et reconstruit l'affichage. */
    public abstract void refresh();

    /** Les pages avec leur propre defilement (tableaux) peuvent renvoyer {@code content} directement. */
    public Node view() {
        return scroll;
    }

    protected javafx.stage.Window window() {
        return ctx.window();
    }
}
