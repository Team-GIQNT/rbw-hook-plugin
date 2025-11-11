package dev.giqnt.rbw.hook.profile;

import com.google.common.collect.ImmutableList;
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
            final var result = plugin.getApi().request(1, "/users/?uuid=" + uuid, "GET", null, response -> {
                if (response.code() == 404) {
                    return null;
                }
                if (!response.isSuccessful()) {
                    throw new IOException(String.format("Failed to fetch profile data for player %s: (%d) %s", uuid, response.code(), response.body()));
                }
                final var responseBody = response.body();
                return responseBody == null ? "" : responseBody.string();
            });
            if (result == null) {
                return null;
            }
            final JsonArray data = JsonParser.parseString(result).getAsJsonArray();
            if (data.isEmpty()) return null;
            return parseJson(data.get(0).getAsJsonObject());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void bulkUpdateProfiles() {
        final ImmutableList<Player> players = ImmutableList.copyOf(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            this.profiles.clear();
            return;
        }
        try {
            final String queryString = players.stream()
                    .map(player -> "uuid=" + player.getUniqueId())
                    .collect(Collectors.joining("&"));
            final var result = plugin.getApi().request(1, "/users/?" + queryString, "GET", null);
            final JsonArray playersData = JsonParser.parseString(result).getAsJsonArray();
            final Map<UUID, PlayerProfile> playerProfileMap = playersData.asList().stream().collect(Collectors.toUnmodifiableMap(
                    data -> UUID.fromString(data.getAsJsonObject().get("uuid").getAsString()),
                    data -> parseJson(data.getAsJsonObject())
            ));
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
            throw new RuntimeException(e);
        }
    }

    private PlayerProfile parseJson(final JsonObject data) {
        final JsonObject stats = data.getAsJsonObject("stats");
        final Map<String, Integer> statsMap = new HashMap<>();
        for (final var entry : stats.entrySet()) {
            try {
                statsMap.put(entry.getKey(), entry.getValue().getAsInt());
            } catch (final UnsupportedOperationException | NumberFormatException | IllegalStateException ignored) {
            }
        }
        return new PlayerProfile(Map.copyOf(statsMap), Map.of());
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
