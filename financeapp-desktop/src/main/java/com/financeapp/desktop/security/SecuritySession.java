package com.financeapp.desktop.security;

import com.financeapp.core.settings.SettingsService;
import com.financeapp.desktop.DesktopApplication;
import com.financeapp.desktop.ui.MainWindow;
import com.financeapp.desktop.ui.common.AppServices;
import com.financeapp.desktop.ui.common.SecurityControls;
import com.financeapp.desktop.ui.security.LockScreen;
import com.financeapp.desktop.ui.security.ProfilePicker;
import com.financeapp.infra.backup.BackupService;
import com.financeapp.infra.security.Argon2Params;
import com.financeapp.infra.security.DatabaseKey;
import com.financeapp.infra.security.VaultService;
import com.financeapp.infra.storage.Profile;
import com.financeapp.infra.storage.ProfileLock;
import com.financeapp.infra.storage.ProfileRegistry;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.input.InputEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Cycle de vie securise de l'application :
 * <ol>
 *   <li>choix de l'utilisateur (profil : dossier, base et mot de passe propres) ;</li>
 *   <li>ecran de mot de passe (aucun acces aux donnees avant) ;</li>
 *   <li>au premier deverrouillage, demarrage du contexte Spring (ouverture et
 *       migration de la base chiffree), puis fenetre principale ;</li>
 *   <li>verrouillage manuel ou apres inactivite : cle effacee de la memoire,
 *       fenetres secondaires fermees, ecran de deverrouillage.</li>
 * </ol>
 */
public final class SecuritySession implements SecurityControls {

    private static final Logger log = LoggerFactory.getLogger(SecuritySession.class);

    private final ProfileRegistry registry;
    private final DatabaseKey key;
    private final Stage stage;
    private final Scene scene;
    private final String appName;
    private final String[] args;
    private final IntegerProperty autoLockMinutes = new SimpleIntegerProperty(5);
    private volatile long lastActivity = System.nanoTime();
    private Profile profile;
    private VaultService vault;
    private ConfigurableApplicationContext context;
    private MainWindow mainWindow;
    private ChangeListener<Number> autoLockSaver;
    private ProfileLock profileLock;

    public SecuritySession(ProfileRegistry registry, DatabaseKey key, Stage stage,
                           Scene scene, String appName, String[] args) {
        this.registry = registry;
        this.key = key;
        this.stage = stage;
        this.scene = scene;
        this.appName = appName;
        this.args = args;
    }

    public void start() {
        trackActivity();
        Timeline idleCheck = new Timeline(new KeyFrame(Duration.seconds(10), e -> lockIfIdle()));
        idleCheck.setCycleCount(Timeline.INDEFINITE);
        idleCheck.play();
        var profiles = registry.list();
        if (profiles.size() == 1) {
            open(profiles.getFirst());
        } else {
            showPicker();
        }
    }

    /** Choix de l'utilisateur ; la base de l'utilisateur precedent est fermee si on en change. */
    private void showPicker() {
        scene.setRoot(new ProfilePicker(registry, appName, this::open).show().root());
        stage.setTitle(appName);
    }

    private void open(Profile chosen) {
        if (profile != null && !profile.id().equals(chosen.id())) {
            closeContext();
            releaseProfileLock();
        }
        profile = chosen;
        try {
            chosen.directories().createAll();
            if (profileLock == null) {
                // Un seul exemplaire de l'application a la fois sur ces donnees.
                var acquired = ProfileLock.tryAcquire(chosen.directories());
                if (acquired.isEmpty()) {
                    profile = null;
                    showAlreadyOpen(chosen);
                    return;
                }
                profileLock = acquired.get();
            }
            BackupService.applyPendingRestore(chosen.directories(), Clock.systemDefaultZone());
        } catch (java.io.IOException | RuntimeException e) {
            log.error("Preparation du profil impossible", e);
            releaseProfileLock();
            LockScreen failed = new LockScreen(null, appName, chosen.name(), this::showPicker, stage, d -> { });
            failed.showMessage("Ouverture impossible", "Les fichiers de cet utilisateur n'ont pas pu être préparés : "
                    + e.getMessage());
            scene.setRoot(failed.root());
            return;
        }
        vault = new VaultService(chosen.directories().keystoreFile(), chosen.directories().databaseFile(),
                chosen.directories().backupsDir(), Argon2Params.DEFAULT);
        showLockScreen();
    }

    private void showAlreadyOpen(Profile chosen) {
        LockScreen busy = new LockScreen(null, appName, chosen.name(), this::showPicker, stage, d -> { });
        busy.showNotice("Déjà ouvert", "Les données de « " + chosen.name() + " » sont déjà ouvertes dans une autre "
                + "fenêtre de " + appName + " (sur cet ordinateur ou une autre session Windows). Fermez-la, puis "
                + "réessayez : deux fenêtres ne peuvent pas modifier les mêmes données en même temps.",
                "Réessayer", () -> open(chosen));
        scene.setRoot(busy.root());
        stage.setTitle(appName);
    }

    private void releaseProfileLock() {
        if (profileLock != null) {
            try {
                profileLock.close();
            } catch (java.io.IOException e) {
                log.warn("Liberation du verrou du profil impossible", e);
            }
            profileLock = null;
        }
    }

    /** Fermeture de l'application : le verrou du profil est libere. */
    public void shutdown() {
        releaseProfileLock();
    }

    public ConfigurableApplicationContext context() {
        return context;
    }

    @Override
    public void lockNow() {
        if (!key.isUnlocked()) {
            return;
        }
        key.lock();
        closeSecondaryWindows();
        showLockScreen();
        log.info("Application verrouillee");
    }

    @Override
    public VaultService vault() {
        return vault;
    }

    @Override
    public IntegerProperty autoLockMinutesProperty() {
        return autoLockMinutes;
    }

    @Override
    public String profileName() {
        return profile == null ? "" : profile.name();
    }

    @Override
    public void renameProfile(String name) {
        profile = registry.rename(profile.id(), name);
        if (key.isUnlocked()) {
            stage.setTitle(title());
        }
    }

    @Override
    public void switchUser() {
        backupBeforeClosing();
        if (key.isUnlocked()) {
            key.lock();
            closeSecondaryWindows();
        }
        showPicker();
    }

    @Override
    public boolean checkPassword(char[] password) throws Exception {
        try {
            byte[] dek = vault.unlock(password);
            Arrays.fill(dek, (byte) 0);
            return true;
        } catch (com.financeapp.infra.security.InvalidSecretException e) {
            return false;
        }
    }

    @Override
    public void deleteCurrentProfile() throws java.io.IOException {
        Profile deleted = profile;
        closeSecondaryWindows();
        closeContext(); // base fermee avant d'effacer ses fichiers ; pas de sauvegarde
        releaseProfileLock();
        profile = null;
        vault = null;
        try {
            registry.delete(deleted.id());
            log.info("Profil supprime");
        } finally {
            showPicker();
        }
    }

    /** Sauvegarde automatique (si activee) tant que la base est encore ouverte. */
    public void backupBeforeClosing() {
        try {
            if (context != null && key.isUnlocked()) {
                SettingsService settings = context.getBean(SettingsService.class);
                BackupService backups = context.getBean(BackupService.class);
                if (settings.autoBackupEnabled() && !backups.hasPendingRestore()) {
                    backups.createAutomaticBackup(settings.autoBackupKeep());
                }
            }
        } catch (Exception e) {
            log.error("La sauvegarde automatique a echoue", e);
        }
    }

    private void closeContext() {
        key.lock();
        if (autoLockSaver != null) {
            autoLockMinutes.removeListener(autoLockSaver);
            autoLockSaver = null;
        }
        if (context != null) {
            context.close();
        }
        context = null;
        mainWindow = null;
    }

    private String title() {
        return registry.list().size() > 1 ? appName + " — " + profile.name() : appName;
    }

    private void showLockScreen() {
        LockScreen lock = new LockScreen(vault, appName, profile.name(), this::showPicker, stage, this::onUnlocked)
                .showForCurrentState();
        scene.setRoot(lock.root());
        stage.setTitle(appName + " — verrouillé");
    }

    private void onUnlocked(byte[] dek) {
        try {
            key.unlock(dek);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
        registry.markUsed(profile.id());
        lastActivity = System.nanoTime();
        if (context != null) {
            showMain();
            return;
        }
        LockScreen opening = new LockScreen(vault, appName, profile.name(), null, stage, d -> { });
        var directories = profile.directories();
        opening.showMessage("Ouverture de vos données…", "Vérification et mise à jour de la base chiffrée.");
        scene.setRoot(opening.root());
        Thread t = new Thread(() -> {
            try {
                ConfigurableApplicationContext ctx = DesktopApplication.start(directories, key, args);
                Platform.runLater(() -> {
                    context = ctx;
                    SettingsService settings = ctx.getBean(SettingsService.class);
                    autoLockMinutes.set(settings.autoLockMinutes());
                    autoLockSaver = (o, old, v) -> settings.setAutoLockMinutes(v.intValue());
                    autoLockMinutes.addListener(autoLockSaver);
                    mainWindow = new MainWindow(AppServices.from(ctx), stage, this);
                    mainWindow.installShortcuts(scene);
                    showMain();
                });
            } catch (Throwable e) {
                log.error("Ouverture de la base impossible", e);
                Platform.runLater(() -> {
                    key.lock();
                    LockScreen failed = new LockScreen(vault, appName, profile.name(), this::showPicker, stage, d -> { });
                    failed.showMessage("Ouverture impossible", "La base n'a pas pu être ouverte. Consultez le fichier "
                            + "de log (" + directories.logsDir() + ") puis relancez l'application.");
                    scene.setRoot(failed.root());
                });
            }
        }, "financeapp-startup");
        t.setDaemon(true);
        t.start();
    }

    private void showMain() {
        scene.setRoot(mainWindow.root());
        stage.setTitle(title());
        mainWindow.refreshCurrent();
    }

    private void lockIfIdle() {
        int minutes = autoLockMinutes.get();
        if (minutes <= 0 || !key.isUnlocked()) {
            return;
        }
        long idleSeconds = (System.nanoTime() - lastActivity) / 1_000_000_000L;
        if (idleSeconds >= minutes * 60L) {
            log.info("Verrouillage automatique apres {} min d'inactivite", minutes);
            lockNow();
        }
    }

    /**
     * Toute interaction, dans la fenetre principale ou un dialogue, repousse le
     * verrouillage ; Ctrl+L verrouille depuis n'importe quelle fenetre (un
     * raccourci de scene n'est pas recu quand un dialogue a le focus).
     */
    private void trackActivity() {
        KeyCombination lockKey = new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN);
        EventHandler<InputEvent> touch = e -> {
            lastActivity = System.nanoTime();
            if (e instanceof KeyEvent k && k.getEventType() == KeyEvent.KEY_PRESSED && lockKey.match(k)
                    && key.isUnlocked()) {
                k.consume();
                Platform.runLater(this::lockNow);
            }
        };
        ListChangeListener<Window> onWindows = change -> {
            while (change.next()) {
                for (Window w : change.getAddedSubList()) {
                    w.addEventFilter(KeyEvent.ANY, touch::handle);
                    w.addEventFilter(MouseEvent.MOUSE_PRESSED, touch::handle);
                    w.addEventFilter(MouseEvent.MOUSE_MOVED, touch::handle);
                    w.addEventFilter(ScrollEvent.ANY, touch::handle);
                }
            }
        };
        Window.getWindows().addListener(onWindows);
        for (Window w : Window.getWindows()) {
            w.addEventFilter(KeyEvent.ANY, touch::handle);
            w.addEventFilter(MouseEvent.MOUSE_PRESSED, touch::handle);
            w.addEventFilter(MouseEvent.MOUSE_MOVED, touch::handle);
            w.addEventFilter(ScrollEvent.ANY, touch::handle);
        }
    }

    /** Dialogues et formulaires ouverts : fermes, pour ne rien laisser visible derriere le verrou. */
    private void closeSecondaryWindows() {
        for (Window w : new ArrayList<>(Window.getWindows())) {
            if (w != stage) {
                w.hide();
            }
        }
    }
}
