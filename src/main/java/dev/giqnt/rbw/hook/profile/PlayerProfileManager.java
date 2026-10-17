package dev.giqnt.rbw.hook.profile;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.giqnt.rbw.hook.HookPlugin;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class PlayerProfileManager implements Listener {
    private final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();
    private final HookPlugin plugin;

    public void init() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            bulkUpdateProfiles();
            for (final var it = profiles.keySet().iterator(); it.hasNext(); ) {
                final var uuid = it.next();
                final var player = Bukkit.getPlayer(uuid);
                if (player == null || !player.isOnline()) {
                    it.remove();
                }
            }
        }, 20 * 60, 20 * 60);
    }

    @Nullable
    private PlayerProfile fetchProfile(final UUID uuid) {
        try {
            final var result = plugin.getApi().request("/users?uuid=" + uuid, "GET", null);
            final JsonArray data = items(result);
            if (data.isEmpty()) return null;
            return parseJson(data.get(0).getAsJsonObject());
        } catch (IOException e) {
            // The bulk refresh fills it in within a minute.
            plugin.getLogger().warning("Failed to fetch profile of " + uuid + ": " + e.getMessage());
            return null;
        }
    }

    private void bulkUpdateProfiles() {
        final ImmutableList<Player> players = ImmutableList.copyOf(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            this.profiles.clear();
            return;
        }
        try {
            final Map<UUID, PlayerProfile> playerProfileMap = new HashMap<>();
            // the api takes at most 100 uuids per request
            for (final List<Player> chunk : Lists.partition(players, 100)) {
                final String queryString = chunk.stream()
                        .map(player -> "uuid=" + player.getUniqueId())
                        .collect(Collectors.joining("&"));
                final var result = plugin.getApi().request("/users?limit=100&" + queryString, "GET", null);
                for (final var data : items(result)) {
                    final JsonObject user = data.getAsJsonObject();
                    playerProfileMap.put(UUID.fromString(user.get("uuid").getAsString()), parseJson(user));
                }
            }
            for (final Player player : players) {
                final UUID uuid = player.getUniqueId();
                final PlayerProfile profile = playerProfileMap.get(uuid);
                if (profile == null) {
                    this.profiles.remove(uuid);
                } else {
                    this.profiles.put(uuid, profile);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to update profiles: " + e.getMessage());
        }
    }

    private static JsonArray items(final String body) {
        return JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("items");
    }

    private PlayerProfile parseJson(final JsonObject data) {
        final JsonObject stats = data.getAsJsonObject("stats");
        final Map<String, Number> statsMap = new HashMap<>();
        for (final var entry : stats.entrySet()) {
            try {
                statsMap.put(entry.getKey(), entry.getValue().getAsNumber());
            } catch (final UnsupportedOperationException | NumberFormatException | IllegalStateException ignored) {
            }
        }
        return new PlayerProfile(Map.copyOf(statsMap), data.get("position").getAsInt());
    }

    public Optional<PlayerProfile> getProfile(final Player player) {
        return getProfile(player.getUniqueId());
    }

    public Optional<PlayerProfile> getProfile(final UUID playerId) {
        return Optional.ofNullable(profiles.get(playerId));
    }

    @EventHandler
    public void onJoinAsync(final AsyncPlayerPreLoginEvent e) {
        final var uuid = e.getUniqueId();
        profiles.remove(uuid);
        final PlayerProfile profile = fetchProfile(uuid);
        if (profile != null) {
            profiles.put(uuid, profile);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent e) {
        profiles.remove(e.getPlayer().getUniqueId());
    }
}
