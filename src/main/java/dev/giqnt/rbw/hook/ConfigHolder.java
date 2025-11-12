package dev.giqnt.rbw.hook;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jspecify.annotations.NonNull;

import java.util.Objects;
import java.util.TreeMap;

public record ConfigHolder(
        @NonNull String rbwName,
        @NonNull String token,
        @NonNull String groupPrefix,
        @NonNull TreeMap<Integer, Character> rankColors
) {
    public static ConfigHolder load(final FileConfiguration config) {
        return new ConfigHolder(
                Objects.requireNonNull(config.getString("rbw-name")),
                Objects.requireNonNull(config.getString("token")),
                Objects.requireNonNull(config.getString("group-prefix")),
                loadRankColor(config.getConfigurationSection("rank-color"))
        );
    }

    private static TreeMap<Integer, Character> loadRankColor(final ConfigurationSection configSection) {
        final TreeMap<Integer, Character> rankColors = new TreeMap<>();
        if (configSection == null) return rankColors;
        for (final String key : configSection.getKeys(false)) {
            try {
                final int elo = Integer.parseInt(key);
                final String color = configSection.getString(key);
                if (color != null && color.length() == 1) {
                    rankColors.put(elo, color.charAt(0));
                }
            } catch (final NumberFormatException ignored) {
            }
        }
        return rankColors;
    }
}
