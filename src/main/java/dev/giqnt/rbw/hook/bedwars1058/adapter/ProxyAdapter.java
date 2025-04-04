package dev.giqnt.rbw.hook.bedwars1058.adapter;

import com.andrei1058.bedwars.proxy.BedWarsProxy;
import com.andrei1058.bedwars.proxy.api.ArenaStatus;
import com.andrei1058.bedwars.proxy.api.CachedArena;
import com.andrei1058.bedwars.proxy.api.event.ArenaCacheRemoveEvent;
import com.andrei1058.bedwars.proxy.arenamanager.ArenaManager;
import com.andrei1058.bedwars.proxy.language.LanguageManager;
import dev.giqnt.rbw.hook.bedwars1058.GameCreateException;
import dev.giqnt.rbw.hook.bedwars1058.HookPlugin;
import dev.giqnt.rbw.hook.bedwars1058.MapInfo;
import dev.giqnt.rbw.hook.bedwars1058.RankedGame;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.*;
import java.util.concurrent.*;

public class ProxyAdapter implements Adapter, Listener {
    private final HookPlugin plugin;
    private final ConcurrentMap<ArenaIdentifier, RankedGame> arenaToGame = new ConcurrentHashMap<>();

    public ProxyAdapter(final HookPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Collection<MapInfo> getMaps() {
        final Set<MapInfo> maps = new HashSet<>();
        final String groupPrefix = plugin.configHolder.groupPrefix();
        for (final var arena : ArenaManager.getArenas()) {
            final String group = arena.getArenaGroup();
            if (!group.startsWith(groupPrefix)) continue;
            final String category = group.substring(groupPrefix.length());
            final String mapName = arena.getDisplayName(LanguageManager.get().getDefaultLanguage());
            maps.add(new MapInfo(category, mapName, null));
        }
        return Collections.unmodifiableSet(maps);
    }

    @Override
    public boolean isReady(Player player) {
        return player.isOnline();
    }

    @Override
    public CompletableFuture<Void> createGame(RankedGame game) {
        if (arenaToGame.values().stream().anyMatch(g -> g.id() == game.id())) {
            return CompletableFuture.failedFuture(new GameCreateException("Game already created"));
        }
        final List<List<Player>> teams = game.teams();
        final String mapName = game.mapName();

        final var party = BedWarsProxy.getParty();
        for (final var player : game.players()) {
            party.removeFromParty(player.getUniqueId());
        }
        for (final var team : teams) {
            if (team.isEmpty()) continue;
            party.createParty(team.getFirst(), team.subList(1, team.size()).toArray(new Player[0]));
        }

        final var defaultLang = LanguageManager.get().getDefaultLanguage();
        final int teamCount = teams.size();
        final int teamSize = teams.stream().mapToInt(List::size).max().orElse(0);
        final var availableArenas = ArenaManager.getArenas().stream()
                .filter(a -> a.getDisplayName(defaultLang).equals(mapName)
                        && a.getStatus() == ArenaStatus.WAITING
                        && a.getCurrentPlayers() == 0
                        && a.getMaxPlayers() >= teamCount * teamSize
                        && a.getMaxInTeam() >= teamSize)
                .toList();
        if (availableArenas.isEmpty()) {
            return CompletableFuture.failedFuture(new GameCreateException("No available arenas found for map " + mapName));
        }
        final var selectedArena = availableArenas.get(ThreadLocalRandom.current().nextInt(availableArenas.size()));
        final var arenaIdentifier = ArenaIdentifier.create(selectedArena);
        arenaToGame.put(arenaIdentifier, game);

        return CompletableFuture.runAsync(() -> {
            try {
                Bukkit.getScheduler().callSyncMethod(plugin, (Callable<Void>) () -> {
                    for (final var team : teams) {
                        if (team.isEmpty()) continue;
                        selectedArena.addPlayer(team.getFirst(), null);
                    }
                    return null;
                }).get();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }).exceptionally((throwable) -> {
            plugin.getLogger().severe("Failed to add players to arena: " + throwable.getMessage());
            throw new GameCreateException("Failed to add players to arena", throwable);
        });
    }

    @EventHandler
    public void onArenaRemove(final ArenaCacheRemoveEvent e) {
        this.arenaToGame.remove(ArenaIdentifier.create(e.getArena()));
    }

    // TODO: figure out a way to get game end data
}

record ArenaIdentifier(String server, String name) {
    public static ArenaIdentifier create(final CachedArena arena) {
        return new ArenaIdentifier(arena.getServer(), arena.getArenaName());
    }
}
