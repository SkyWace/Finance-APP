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
    private final HBox updateBar = new HBox(12);

    public MainWindow(AppServices services, Stage stage, SecurityControls security) {
        Formats formats = new Formats();
        DataEvents events = new DataEvents();
        ctx = new UiContext(services, formats, events, stage, this::show, security);
        formats.privacyProperty().set(services.settings().privacyMode());

        root.getStyleClass().add("app-root");
        root.setLeft(buildSidebar(services.properties().name()));
        center.getStyleClass().add("content");
        updateBar.setVisible(false);
        updateBar.managedProperty().bind(updateBar.visibleProperty());
        VBox main = new VBox(buildHeader(), updateBar, center);
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
        checkForUpdate();
    }

    /**
     * Verification automatique des nouvelles versions, si l'utilisateur l'a activee
     * (au plus une fois par jour). Une erreur reseau est ignoree en silence.
     */
    private void checkForUpdate() {
        var updates = ctx.services().updates();
        if (!updates.autoCheckEnabled()) {
            return;
        }
        com.financeapp.desktop.ui.common.UiAsync.load(() -> {
            try {
                return updates.checkIfDue();
            } catch (java.io.IOException | RuntimeException e) {
                return java.util.Optional.<com.financeapp.core.update.AvailableRelease>empty();
            }
        }, found -> found.ifPresent(this::showUpdateBar));
    }

    private void showUpdateBar(com.financeapp.core.update.AvailableRelease release) {
        Label text = Widgets.label("Une nouvelle version de " + ctx.services().properties().name() + " est disponible : "
                + release.version() + (release.publishedOn() == null ? "" : " (publiée le "
                + Formats.date(release.publishedOn()) + ")") + ".", "op-label");
        Button open = new Button("Voir la nouvelle version");
        open.getStyleClass().addAll("primary", "compact");
        open.setOnAction(e -> com.financeapp.desktop.ui.common.Browser.open(release.pageUrl()));
        Button later = new Button("Plus tard");
        later.getStyleClass().addAll("ghost", "compact");
        later.setTooltip(new Tooltip("Ne plus signaler cette version (elle reste visible dans Paramètres > Mises à jour)"));
        later.setOnAction(e -> {
            ctx.services().updates().ignore(release);
            updateBar.setVisible(false);
        });
        updateBar.getChildren().setAll(Widgets.label("↑", "op-label"), text, Widgets.spacer(), open, later);
        updateBar.setAlignment(Pos.CENTER_LEFT);
        updateBar.getStyleClass().setAll("update-bar");
        updateBar.setVisible(true);
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

    /** Menus qui restent toujours dans la barre (sinon les reglages deviendraient introuvables). */
    private static final java.util.Set<String> PINNED = java.util.Set.of("settings");
    private static final javafx.scene.input.DataFormat MENU_ID = new javafx.scene.input.DataFormat("application/x-financeapp-menu");

    private com.financeapp.core.settings.MenuLayout layout() {
        java.util.List<String> catalog = new java.util.ArrayList<>();
        java.util.Set<String> optional = new java.util.HashSet<>();
        for (NavEntry entry : NAV) {
            catalog.add(entry.id());
            if (entry.optional()) {
                optional.add(entry.id());
            }
        }
        return ctx.services().settings().menuLayout(catalog, optional, PINNED);
    }

    private static NavEntry entry(String id) {
        for (NavEntry n : NAV) {
            if (n.id().equals(id)) {
                return n;
            }
        }
        return null;
    }

    /**
     * (Re)construit la navigation dans l'ordre choisi. Verrouillee (par defaut), elle ne bouge pas ;
     * deverrouillee, les menus se deplacent par glisser-deposer ou par "Monter" / "Descendre".
     */
    private void buildNav() {
        var settings = ctx.services().settings();
        var layout = layout();
        boolean locked = settings.menusLocked();
        nav.getChildren().clear();
        navButtons.clear();
        visible.clear();
        nav.getStyleClass().remove("nav-unlocked");
        if (!locked) {
            nav.getStyleClass().add("nav-unlocked");
        }
        for (String id : layout.visible()) {
            NavEntry entry = entry(id);
            visible.add(entry);
            int index = visible.size();
            ToggleButton b = new ToggleButton();
            Label icon = Widgets.label(entry.icon(), "nav-icon");
            Label text = Widgets.label(entry.label(), "nav-label");
            HBox graphic = new HBox(12, icon, text);
            graphic.setMaxWidth(Double.MAX_VALUE);
            if (entry.id().equals("inbox")) {
                inboxBadge.visibleProperty().unbind();
                inboxBadge.visibleProperty().bind(inboxBadge.textProperty().isNotEmpty());
                graphic.getChildren().addAll(Widgets.spacer(), inboxBadge);
            }
            if (!locked) {
                if (!entry.id().equals("inbox")) {
                    graphic.getChildren().add(Widgets.spacer());
                }
                text.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
                graphic.getChildren().add(Widgets.label("⇅", "nav-handle"));
            }
            b.setGraphic(graphic);
            b.getStyleClass().add("nav-button");
            b.setToggleGroup(navGroup);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setTooltip(new Tooltip(entry.label() + (index <= 9 ? "  (Ctrl+" + index + ")" : "")
                    + (locked ? "" : "\nGlissez pour déplacer")));
            b.setOnAction(e -> show(entry.id()));
            b.setContextMenu(navMenu(entry, layout, locked));
            if (!locked) {
                enableDrag(b, entry.id());
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
        more.setTooltip(new Tooltip("Remettre un menu retiré, ou en ajouter un"));
        ContextMenu choices = new ContextMenu();
        for (String id : layout.order()) {
            NavEntry entry = entry(id);
            if (!layout.canHide(id)) {
                continue;
            }
            CheckMenuItem item = new CheckMenuItem(entry.icon() + "  " + entry.label());
            item.setSelected(layout.isVisible(id));
            item.setOnAction(e -> setMenuEnabled(entry.id(), item.isSelected()));
            choices.getItems().add(item);
        }
        MenuItem reset = new MenuItem("Rétablir les menus d'origine");
        reset.setOnAction(e -> resetMenus());
        choices.getItems().addAll(new javafx.scene.control.SeparatorMenuItem(), reset);
        more.setOnAction(e -> choices.show(more, javafx.geometry.Side.TOP, 0, 0));

        Button lock = new Button(locked ? "⇅  Organiser les menus" : "✓  Verrouiller");
        lock.getStyleClass().addAll(locked ? "ghost" : "primary", "nav-more");
        lock.setMaxWidth(Double.MAX_VALUE);
        lock.setTooltip(new Tooltip(locked
                ? "Déverrouiller pour déplacer les menus (glisser-déposer)"
                : "Verrouiller : les menus ne bougent plus"));
        lock.setOnAction(e -> setMenusLocked(!locked));

        VBox.setMargin(more, new javafx.geometry.Insets(8, 0, 0, 0));
        nav.getChildren().addAll(more, lock);
        if (!locked) {
            Label hint = Widgets.label("Glissez un menu pour le déplacer, clic droit pour le retirer.", "nav-hint");
            hint.setWrapText(true);
            nav.getChildren().add(hint);
        }
    }

    /** Clic droit sur un menu : le retirer, le deplacer (deverrouille), verrouiller / deverrouiller. */
    private ContextMenu navMenu(NavEntry entry, com.financeapp.core.settings.MenuLayout layout, boolean locked) {
        ContextMenu menu = new ContextMenu();
        if (layout.canHide(entry.id())) {
            MenuItem remove = new MenuItem("Retirer « " + entry.label() + " » du menu");
            remove.setOnAction(e -> setMenuEnabled(entry.id(), false));
            menu.getItems().add(remove);
        }
        if (!locked) {
            java.util.List<String> shown = layout.visible();
            MenuItem up = new MenuItem("Monter");
            up.setDisable(shown.indexOf(entry.id()) == 0);
            up.setOnAction(e -> saveLayout(layout().moveBy(entry.id(), -1)));
            MenuItem down = new MenuItem("Descendre");
            down.setDisable(shown.indexOf(entry.id()) == shown.size() - 1);
            down.setOnAction(e -> saveLayout(layout().moveBy(entry.id(), 1)));
            menu.getItems().addAll(up, down);
        }
        if (!menu.getItems().isEmpty()) {
            menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
        }
        MenuItem toggle = new MenuItem(locked ? "Déverrouiller les menus (pour les déplacer)" : "Verrouiller les menus");
        toggle.setOnAction(e -> setMenusLocked(!locked));
        menu.getItems().add(toggle);
        return menu;
    }

    /** Glisser-deposer : le menu depose se place avant ou apres celui vise (selon la moitie survolee). */
    private void enableDrag(ToggleButton b, String id) {
        b.setOnDragDetected(e -> {
            var board = b.startDragAndDrop(javafx.scene.input.TransferMode.MOVE);
            var content = new javafx.scene.input.ClipboardContent();
            content.put(MENU_ID, id);
            board.setContent(content);
            board.setDragView(b.snapshot(null, null), e.getX(), e.getY());
            b.getStyleClass().add("nav-dragging");
            e.consume();
        });
        b.setOnDragDone(e -> b.getStyleClass().remove("nav-dragging"));
        b.setOnDragOver(e -> {
            Object dragged = e.getDragboard().getContent(MENU_ID);
            if (dragged != null && !id.equals(dragged)) {
                e.acceptTransferModes(javafx.scene.input.TransferMode.MOVE);
                boolean after = e.getY() > b.getHeight() / 2;
                b.getStyleClass().removeAll("drop-before", "drop-after");
                b.getStyleClass().add(after ? "drop-after" : "drop-before");
            }
            e.consume();
        });
        b.setOnDragExited(e -> b.getStyleClass().removeAll("drop-before", "drop-after"));
        b.setOnDragDropped(e -> {
            Object dragged = e.getDragboard().getContent(MENU_ID);
            boolean done = false;
            if (dragged instanceof String moved && !moved.equals(id)) {
                boolean after = e.getY() > b.getHeight() / 2;
                // Apres le deplacement la barre est reconstruite : on sort du traitement de l'evenement.
                javafx.application.Platform.runLater(() -> saveLayout(layout().moveNextTo(moved, id, after)));
                done = true;
            }
            e.setDropCompleted(done);
            e.consume();
        });
    }

    private void saveLayout(com.financeapp.core.settings.MenuLayout layout) {
        ctx.services().settings().saveMenuLayout(layout);
        buildNav();
    }

    private void setMenusLocked(boolean locked) {
        ctx.services().settings().setMenusLocked(locked);
        buildNav();
    }

    private void resetMenus() {
        java.util.List<String> catalog = new java.util.ArrayList<>();
        java.util.List<String> optional = new java.util.ArrayList<>();
        for (NavEntry entry : NAV) {
            catalog.add(entry.id());
            if (entry.optional()) {
                optional.add(entry.id());
            }
        }
        saveLayout(com.financeapp.core.settings.MenuLayout.defaults(catalog, optional, PINNED));
        if (currentId != null && !layout().isVisible(currentId)) {
            show(visible.get(0).id());
        }
    }

    private void setMenuEnabled(String id, boolean on) {
        var layout = layout();
        saveLayout(on ? layout.show(id) : layout.hide(id));
        if (on) {
            show(id);
        } else if (id.equals(currentId)) {
            show(visible.get(0).id());
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
