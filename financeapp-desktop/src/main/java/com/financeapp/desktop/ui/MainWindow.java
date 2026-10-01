package com.financeapp.desktop.ui;

import com.financeapp.core.transaction.TransactionType;
import com.financeapp.desktop.ui.common.AppServices;
import com.financeapp.desktop.ui.common.DataEvents;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.Theme;
import com.financeapp.desktop.ui.common.SecurityControls;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import com.financeapp.desktop.ui.dialogs.TransactionDialog;
import com.financeapp.desktop.ui.pages.AccountsPage;
import com.financeapp.desktop.ui.pages.AnalysisPage;
import com.financeapp.desktop.ui.pages.BudgetsPage;
import com.financeapp.desktop.ui.pages.CalendarPage;
import com.financeapp.desktop.ui.pages.ImportsPage;
import com.financeapp.desktop.ui.pages.InboxPage;
import com.financeapp.desktop.ui.pages.BankSyncPage;
import com.financeapp.desktop.ui.pages.LoansPage;
import com.financeapp.desktop.ui.pages.SimulationsPage;
import com.financeapp.desktop.ui.pages.RulesPage;
import com.financeapp.desktop.ui.pages.SavingsGoalsPage;
import com.financeapp.desktop.ui.pages.SavingsPage;
import com.financeapp.desktop.ui.pages.SubscriptionsPage;
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
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
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

    /** @param optional menu facultatif : masque tant que l'utilisateur ne l'ajoute pas (bouton "+"). */
    private record NavEntry(String id, String icon, String label, Function<UiContext, Page> factory, boolean optional) {

        NavEntry(String id, String icon, String label, Function<UiContext, Page> factory) {
            this(id, icon, label, factory, false);
        }
    }

    private static final NavEntry[] NAV = {
            new NavEntry("dashboard", "◉", "Tableau de bord", DashboardPage::new),
            new NavEntry("accounts", "▣", "Comptes", AccountsPage::new),
            new NavEntry("transactions", "≡", "Transactions", TransactionsPage::new),
            new NavEntry("upcoming", "◷", "À venir", UpcomingPage::new),
            new NavEntry("inbox", "✉", "À valider", InboxPage::new, true),
            new NavEntry("calendar", "▤", "Calendrier", CalendarPage::new),
            new NavEntry("budgets", "◔", "Budgets", BudgetsPage::new, true),
            new NavEntry("savings", "◆", "Épargne", SavingsPage::new),
            new NavEntry("goals", "★", "Objectifs", SavingsGoalsPage::new, true),
            new NavEntry("available", "◎", "Disponible réel", AvailablePage::new),
            new NavEntry("forecast", "↗", "Prévisions", ForecastPage::new, true),
            new NavEntry("simulations", "⚖", "Simulations", SimulationsPage::new, true),
            new NavEntry("recurring", "↻", "Récurrences", RecurringPage::new),
            new NavEntry("subscriptions", "♺", "Abonnements", SubscriptionsPage::new),
            new NavEntry("loans", "▭", "Crédits", LoansPage::new, true),
            new NavEntry("analysis", "▥", "Analyses", AnalysisPage::new, true),
            new NavEntry("imports", "⇩", "Import", ImportsPage::new),
            new NavEntry("banksync", "⇄", "Synchronisation", BankSyncPage::new),
            new NavEntry("rules", "⚑", "Règles", RulesPage::new),
            new NavEntry("categories", "▦", "Catégories", CategoriesPage::new),
            new NavEntry("settings", "⚙", "Paramètres", SettingsPage::new),
    };

    private final BorderPane root = new BorderPane();
    private final StackPane center = new StackPane();
    private final Label pageTitle = Widgets.label("", "page-title");
    private final ToggleGroup navGroup = new ToggleGroup();
    private final Map<String, ToggleButton> navButtons = new LinkedHashMap<>();
    private final VBox nav = new VBox(2);
    private final java.util.List<NavEntry> visible = new java.util.ArrayList<>();
    private String currentId;
    private final Label inboxBadge = Widgets.label("", "nav-badge");
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final UiContext ctx;
    private final ToggleButton privacyButton = new ToggleButton();
    private Page current;
    private Button userButton;

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
            updateInboxBadge();
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
        updateInboxBadge();
        show("dashboard");
    }

    public Parent root() {
        return root;
    }

    private VBox buildSidebar(String appName) {
        Label brand = Widgets.label(appName, "brand");
        Label tagline = Widgets.label("Finances personnelles", "brand-tagline");
        buildNav();
        Label local = Widgets.label("● Données locales · hors ligne", "sidebar-footer");
        javafx.scene.control.ScrollPane navScroll = new javafx.scene.control.ScrollPane(nav);
        navScroll.setFitToWidth(true);
        navScroll.getStyleClass().add("nav-scroll");
        VBox.setVgrow(navScroll, javafx.scene.layout.Priority.ALWAYS);
        VBox sidebar = new VBox(4, new VBox(2, brand, tagline), navScroll, local);
        sidebar.getStyleClass().add("sidebar");
        return sidebar;
    }

    /** (Re)construit la navigation : menus de base, menus facultatifs ajoutes, puis le bouton "+". */
    private void buildNav() {
        java.util.Set<String> enabled = ctx.services().settings().enabledOptionalMenus();
        nav.getChildren().clear();
        navButtons.clear();
        visible.clear();
        for (NavEntry entry : NAV) {
            if (entry.optional() && !enabled.contains(entry.id())) {
                continue;
            }
            visible.add(entry);
            int index = visible.size();
            ToggleButton b = new ToggleButton();
            Label icon = Widgets.label(entry.icon(), "nav-icon");
            Label text = Widgets.label(entry.label(), "nav-label");
            HBox graphic = new HBox(12, icon, text);
            if (entry.id().equals("inbox")) {
                inboxBadge.visibleProperty().unbind();
                inboxBadge.visibleProperty().bind(inboxBadge.textProperty().isNotEmpty());
                graphic.getChildren().addAll(Widgets.spacer(), inboxBadge);
                graphic.setMaxWidth(Double.MAX_VALUE);
            }
            b.setGraphic(graphic);
            b.getStyleClass().add("nav-button");
            b.setToggleGroup(navGroup);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setTooltip(new Tooltip(entry.label() + (index <= 9 ? "  (Ctrl+" + index + ")" : "")));
            b.setOnAction(e -> show(entry.id()));
            if (entry.optional()) {
                MenuItem remove = new MenuItem("Retirer « " + entry.label() + " » du menu");
                remove.setOnAction(e -> setMenuEnabled(entry.id(), false));
                b.setContextMenu(new ContextMenu(remove));
            }
            if (entry.id().equals(currentId)) {
                b.setSelected(true);
            }
            navButtons.put(entry.id(), b);
            nav.getChildren().add(b);
        }

        Button more = new Button("+  Ajouter des menus");
        more.getStyleClass().addAll("ghost", "nav-more");
        more.setMaxWidth(Double.MAX_VALUE);
        more.setTooltip(new Tooltip("Afficher ou masquer les menus facultatifs"));
        ContextMenu choices = new ContextMenu();
        for (NavEntry entry : NAV) {
            if (entry.optional()) {
                CheckMenuItem item = new CheckMenuItem(entry.icon() + "  " + entry.label());
                item.setSelected(enabled.contains(entry.id()));
                item.setOnAction(e -> setMenuEnabled(entry.id(), item.isSelected()));
                choices.getItems().add(item);
            }
        }
        more.setOnAction(e -> choices.show(more, javafx.geometry.Side.TOP, 0, 0));
        VBox.setMargin(more, new javafx.geometry.Insets(8, 0, 0, 0));
        nav.getChildren().add(more);
    }

    private void setMenuEnabled(String id, boolean on) {
        java.util.Set<String> enabled = new java.util.TreeSet<>(ctx.services().settings().enabledOptionalMenus());
        if (on) {
            enabled.add(id);
        } else {
            enabled.remove(id);
        }
        ctx.services().settings().setEnabledOptionalMenus(enabled);
        buildNav();
        if (on) {
            show(id);
        } else if (id.equals(currentId)) {
            show("dashboard");
        }
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
        Button user = new Button("◯  " + ctx.security().profileName());
        user.getStyleClass().add("ghost");
        user.setTooltip(new Tooltip("Changer d'utilisateur (vos données sont sauvegardées et verrouillées)"));
        user.setOnAction(e -> ctx.security().switchUser());
        userButton = user;
        Button theme = new Button();
        theme.getStyleClass().addAll("ghost", "theme-toggle");
        // Icone seule : l'en-tete reste lisible meme en fenetre etroite ; l'infobulle dit ce qu'elle fait.
        Runnable themeLabel = () -> {
            theme.setText(Theme.isLight() ? "☾" : "☀");
            theme.setTooltip(new Tooltip(Theme.isLight() ? "Passer au thème sombre" : "Passer au thème clair"));
        };
        themeLabel.run();
        theme.setOnAction(e -> {
            Theme.setLight(!Theme.isLight());
            themeLabel.run();
        });
        HBox header = new HBox(12, pageTitle, Widgets.spacer(), user, lock, theme, privacyButton, add);
        // Les boutons gardent leur largeur ; seul le titre se raccourcit si la fenetre est etroite.
        for (var node : header.getChildren()) {
            if (node instanceof javafx.scene.control.ButtonBase b) {
                b.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            }
        }
        pageTitle.setMinWidth(0);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("header");
        return header;
    }

    private void updatePrivacyButton() {
        boolean on = ctx.formats().isPrivacy();
        privacyButton.setText(on ? "◌  Afficher" : "◍  Masquer");
        privacyButton.setTooltip(new Tooltip((on ? "Afficher" : "Masquer") + " les montants — mode confidentialité (Ctrl+M)"));
    }

    private void newTransaction() {
        new TransactionDialog(ctx, null, TransactionType.EXPENSE, null).showAndWait()
                .ifPresent(t -> ctx.events().fireChanged());
    }

    /** Nombre d'operations importees a valider, affiche a cote de "A valider". */
    private void updateInboxBadge() {
        try {
            long n = ctx.services().inbox().count();
            inboxBadge.setText(n == 0 ? "" : Long.toString(n));
        } catch (RuntimeException e) {
            inboxBadge.setText("");
        }
    }

    /** Recharge la page affichee (apres un deverrouillage, par exemple). */
    public void refreshCurrent() {
        updateInboxBadge();
        userButton.setText("◯  " + ctx.security().profileName());
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
        currentId = id;
        pageTitle.setText(page.title());
        // Un menu facultatif masque reste accessible par les liens (ex. "Valider maintenant").
        ToggleButton button = navButtons.get(id);
        if (button != null) {
            button.setSelected(true);
        } else if (navGroup.getSelectedToggle() != null) {
            navGroup.getSelectedToggle().setSelected(false);
        }
        center.getChildren().setAll(page.view());
        page.refresh();
    }

    /** Raccourcis : Ctrl+1..9 navigation (menus affiches), Ctrl+N nouvelle operation, Ctrl+M masquer les montants (Ctrl+L : voir SecuritySession). */
    public void installShortcuts(Scene scene) {
        for (int i = 0; i < 9; i++) {
            int position = i;
            Runnable go = () -> {
                if (position < visible.size()) {
                    show(visible.get(position).id());
                }
            };
            KeyCode digit = KeyCode.valueOf("DIGIT" + (i + 1));
            scene.getAccelerators().put(new KeyCodeCombination(digit, KeyCombination.SHORTCUT_DOWN), guard(scene, go));
            KeyCode numpad = KeyCode.valueOf("NUMPAD" + (i + 1));
            scene.getAccelerators().put(new KeyCodeCombination(numpad, KeyCombination.SHORTCUT_DOWN), guard(scene, go));
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
