package com.financeapp.core.settings;

import com.financeapp.core.testing.TestApp;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OptionalMenusSettingTest {

    private final TestApp app = new TestApp(LocalDate.of(2026, 10, 1));

    @Test
    void optionalMenusAreHiddenByDefaultAndTheChoiceIsKept() {
        assertTrue(app.settings.enabledOptionalMenus().isEmpty(), "masques tant qu'on ne les ajoute pas");

        app.settings.setEnabledOptionalMenus(Set.of("loans", "budgets"));
        assertEquals(Set.of("budgets", "loans"), app.settings.enabledOptionalMenus());

        app.settings.setEnabledOptionalMenus(Set.of());
        assertTrue(app.settings.enabledOptionalMenus().isEmpty(), "tout retirer est possible");
    }
}
