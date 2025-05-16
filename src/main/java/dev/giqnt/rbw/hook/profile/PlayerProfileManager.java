package dev.giqnt.rbw.hook.profile;

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

@RequiredArgsConstructor
public class PlayerProfileManager implements Listener {
    private final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();
    private final HookPlugin plugin;

    public void init() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            for (final var it = profiles.keySet().iterator(); it.hasNext(); ) {
                final var uuid = it.next();
                final var player = Bukkit.getPlayer(uuid);
                if (player == null || !player.isOnline()) {
                    it.remove();
                }
            }
            for (final var player : Bukkit.getOnlinePlayers()) {
                final var profile = fetchProfile(player.getName());
                if (profile != null) {
                    profiles.put(player.getUniqueId(), profile);
                } else {
                    profiles.remove(player.getUniqueId());
                }
            }
        }, 20 * 60, 20 * 60);
    }

    @Nullable
    private PlayerProfile fetchProfile(final String name) {
        try {
            final var response = plugin.getApi().request("/player/" + name, "GET", null);
            final var statusCode = response.statusCode();
            if (statusCode == 404) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new RuntimeException(String.format("Failed to fetch profile data for player %s: (%d) %s", name, response.statusCode(), response.body()));
            }
            final JsonObject data = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!data.get("success").getAsBoolean()) {
                throw new RuntimeException(String.format("Failed to fetch profile data for player %s: %s", name, data.get("message").getAsString()));
            }
            final JsonObject stats = data.getAsJsonObject("stats");
            final Map<String, Integer> statsMap = new HashMap<>();
            for (final var key : stats.keySet()) {
                try {
                    statsMap.put(key, stats.get(key).getAsInt());
                } catch (final UnsupportedOperationException | NumberFormatException | IllegalStateException ignored) {
                }
            }
            return new PlayerProfile(Map.copyOf(statsMap), Map.of());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
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
        final var name = e.getName();
        profiles.remove(uuid);
        final PlayerProfile profile = fetchProfile(name);
        if (profile != null) {
            profiles.put(uuid, profile);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent e) {
        profiles.remove(e.getPlayer().getUniqueId());
    }
}
