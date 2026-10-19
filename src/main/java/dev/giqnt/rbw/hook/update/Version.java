package dev.giqnt.rbw.hook.update;

import org.jspecify.annotations.NonNull;

import java.util.Optional;
import java.util.regex.Pattern;

/** A release tag. Builds off a tag (`2.1.0-3-gabc1234`, `-dirty`, a bare sha) are not releases. */
record Version(int major, int minor, int patch) implements Comparable<Version> {
    private static final Pattern RELEASE = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)$");

    static Optional<Version> parse(@NonNull final String value) {
        final var matcher = RELEASE.matcher(value);
        if (!matcher.matches()) return Optional.empty();
        try {
            return Optional.of(new Version(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))
            ));
        } catch (final NumberFormatException e) {
            return Optional.empty();
        }
    }

    @Override
    public int compareTo(@NonNull final Version other) {
        if (major != other.major) return Integer.compare(major, other.major);
        if (minor != other.minor) return Integer.compare(minor, other.minor);
        return Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
