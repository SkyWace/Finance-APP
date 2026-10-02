package com.financeapp.core.update;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Numero de version "majeur.mineur.correctif" ("v0.2.3", "0.2.3", "0.2.4-SNAPSHOT").
 * Une version de developpement (suffixe) precede la version publiee du meme numero.
 */
public record ReleaseVersion(int major, int minor, int patch, boolean preRelease) implements Comparable<ReleaseVersion> {

    private static final Pattern FORMAT = Pattern.compile("^v?(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,5})(-[0-9A-Za-z.-]+)?$");

    public static Optional<ReleaseVersion> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(text.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new ReleaseVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), m.group(4) != null));
    }

    @Override
    public int compareTo(ReleaseVersion o) {
        int c = Integer.compare(major, o.major);
        if (c == 0) {
            c = Integer.compare(minor, o.minor);
        }
        if (c == 0) {
            c = Integer.compare(patch, o.patch);
        }
        if (c == 0) {
            c = Boolean.compare(o.preRelease, preRelease); // 0.2.4-SNAPSHOT < 0.2.4
        }
        return c;
    }

    public boolean isNewerThan(ReleaseVersion other) {
        return compareTo(other) > 0;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (preRelease ? " (développement)" : "");
    }
}
