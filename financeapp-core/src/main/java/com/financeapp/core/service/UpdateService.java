package com.financeapp.core.service;

import com.financeapp.core.port.ReleaseFeed;
import com.financeapp.core.settings.SettingsService;
import com.financeapp.core.update.AvailableRelease;
import com.financeapp.core.update.ReleaseVersion;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Signale qu'une nouvelle version est publiee. Desactive par defaut : aucune
 * connexion tant que l'utilisateur ne l'a pas active ou ne lance pas la
 * verification lui-meme. La requete ne transmet aucune donnee de l'utilisateur ;
 * rien n'est telecharge ni installe automatiquement.
 */
public final class UpdateService {

    private final ReleaseFeed feed;
    private final SettingsService settings;
    private final String currentVersion;
    private final Clock clock;

    public UpdateService(ReleaseFeed feed, SettingsService settings, String currentVersion, Clock clock) {
        this.feed = feed;
        this.settings = settings;
        this.currentVersion = currentVersion;
        this.clock = clock;
    }

    public String currentVersion() {
        return currentVersion;
    }

    public boolean autoCheckEnabled() {
        return settings.updateCheckEnabled();
    }

    public void setAutoCheckEnabled(boolean enabled) {
        settings.setUpdateCheckEnabled(enabled);
    }

    /** Verification demandee par l'utilisateur : toujours effectuee. */
    public Optional<AvailableRelease> checkNow() throws IOException {
        settings.setLastUpdateCheck(LocalDate.now(clock));
        Optional<AvailableRelease> latest = feed.latest();
        Optional<ReleaseVersion> current = ReleaseVersion.parse(currentVersion);
        if (latest.isEmpty() || current.isEmpty()) {
            return Optional.empty(); // version locale inconnue ("dev") : rien a comparer
        }
        return latest.filter(r -> r.version().isNewerThan(current.get()));
    }

    /**
     * Verification automatique a l'ouverture : seulement si l'utilisateur l'a activee,
     * au plus une fois par jour, et sans rappeler une version qu'il a deja ecartee.
     */
    public Optional<AvailableRelease> checkIfDue() throws IOException {
        if (!autoCheckEnabled()) {
            return Optional.empty();
        }
        LocalDate today = LocalDate.now(clock);
        if (today.equals(settings.lastUpdateCheck().orElse(null))) {
            return Optional.empty();
        }
        String ignored = settings.ignoredUpdateVersion().orElse("");
        return checkNow().filter(r -> !r.version().toString().equals(ignored));
    }

    /** "Plus tard" : cette version n'est plus signalee automatiquement. */
    public void ignore(AvailableRelease release) {
        settings.setIgnoredUpdateVersion(release.version().toString());
    }
}
