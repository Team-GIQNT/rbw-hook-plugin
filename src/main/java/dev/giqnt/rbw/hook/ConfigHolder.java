package dev.giqnt.rbw.hook;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.NonNull;

import java.util.TreeMap;

public record ConfigHolder(
        @NonNull String rbwName,
        @NonNull String token,
        @NonNull String groupPrefix,
        @NonNull ProxyConfigHolder proxy,
        @NonNull TreeMap<Integer, Character> rankColors
) {
    public static ConfigHolder load(final FileConfiguration config) {
        return new ConfigHolder(
                config.getString("rbw-name", ""),
                config.getString("token", ""),
                config.getString("group-prefix", ""),
                ProxyConfigHolder.load(config.getConfigurationSection("proxy")),
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

    public YamlConfiguration toYaml() {
        final YamlConfiguration config = new YamlConfiguration();
        config.set("rbw-name", rbwName);
        config.set("token", token);
        config.set("group-prefix", groupPrefix);
        proxy.save(config.createSection("proxy"));
        config.set("rank-color", rankColors);
        return config;
    }

    public record ProxyConfigHolder(
            boolean enabled,
            @NonNull String username,
            @NonNull String password,
            @NonNull String ip,
            int port
    ) {
        private static ProxyConfigHolder load(final ConfigurationSection section) {
            if (section == null) {
                return new ProxyConfigHolder(false, "", "", "127.0.0.1", 8585);
            }
            return new ProxyConfigHolder(
                    section.getBoolean("enabled", false),
                    section.getString("username", ""),
                    section.getString("password", ""),
                    section.getString("ip", "127.0.0.1"),
                    section.getInt("port", 8585)
            );
        }

        private void save(final ConfigurationSection section) {
            section.set("enabled", enabled);
            section.set("username", username);
            section.set("password", password);
            section.set("ip", ip);
            section.set("port", port);
        }
    }
}
