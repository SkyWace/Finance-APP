package com.financeapp.desktop.ui;

import com.financeapp.core.transaction.TransactionType;
import com.financeapp.desktop.ui.common.AppServices;
import com.financeapp.desktop.ui.common.DataEvents;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.SecurityControls;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import com.financeapp.desktop.ui.dialogs.TransactionDialog;
import com.financeapp.desktop.ui.pages.AccountsPage;
import com.financeapp.desktop.ui.pages.AvailablePage;
import com.financeapp.desktop.ui.pages.CategoriesPage;
import com.financeapp.desktop.ui.pages.DashboardPage;
import com.financeapp.desktop.ui.pages.ForecastPage;
import com.financeapp.desktop.ui.pages.Page;
import com.financeapp.desktop.ui.pages.RecurringPage;
import com.financeapp.desktop.ui.pages.SettingsPage;
import com.financeapp.desktop.ui.pages.TransactionsPage;
import com.financeapp.desktop.ui.pages.UpcomingPage;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Fenetre principale : navigation laterale, en-tete, zone de contenu. */
public final class MainWindow {

    private record NavEntry(String id, String icon, String label, Function<UiContext, Page> factory) {
    }

    private static final NavEntry[] NAV = {
            new NavEntry("dashboard", "◉", "Tableau de bord", DashboardPage::new),
            new NavEntry("accounts", "▣", "Comptes", AccountsPage::new),
            new NavEntry("transactions", "≡", "Transactions", TransactionsPage::new),
            new NavEntry("upcoming", "◷", "À venir", UpcomingPage::new),
            new NavEntry("recurring", "↻", "Récurrences", RecurringPage::new),
            new NavEntry("available", "◎", "Disponible réel", AvailablePage::new),
            new NavEntry("forecast", "↗", "Prévisions", ForecastPage::new),
            new NavEntry("categories", "▦", "Catégories", CategoriesPage::new),
            new NavEntry("settings", "⚙", "Paramètres", SettingsPage::new),
    };

    private final BorderPane root = new BorderPane();
    private final StackPane center = new StackPane();
    private final Label pageTitle = Widgets.label("", "page-title");
    private final ToggleGroup navGroup = new ToggleGroup();
    private final Map<String, ToggleButton> navButtons = new LinkedHashMap<>();
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final UiContext ctx;
    private final ToggleButton privacyButton = new ToggleButton();
    private Page current;

    public MainWindow(AppServices services, Stage stage, SecurityControls security) {
        Formats formats = new Formats();
        DataEvents events = new DataEvents();
        ctx = new UiContext(services, formats, events, stage, this::show, security);
        formats.privacyProperty().set(services.settings().privacyMode());

        root.getStyleClass().add("app-root");
        root.setLeft(buildSidebar(services.properties().name()));
        center.getStyleClass().add("content");
        VBox main = new VBox(buildHeader(), center);
        VBox.setVgrow(center, javafx.scene.layout.Priority.ALWAYS);
        root.setCenter(main);

        events.onChange(() -> {
            if (current != null) {
                current.refresh();
            }
        });
        formats.privacyProperty().addListener((o, old, on) -> {
            services.settings().setPrivacyMode(on);
            updatePrivacyButton();
            if (current != null) {
                current.refresh();
            }
        });
        updatePrivacyButton();
        show("dashboard");
    }

    public Parent root() {
        return root;
    }

    private VBox buildSidebar(String appName) {
        Label brand = Widgets.label(appName, "brand");
        Label tagline = Widgets.label("Finances personnelles", "brand-tagline");
        VBox nav = new VBox(2);
        int index = 1;
        for (NavEntry entry : NAV) {
            ToggleButton b = new ToggleButton();
            Label icon = Widgets.label(entry.icon(), "nav-icon");
            Label text = Widgets.label(entry.label(), "nav-label");
            b.setGraphic(new HBox(12, icon, text));
            b.getStyleClass().add("nav-button");
            b.setToggleGroup(navGroup);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setTooltip(new Tooltip(entry.label() + (index <= 9 ? "  (Ctrl+" + index + ")" : "")));
            b.setOnAction(e -> show(entry.id()));
            navButtons.put(entry.id(), b);
            nav.getChildren().add(b);
            index++;
        }
        Label local = Widgets.label("● Données locales · hors ligne", "sidebar-footer");
        VBox sidebar = new VBox(4, new VBox(2, brand, tagline), nav, Widgets.spacer(), local);
        sidebar.getStyleClass().add("sidebar");
        return sidebar;
    }

    private HBox buildHeader() {
        Button add = new Button("+  Nouvelle opération");
        add.getStyleClass().add("primary");
        add.setTooltip(new Tooltip("Ctrl+N"));
        add.setOnAction(e -> newTransaction());
        privacyButton.getStyleClass().add("ghost");
        privacyButton.selectedProperty().bindBidirectional(ctx.formats().privacyProperty());
        Button lock = new Button("⊘  Verrouiller");
        lock.getStyleClass().add("ghost");
        lock.setTooltip(new Tooltip("Verrouiller l'application : la clé des données est effacée de la mémoire (Ctrl+L)"));
        lock.setOnAction(e -> ctx.security().lockNow());
        HBox header = new HBox(12, pageTitle, Widgets.spacer(), lock, privacyButton, add);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("header");
        return header;
    }

    private void updatePrivacyButton() {
        boolean on = ctx.formats().isPrivacy();
        privacyButton.setText(on ? "◌  Afficher les montants" : "◍  Masquer les montants");
        privacyButton.setTooltip(new Tooltip("Mode confidentialité (Ctrl+M)"));
    }

    private void newTransaction() {
        new TransactionDialog(ctx, null, TransactionType.EXPENSE, null).showAndWait()
                .ifPresent(t -> ctx.events().fireChanged());
    }

    /** Recharge la page affichee (apres un deverrouillage, par exemple). */
    public void refreshCurrent() {
        if (current != null) {
            current.refresh();
        }
    }

    public void show(String id) {
        NavEntry entry = null;
        for (NavEntry n : NAV) {
            if (n.id().equals(id)) {
                entry = n;
            }
        }
        if (entry == null) {
            return;
        }
        NavEntry e = entry;
        Page page = pages.computeIfAbsent(id, k -> e.factory().apply(ctx));
        current = page;
        pageTitle.setText(page.title());
        navButtons.get(id).setSelected(true);
        center.getChildren().setAll(page.view());
        page.refresh();
    }

    /** Raccourcis : Ctrl+1..9 navigation, Ctrl+N nouvelle operation, Ctrl+M masquer les montants (Ctrl+L : voir SecuritySession). */
    public void installShortcuts(Scene scene) {
        for (int i = 0; i < NAV.length && i < 9; i++) {
            String id = NAV[i].id();
            KeyCode digit = KeyCode.valueOf("DIGIT" + (i + 1));
            scene.getAccelerators().put(new KeyCodeCombination(digit, KeyCombination.SHORTCUT_DOWN), guard(scene, () -> show(id)));
            KeyCode numpad = KeyCode.valueOf("NUMPAD" + (i + 1));
            scene.getAccelerators().put(new KeyCodeCombination(numpad, KeyCombination.SHORTCUT_DOWN), guard(scene, () -> show(id)));
        }
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN), guard(scene, this::newTransaction));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.M, KeyCombination.SHORTCUT_DOWN),
                guard(scene, () -> ctx.formats().privacyProperty().set(!ctx.formats().isPrivacy())));
    }

    /** Les raccourcis ne font rien tant que l'ecran de verrouillage est affiche. */
    private Runnable guard(Scene scene, Runnable action) {
        return () -> {
            if (scene.getRoot() == root) {
                action.run();
            }
        };
    }
}
