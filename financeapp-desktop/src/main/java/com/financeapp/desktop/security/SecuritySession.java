package com.financeapp.desktop.security;

import com.financeapp.core.settings.SettingsService;
import com.financeapp.desktop.DesktopApplication;
import com.financeapp.desktop.ui.MainWindow;
import com.financeapp.desktop.ui.common.AppServices;
import com.financeapp.desktop.ui.common.SecurityControls;
import com.financeapp.desktop.ui.security.LockScreen;
import com.financeapp.infra.security.DatabaseKey;
import com.financeapp.infra.security.VaultService;
import com.financeapp.infra.storage.AppDirectories;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
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

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Cycle de vie securise de l'application :
 * <ol>
 *   <li>ecran de mot de passe (aucun acces aux donnees avant) ;</li>
 *   <li>au premier deverrouillage, demarrage du contexte Spring (ouverture et
 *       migration de la base chiffree), puis fenetre principale ;</li>
 *   <li>verrouillage manuel ou apres inactivite : cle effacee de la memoire,
 *       fenetres secondaires fermees, ecran de deverrouillage.</li>
 * </ol>
 */
public final class SecuritySession implements SecurityControls {

    private static final Logger log = LoggerFactory.getLogger(SecuritySession.class);

    private final AppDirectories directories;
    private final VaultService vault;
    private final DatabaseKey key;
    private final Stage stage;
    private final Scene scene;
    private final String appName;
    private final String[] args;
    private final IntegerProperty autoLockMinutes = new SimpleIntegerProperty(5);
    private volatile long lastActivity = System.nanoTime();
    private ConfigurableApplicationContext context;
    private MainWindow mainWindow;

    public SecuritySession(AppDirectories directories, VaultService vault, DatabaseKey key, Stage stage,
                           Scene scene, String appName, String[] args) {
        this.directories = directories;
        this.vault = vault;
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
        showLockScreen();
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

    private void showLockScreen() {
        LockScreen lock = new LockScreen(vault, appName, stage, this::onUnlocked).showForCurrentState();
        scene.setRoot(lock.root());
        stage.setTitle(appName + " — verrouillé");
    }

    private void onUnlocked(byte[] dek) {
        try {
            key.unlock(dek);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
        lastActivity = System.nanoTime();
        if (context != null) {
            showMain();
            return;
        }
        LockScreen opening = new LockScreen(vault, appName, stage, d -> { });
        opening.showMessage("Ouverture de vos données…", "Vérification et mise à jour de la base chiffrée.");
        scene.setRoot(opening.root());
        Thread t = new Thread(() -> {
            try {
                ConfigurableApplicationContext ctx = DesktopApplication.start(directories, key, args);
                Platform.runLater(() -> {
                    context = ctx;
                    SettingsService settings = ctx.getBean(SettingsService.class);
                    autoLockMinutes.set(settings.autoLockMinutes());
                    autoLockMinutes.addListener((o, old, v) -> settings.setAutoLockMinutes(v.intValue()));
                    mainWindow = new MainWindow(AppServices.from(ctx), stage, this);
                    mainWindow.installShortcuts(scene);
                    showMain();
                });
            } catch (Throwable e) {
                log.error("Ouverture de la base impossible", e);
                Platform.runLater(() -> {
                    key.lock();
                    LockScreen failed = new LockScreen(vault, appName, stage, d -> { });
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
        stage.setTitle(appName);
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
