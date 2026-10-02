package com.financeapp.core.service;

import com.financeapp.core.port.ReleaseFeed;
import com.financeapp.core.testing.TestApp;
import com.financeapp.core.update.AvailableRelease;
import com.financeapp.core.update.ReleaseVersion;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class UpdateServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private final TestApp app = new TestApp(TODAY);
    private final AtomicInteger calls = new AtomicInteger();

    private UpdateService service(String current, String latest) {
        ReleaseFeed feed = () -> {
            calls.incrementAndGet();
            return ReleaseVersion.parse(latest).map(v -> new AvailableRelease(v,
                    "https://github.com/SkyWace/Finance-APP/releases/tag/" + latest, TODAY));
        };
        Clock clock = Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        return new UpdateService(feed, app.settings, current, clock);
    }

    @Test
    void versionsCompareNumericallyAndDevelopmentPrecedesTheRelease() {
        ReleaseVersion v023 = ReleaseVersion.parse("v0.2.3").orElseThrow();
        assertTrue(ReleaseVersion.parse("0.2.10").orElseThrow().isNewerThan(v023), "10 > 3, pas un tri de texte");
        assertTrue(ReleaseVersion.parse("1.0.0").orElseThrow().isNewerThan(ReleaseVersion.parse("0.99.99").orElseThrow()));
        assertTrue(v023.isNewerThan(ReleaseVersion.parse("0.2.3-SNAPSHOT").orElseThrow()));
        assertFalse(v023.isNewerThan(ReleaseVersion.parse("0.2.3").orElseThrow()));
        assertTrue(ReleaseVersion.parse("dev").isEmpty());
        assertTrue(ReleaseVersion.parse("v0.2.3; rm -rf /").isEmpty());
    }

    @Test
    void nothingIsCheckedAutomaticallyUnlessEnabled() throws Exception {
        UpdateService s = service("0.2.3", "v0.2.4");
        assertFalse(s.autoCheckEnabled(), "desactive par defaut");
        assertTrue(s.checkIfDue().isEmpty());
        assertEquals(0, calls.get(), "aucune connexion");

        assertEquals("0.2.4", s.checkNow().orElseThrow().version().toString(), "verification manuelle toujours possible");
        assertEquals(1, calls.get());
    }

    @Test
    void automaticCheckRunsAtMostOncePerDayAndRespectsLater() throws Exception {
        UpdateService s = service("0.2.3", "v0.2.4");
        s.setAutoCheckEnabled(true);
        AvailableRelease found = s.checkIfDue().orElseThrow();
        assertTrue(s.checkIfDue().isEmpty(), "deja verifie aujourd'hui");
        assertEquals(1, calls.get());

        app.settings.setLastUpdateCheck(TODAY.minusDays(1));
        s.ignore(found);
        assertTrue(s.checkIfDue().isEmpty(), "« Plus tard » : cette version n'est plus rappelee");
        assertTrue(s.checkNow().isPresent(), "mais reste visible en verification manuelle");
    }

    @Test
    void noUpdateWhenUpToDateOrUnknown() throws Exception {
        assertTrue(service("0.2.4", "v0.2.4").checkNow().isEmpty());
        assertTrue(service("0.2.5-SNAPSHOT", "v0.2.4").checkNow().isEmpty());
        assertTrue(service("dev", "v0.2.4").checkNow().isEmpty(), "version locale inconnue");
        assertEquals(Optional.empty(), service("0.2.3", "pas-une-version").checkNow());
    }
}
