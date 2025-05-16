package dev.giqnt.rbw.hook.placeholder;

import dev.giqnt.rbw.hook.HookPlugin;
import dev.giqnt.rbw.hook.leaderboard.LeaderboardCategory;
import lombok.RequiredArgsConstructor;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

@RequiredArgsConstructor
public class RBWPlaceholderExpansion extends PlaceholderExpansion {
    private final HookPlugin plugin;

    @Override
    public @NonNull String getIdentifier() {
        return "rbw";
    }

    @Override
    public @NonNull String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public @NonNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onPlaceholderRequest(@Nullable Player player, @NonNull String params) {
        final var parts = params.split("_");
        if (parts.length < 1) return null;
        try {
            switch (parts[0]) {
                case "leaderboard": {
                    // leaderboard_<category>_<rank>_(name/value)
                    if (parts.length != 4) return null;
                    final LeaderboardCategory category = LeaderboardCategory.valueOf(parts[1].toUpperCase());
                    final int rank = Integer.parseInt(parts[2]);
                    if (rank < 1) return null;
                    final String field = parts[3];
                    if (!field.equals("name") && !field.equals("value")) return null;
                    final var entry = plugin.getLeaderboardManager().getEntry(category, rank - 1);
                    if (field.equals("name")) {
                        return Objects.requireNonNullElse(entry.name(), "???");
                    } else {
                        return String.valueOf(entry.value());
                    }
                }
                case "stats": {
                    // stats_<type>
                    if (player == null) return null;
                    if (parts.length != 2) return null;
                    final var type = parts[1];
                    return plugin.getPlayerProfileManager().getProfile(player)
                            .map(profile -> profile.stats().get(type))
                            .map(String::valueOf)
                            .orElse("???");
                }
            }
        } catch (final IllegalArgumentException ignored) {
        }
        return null;
    }
}
