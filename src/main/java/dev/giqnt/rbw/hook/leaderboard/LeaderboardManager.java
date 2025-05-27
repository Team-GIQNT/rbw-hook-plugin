package dev.giqnt.rbw.hook.leaderboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.giqnt.rbw.hook.HookPlugin;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

@RequiredArgsConstructor
public class LeaderboardManager {
    private final Map<LeaderboardCategory, Leaderboard> leaderboards = new EnumMap<>(LeaderboardCategory.class);
    private final HookPlugin plugin;

    public void init() {
        fetchAll();
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::fetchAll, 0, 20 * 60);
    }

    private void fetchAll() {
        try {
            for (final LeaderboardCategory category : LeaderboardCategory.values()) {
                final String response = plugin.getApi().request(String.format("/leaderboard?limit=10&sortBy=%s", category.name().toLowerCase()), "GET", null);
                final JsonObject body = JsonParser.parseString(response).getAsJsonObject();
                if (!body.get("success").getAsBoolean()) {
                    throw new RuntimeException(String.format("Failed to fetch leaderboard data for category %s: %s", category, body.get("message").getAsString()));
                }
                final JsonArray data = body.getAsJsonArray("data");
                final List<LeaderboardEntry> entries = new ArrayList<>();
                data.forEach(entry -> {
                    final var obj = entry.getAsJsonObject();
                    final var nameElem = obj.get("name");
                    entries.add(new LeaderboardEntry(nameElem.isJsonNull() ? null : nameElem.getAsString(), obj.get("value").getAsInt()));
                });
                leaderboards.put(category, new Leaderboard(List.copyOf(entries)));
            }
        } catch (final IOException | RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to fetch leaderboard data", e);
        }
    }

    public LeaderboardEntry getEntry(final LeaderboardCategory category, final int index) {
        return leaderboards.get(category).entries().get(index);
    }
}
