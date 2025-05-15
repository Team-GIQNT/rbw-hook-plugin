package dev.giqnt.rbw.hook;

import org.bukkit.configuration.file.FileConfiguration;
import org.jspecify.annotations.NonNull;

import java.util.Objects;

public record ConfigHolder(
        @NonNull String rbwName,
        @NonNull String token,
        @NonNull String groupPrefix
) {
    public static ConfigHolder load(final FileConfiguration config) {
        return new ConfigHolder(
                Objects.requireNonNull(config.getString("rbw-name")),
                Objects.requireNonNull(config.getString("token")),
                Objects.requireNonNull(config.getString("group-prefix"))
        );
    }
}
