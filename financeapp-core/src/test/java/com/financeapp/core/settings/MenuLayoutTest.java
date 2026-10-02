package com.financeapp.core.settings;

import com.financeapp.core.testing.TestApp;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MenuLayoutTest {

    private static final List<String> CATALOG = List.of("dashboard", "accounts", "budgets", "recurring", "analysis", "settings");
    private static final Set<String> OPTIONAL = Set.of("budgets", "analysis");
    private static final Set<String> PINNED = Set.of("settings");

    private final TestApp app = new TestApp(LocalDate.of(2026, 10, 2));

    private MenuLayout load() {
        return app.settings.menuLayout(CATALOG, OPTIONAL, PINNED);
    }

    @Test
    void withoutSavedLayoutTheDefaultOrderIsUsedAndOptionalMenusAreHidden() {
        assertEquals(List.of("dashboard", "accounts", "recurring", "settings"), load().visible());
        assertTrue(app.settings.menusLocked(), "verrouilles par defaut");
    }

    @Test
    void menusAddedWithThePreviousSettingStayVisible() {
        app.settings.setEnabledOptionalMenus(Set.of("analysis"));
        assertEquals(List.of("dashboard", "accounts", "recurring", "analysis", "settings"), load().visible());
    }

    @Test
    void orderAndRemovedMenusAreKept() {
        MenuLayout layout = load()
                .show("analysis")
                .moveNextTo("analysis", "dashboard", false)
                .hide("accounts")
                .moveBy("settings", -1);
        app.settings.saveMenuLayout(layout);

        MenuLayout reloaded = load();
        assertEquals(List.of("analysis", "dashboard", "settings", "recurring"), reloaded.visible());
        assertFalse(reloaded.isVisible("accounts"));

        // Remis plus tard : il reprend sa place.
        assertEquals(List.of("analysis", "dashboard", "accounts", "settings", "recurring"), reloaded.show("accounts").visible());
    }

    @Test
    void pinnedMenusCannotBeRemovedAndMovesStayInBounds() {
        MenuLayout layout = load();
        assertSame(layout, layout.hide("settings"));
        assertFalse(layout.canHide("settings"));
        assertSame(layout, layout.moveBy("dashboard", -1), "deja en haut");
        assertSame(layout, layout.moveBy("settings", 1), "deja en bas");
        // Monter / descendre saute les menus retires.
        assertEquals(List.of("accounts", "dashboard", "recurring", "settings"), layout.moveBy("accounts", -1).visible());
        assertEquals(List.of("dashboard", "accounts", "settings", "recurring"), layout.moveBy("recurring", 1).visible());
    }

    @Test
    void menusOfANewVersionAppearAtTheirDefaultPlace() {
        app.settings.saveMenuLayout(MenuLayout.defaults(List.of("settings", "dashboard", "recurring"), Set.of(), PINNED));

        MenuLayout layout = load();
        assertEquals(List.of("settings", "dashboard", "accounts", "recurring"), layout.visible(),
                "nouveau menu de base : affiche apres son voisin ; nouveau menu facultatif : retire");
        assertTrue(layout.order().containsAll(CATALOG));
    }

    @Test
    void unknownSavedMenusAreIgnored() {
        app.settings.saveMenuLayout(new MenuLayout(List.of("old", "dashboard"), Set.of("old"), PINNED));
        assertFalse(load().order().contains("old"));
    }

    @Test
    void lockIsRemembered() {
        app.settings.setMenusLocked(false);
        assertFalse(app.settings.menusLocked());
    }
}
