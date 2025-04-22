package dev.giqnt.rbw.hook.adapter;

import com.andrei1058.bedwars.api.arena.GameState;
import com.andrei1058.bedwars.api.arena.IArena;
import com.andrei1058.bedwars.api.arena.stats.DefaultStatistics;
import com.andrei1058.bedwars.api.configuration.ConfigPath;
import com.andrei1058.bedwars.api.events.gameplay.GameEndEvent;
import com.andrei1058.bedwars.api.events.gameplay.GameStateChangeEvent;
import com.andrei1058.bedwars.api.events.player.PlayerJoinArenaEvent;
import com.andrei1058.bedwars.api.events.player.PlayerLeaveArenaEvent;
import com.andrei1058.bedwars.arena.Arena;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.giqnt.rbw.hook.HookPlugin;
import dev.giqnt.rbw.hook.game.GameCreateException;
import dev.giqnt.rbw.hook.game.RankedGame;
import lombok.SneakyThrows;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

public class BedWars1058Adapter implements Adapter, Listener {

    private final HookPlugin plugin;
    private final ConcurrentMap<String, RankedGame> arenaToGame = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<Void>> arenaStartFutures = new ConcurrentHashMap<>();

    public BedWars1058Adapter(@Nonnull final HookPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Collection<MapInfo> getMaps() {
        final Set<MapInfo> maps = new HashSet<>();
        final String groupPrefix = plugin.configHolder.groupPrefix();
        for (final var arena : Arena.getArenas()) {
            final String group = arena.getGroup();
            if (!group.startsWith(groupPrefix)) continue;
            final String category = group.substring(groupPrefix.length());
            final String mapName = arena.getDisplayName();
            final int maxBuildY = arena.getConfig().getInt(ConfigPath.ARENA_CONFIGURATION_MAX_BUILD_Y);
            maps.add(new MapInfo(category, mapName, maxBuildY));
        }
        return Collections.unmodifiableSet(maps);
    }

    @Override
    public boolean isReady(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        final var arena = Arena.getArenaByPlayer(player);
        return arena == null || !arena.isPlayer(player);
    }

    @SneakyThrows
    @Override
    public void createGame(@Nonnull final RankedGame game) {
        if (arenaToGame.values().stream().anyMatch(g -> g.id() == game.id())) {
            throw new GameCreateException("Game already created");
        }
        final List<List<Player>> teams = game.teams();
        final String mapName = game.mapName();
        final int teamCount = teams.size();
        final int teamSize = teams.stream().mapToInt(List::size).max().orElse(0);

        final var availableArenas = Arena.getArenas().stream()
                .filter(a -> a.getDisplayName().equals(mapName)
                        && a.getStatus() == GameState.waiting
                        && a.getPlayers().isEmpty()
                        && a.getMaxPlayers() >= teamCount * teamSize
                        && a.getTeams().size() >= teamCount
                        && a.getMaxInTeam() >= teamSize)
                .toList();
        if (availableArenas.isEmpty()) {
            throw new GameCreateException("No available arenas found for map " + mapName);
        }
        final var selectedArena = availableArenas.get(ThreadLocalRandom.current().nextInt(availableArenas.size()));
        arenaToGame.put(selectedArena.getArenaName(), game);
        try {
            Bukkit.getScheduler().callSyncMethod(plugin, (Callable<Void>) () -> {
                for (final var player : game.players()) {
                    selectedArena.addPlayer(player, true);
                }
                return null;
            }).get();
        } catch (InterruptedException | ExecutionException ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to add players to arena", ex);
            throw new GameCreateException("Failed to add players to arena", ex);
        }
        selectedArena.setTeamAssigner(arena -> {
            for (int i = 0; i < teamCount; i++) {
                var team = arena.getTeams().get(i);
                team.addPlayers(teams.get(i).toArray(new Player[0]));
            }
        });
        final var future = new CompletableFuture<Void>();
        arenaStartFutures.put(selectedArena.getArenaName(), future);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (selectedArena.getStatus() != GameState.waiting) return;
            future.completeExceptionally(new GameCreateException("Game not getting started"));
            cancelArenaStart(selectedArena);
        }, 30L);
        try {
            future.join();
        } catch (CompletionException ex) {
            throw ex.getCause();
        }
    }

    private void cancelArenaStart(final IArena arena) {
        if (arena == null) {
            return;
        }
        final String arenaName = arena.getArenaName();
        arenaStartFutures.remove(arenaName);
        arenaToGame.remove(arenaName);
        // Remove all players from the arena
        new ArrayList<>(arena.getPlayers()).forEach(player -> arena.removePlayer(player, false));
    }

    @EventHandler
    public void onArenaLeave(final PlayerLeaveArenaEvent event) {
        final var arena = event.getArena();
        if (arena == null || arena.getStatus() == GameState.playing) {
            return;
        }
        final String arenaName = arena.getArenaName();
        CompletableFuture<Void> future = arenaStartFutures.get(arenaName);
        if (future == null) return;
        cancelArenaStart(arena);
        future.completeExceptionally(new GameCreateException(
                String.format("Player `%s` left the game", event.getPlayer().getName())
        ));
    }

    @EventHandler
    public void onGameStart(final GameStateChangeEvent event) {
        if (event.getNewState() != GameState.playing) return;
        final String arenaName = event.getArena().getArenaName();
        CompletableFuture<Void> future = arenaStartFutures.remove(arenaName);
        if (future != null) {
            future.complete(null);
        }
    }

    @EventHandler
    public void onGameEnd(final GameEndEvent event) {
        final var arena = event.getArena();
        if (arena == null || arena.getStatsHolder() == null) {
            return;
        }
        final String arenaName = arena.getArenaName();
        final RankedGame rankedGame = arenaToGame.remove(arenaName);
        if (rankedGame == null) {
            return;
        }
        JsonArray teamsData = new JsonArray();
        final UUID winningTeamId = event.getTeamWinner().getIdentity();
        arena.getTeams().forEach(team -> {
            JsonArray playersData = new JsonArray();
            team.getMembers().forEach(member -> {
                JsonObject playerData = new JsonObject();
                playerData.addProperty("name", member.getName());
                arena.getStatsHolder().get(member).ifPresentOrElse(stats -> {
                    stats.getStatistic(DefaultStatistics.KILLS)
                            .ifPresent(stat -> playerData.addProperty("kills", (int) stat.getValue()));
                    stats.getStatistic(DefaultStatistics.BEDS_DESTROYED)
                            .ifPresent(stat -> playerData.addProperty("bedsBroken", (int) stat.getValue()));
                }, () -> {
                    playerData.addProperty("kills", 0);
                    playerData.addProperty("bedsBroken", 0);
                });
                playersData.add(playerData);
            });
            JsonObject teamData = new JsonObject();
            teamData.addProperty("win", team.getIdentity().equals(winningTeamId));
            teamData.add("players", playersData);
            teamsData.add(teamData);
        });
        JsonObject data = new JsonObject();
        data.add("teams", teamsData);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(String.format("https://rbw.giqnt.dev/project/%s/score/%s",
                        plugin.configHolder.rbwName(), rankedGame.id())))
                .header("Authorization", "Bearer " + plugin.configHolder.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(plugin.gson.toJson(data)))
                .build();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.api.request(httpRequest);
            } catch (IOException | InterruptedException e) {
                plugin.getLogger().severe("Failed to send game data to rbw bot: " + e.getMessage());
                throw new RuntimeException("Failed to send game data to rbw bot", e);
            }
        });
    }

    @EventHandler
    public void onArenaJoin(final PlayerJoinArenaEvent event) {
        final var arena = event.getArena();
        if (arena == null || !arena.getGroup().startsWith(plugin.configHolder.groupPrefix())) {
            return;
        }
        final RankedGame rankedGame = arenaToGame.get(arena.getArenaName());
        if (rankedGame == null || !rankedGame.players().contains(event.getPlayer())) {
            event.setCancelled(true);
        }
    }
}
