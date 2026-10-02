package com.financeapp.core.settings;

import com.financeapp.core.available.HorizonType;
import com.financeapp.core.port.SettingsRepository;

import java.util.Currency;

/** Acces type aux parametres utilisateur, avec valeurs par defaut. */
public final class SettingsService {

    static final String BASE_CURRENCY = "base.currency";
    static final String AVAILABLE_HORIZON = "available.horizon";
    static final String AVAILABLE_INCLUDE_INCOME = "available.include_income";
    static final String BACKUP_AUTO_ENABLED = "backup.auto.enabled";
    static final String BACKUP_AUTO_KEEP = "backup.auto.keep";
    static final String PRIVACY_MODE = "ui.privacy_mode";
    static final String AUTO_LOCK_MINUTES = "security.auto_lock_minutes";
    static final String DISMISSED_PAYMENTS = "subscriptions.dismissed";
    static final String OPTIONAL_MENUS = "ui.optional_menus";
    static final String MENU_ORDER = "ui.menu_order";
    static final String MENU_HIDDEN = "ui.menu_hidden";
    static final String MENUS_LOCKED = "ui.menus_locked";
    static final String UPDATE_CHECK = "update.check_enabled";
    static final String UPDATE_LAST_CHECK = "update.last_check";
    static final String UPDATE_IGNORED = "update.ignored_version";

    public static final int MIN_BACKUPS_KEPT = 2;

    private final SettingsRepository repository;

    public SettingsService(SettingsRepository repository) {
        this.repository = repository;
    }

    /** Devise de reference des totaux. Aucune conversion n'est faite entre devises. */
    public Currency baseCurrency() {
        try {
            return repository.get(BASE_CURRENCY).map(Currency::getInstance).orElse(Currency.getInstance("EUR"));
        } catch (IllegalArgumentException e) {
            return Currency.getInstance("EUR");
        }
    }

    public void setBaseCurrency(Currency currency) {
        repository.put(BASE_CURRENCY, currency.getCurrencyCode());
    }

    public HorizonType defaultHorizon() {
        try {
            return repository.get(AVAILABLE_HORIZON).map(HorizonType::valueOf).orElse(HorizonType.END_OF_MONTH);
        } catch (IllegalArgumentException e) {
            return HorizonType.END_OF_MONTH;
        }
    }

    public void setDefaultHorizon(HorizonType type) {
        repository.put(AVAILABLE_HORIZON, type.name());
    }

    public boolean includeCertainIncome() {
        return bool(AVAILABLE_INCLUDE_INCOME, true);
    }

    public void setIncludeCertainIncome(boolean value) {
        repository.put(AVAILABLE_INCLUDE_INCOME, Boolean.toString(value));
    }

    public boolean autoBackupEnabled() {
        return bool(BACKUP_AUTO_ENABLED, true);
    }

    public void setAutoBackupEnabled(boolean value) {
        repository.put(BACKUP_AUTO_ENABLED, Boolean.toString(value));
    }

    /** Nombre de sauvegardes automatiques conservees (rotation). */
    public int autoBackupKeep() {
        try {
            return repository.get(BACKUP_AUTO_KEEP).map(Integer::parseInt).map(v -> Math.max(MIN_BACKUPS_KEPT, v)).orElse(10);
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    public void setAutoBackupKeep(int value) {
        repository.put(BACKUP_AUTO_KEEP, Integer.toString(Math.max(MIN_BACKUPS_KEPT, value)));
    }

    public boolean privacyMode() {
        return bool(PRIVACY_MODE, false);
    }

    public void setPrivacyMode(boolean value) {
        repository.put(PRIVACY_MODE, Boolean.toString(value));
    }

    /** Verrouillage automatique apres ce nombre de minutes d'inactivite ; 0 = jamais. */
    public int autoLockMinutes() {
        try {
            return repository.get(AUTO_LOCK_MINUTES).map(Integer::parseInt).map(v -> Math.max(0, v)).orElse(5);
        } catch (NumberFormatException e) {
            return 5;
        }
    }

    public void setAutoLockMinutes(int minutes) {
        repository.put(AUTO_LOCK_MINUTES, Integer.toString(Math.max(0, minutes)));
    }

    /** Libelles normalises des paiements reguliers que l'utilisateur a choisi d'ignorer. */
    public java.util.Set<String> dismissedRecurringPayments() {
        return repository.get(DISMISSED_PAYMENTS)
                .map(v -> java.util.Arrays.stream(v.split("\n")).filter(x -> !x.isBlank())
                        .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new)))
                .orElseGet(java.util.TreeSet::new);
    }

    public void setDismissedRecurringPayments(java.util.Set<String> labels) {
        repository.put(DISMISSED_PAYMENTS, String.join("\n", new java.util.TreeSet<>(labels)));
    }

    /**
     * Menus facultatifs que l'utilisateur a ajoutes a la navigation (identifiants
     * d'ecran). Vide par defaut : ces menus sont masques tant qu'on ne les ajoute pas.
     */
    public java.util.Set<String> enabledOptionalMenus() {
        return repository.get(OPTIONAL_MENUS)
                .map(v -> java.util.Arrays.stream(v.split(",")).map(String::strip).filter(x -> !x.isEmpty())
                        .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new)))
                .orElseGet(java.util.TreeSet::new);
    }

    public void setEnabledOptionalMenus(java.util.Set<String> ids) {
        repository.put(OPTIONAL_MENUS, String.join(",", new java.util.TreeSet<>(ids)));
    }

    /**
     * Disposition de la barre laterale (ordre, menus retires). Sans reglage enregistre :
     * ordre par defaut, menus facultatifs retires sauf ceux ajoutes avec l'ancien reglage.
     */
    public MenuLayout menuLayout(java.util.List<String> catalog, java.util.Set<String> hiddenByDefault,
                                 java.util.Set<String> pinned) {
        java.util.List<String> order = repository.get(MENU_ORDER).map(SettingsService::csv).orElse(java.util.List.of());
        java.util.Set<String> hidden = repository.get(MENU_HIDDEN)
                .map(v -> (java.util.Set<String>) new java.util.LinkedHashSet<>(csv(v))).orElse(null);
        return MenuLayout.resolve(catalog, hiddenByDefault, pinned, order, hidden, enabledOptionalMenus());
    }

    public void saveMenuLayout(MenuLayout layout) {
        repository.put(MENU_ORDER, String.join(",", layout.order()));
        repository.put(MENU_HIDDEN, String.join(",", new java.util.TreeSet<>(layout.hidden())));
    }

    /** Menus verrouilles (par defaut) : ils ne peuvent pas etre deplaces par erreur. */
    public boolean menusLocked() {
        return bool(MENUS_LOCKED, true);
    }

    public void setMenusLocked(boolean value) {
        repository.put(MENUS_LOCKED, Boolean.toString(value));
    }

    private static java.util.List<String> csv(String value) {
        return java.util.Arrays.stream(value.split(",")).map(String::strip).filter(x -> !x.isEmpty()).toList();
    }

    /** Verification des nouvelles versions a l'ouverture ; desactivee par defaut (aucune connexion). */
    public boolean updateCheckEnabled() {
        return bool(UPDATE_CHECK, false);
    }

    public void setUpdateCheckEnabled(boolean value) {
        repository.put(UPDATE_CHECK, Boolean.toString(value));
    }

    public java.util.Optional<java.time.LocalDate> lastUpdateCheck() {
        try {
            return repository.get(UPDATE_LAST_CHECK).map(java.time.LocalDate::parse);
        } catch (java.time.format.DateTimeParseException e) {
            return java.util.Optional.empty();
        }
    }

    public void setLastUpdateCheck(java.time.LocalDate date) {
        repository.put(UPDATE_LAST_CHECK, date.toString());
    }

    public java.util.Optional<String> ignoredUpdateVersion() {
        return repository.get(UPDATE_IGNORED).filter(v -> !v.isBlank());
    }

    public void setIgnoredUpdateVersion(String version) {
        repository.put(UPDATE_IGNORED, version);
    }

    private boolean bool(String key, boolean defaultValue) {
        return repository.get(key).map(Boolean::parseBoolean).orElse(defaultValue);
    }
}
