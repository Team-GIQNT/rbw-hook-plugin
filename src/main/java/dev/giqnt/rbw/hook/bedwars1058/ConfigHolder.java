package dev.giqnt.rbw.hook.bedwars1058;

import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public record ConfigHolder(
        @NotNull String rbwName,
        @NotNull String token,
        @NotNull String groupPrefix
) {
    public static ConfigHolder load(final FileConfiguration config) {
        return new ConfigHolder(
                Objects.requireNonNull(config.getString("rbw-name")),
                Objects.requireNonNull(config.getString("token")),
                Objects.requireNonNull(config.getString("group-prefix"))
        );
    }
}
