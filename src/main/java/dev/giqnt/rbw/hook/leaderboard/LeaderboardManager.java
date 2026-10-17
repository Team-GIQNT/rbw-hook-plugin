package dev.giqnt.rbw.hook.leaderboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import dev.giqnt.rbw.hook.HookPlugin;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

@RequiredArgsConstructor
public class LeaderboardManager {
    private final Map<LeaderboardCategory, Leaderboard> leaderboards = new ConcurrentHashMap<>();
    private final HookPlugin plugin;

    public void init() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::fetchAll, 0, 20 * 60);
    }

    private void fetchAll() {
        for (final LeaderboardCategory category : LeaderboardCategory.values()) {
            try {
                leaderboards.put(category, fetch(category));
            } catch (final IOException e) {
                plugin.getLogger().warning("Failed to fetch " + category + " leaderboard: " + e.getMessage());
            } catch (final RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to parse " + category + " leaderboard", e);
            }
        }
    }

    private Leaderboard fetch(final LeaderboardCategory category) throws IOException {
        final String response = plugin.getApi().request(String.format("/leaderboard?limit=10&sortBy=%s", category.name().toLowerCase()), "GET", null);
        final JsonArray data = JsonParser.parseString(response).getAsJsonObject().getAsJsonArray("items");
        final List<LeaderboardEntry> entries = new ArrayList<>();
        data.forEach(entry -> {
            final var obj = entry.getAsJsonObject();
            final var nameElem = obj.get("name");
            entries.add(new LeaderboardEntry(nameElem.isJsonNull() ? null : nameElem.getAsString(), obj.get("value").getAsNumber()));
        });
        return new Leaderboard(List.copyOf(entries));
    }

    public Optional<LeaderboardEntry> getEntry(final LeaderboardCategory category, final int index) {
        final Leaderboard leaderboard = leaderboards.get(category);
        if (leaderboard == null || index >= leaderboard.entries().size()) return Optional.empty();
        return Optional.of(leaderboard.entries().get(index));
    }
}
