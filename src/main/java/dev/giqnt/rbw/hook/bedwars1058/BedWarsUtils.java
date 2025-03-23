package dev.giqnt.rbw.hook.bedwars1058;

import com.andrei1058.bedwars.api.BedWars;
import com.andrei1058.bedwars.api.arena.GameState;
import com.andrei1058.bedwars.api.arena.IArena;
import com.andrei1058.bedwars.api.arena.stats.DefaultStatistics;
import com.andrei1058.bedwars.api.configuration.ConfigPath;
import com.andrei1058.bedwars.api.events.gameplay.GameEndEvent;
import com.andrei1058.bedwars.api.events.gameplay.GameStateChangeEvent;
import com.andrei1058.bedwars.api.events.player.PlayerJoinArenaEvent;
import com.andrei1058.bedwars.api.events.player.PlayerLeaveArenaEvent;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class BedWarsUtils implements Listener {
    public final HookPlugin plugin;
    private final BedWars bedWars;
    private final HashMap<String, RankedGame> arenaToGame = new HashMap<>();
    private final HashMap<String, CompletableFuture<Void>> arenaStartFutures = new HashMap<>();

    private final ConcurrentLinkedQueue<RankedGame> gameQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean queuing = new AtomicBoolean(false);

    public BedWarsUtils(final HookPlugin plugin) {
        this.plugin = plugin;
        this.bedWars = Objects.requireNonNull(Bukkit.getServicesManager().getRegistration(BedWars.class)).getProvider();
    }

    public Map<String, HashMap<String, MapInfo>> getMapsInfo() {
        final Map<String, HashMap<String, MapInfo>> maps = new HashMap<>();
        bedWars.getArenaUtil().getArenas().forEach(arena -> {
            final String group = arena.getGroup();
            if (!group.startsWith(plugin.configHolder.groupPrefix())) return;
            final String category = group.substring(plugin.configHolder.groupPrefix().length());
            final String mapName = arena.getDisplayName();
            final MapInfo mapInfo = new MapInfo(category, mapName, arena.getConfig().getInt(ConfigPath.ARENA_CONFIGURATION_MAX_BUILD_Y));
            maps.computeIfAbsent(category, k -> new HashMap<>()).put(mapName, mapInfo);
        });
        return maps;
    }

    public boolean isInGame(final Player player) {
        return bedWars.getArenaUtil().isPlaying(player) || bedWars.getArenaUtil().isSpectating(player);
    }

    public CompletableFuture<Void> createGame(final int id, final String mapName, final List<List<Player>> teams) {
        CompletableFuture<Void> promise = new CompletableFuture<>();
        gameQueue.offer(new RankedGame(id, mapName, teams.stream().flatMap(Collection::stream).collect(Collectors.toUnmodifiableSet()), teams, promise));
        processNext();
        return promise;
    }

    private void processNext() {
        if (queuing.compareAndSet(false, true)) {
            final var nextGame = gameQueue.poll();
            if (nextGame != null) {
                this.handleGameCreate(nextGame)
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                nextGame.promise().completeExceptionally(ex);
                            } else {
                                nextGame.promise().complete(null);
                            }
                        });
            }
            queuing.set(false);
            if (!gameQueue.isEmpty()) {
                processNext();
            }
        }
    }

    private CompletableFuture<Void> handleGameCreate(final RankedGame game) {
        final var rankedGame = this.arenaToGame.values().stream().filter(g -> g.id() == game.id()).findFirst();
        if (rankedGame.isPresent()) {
            return CompletableFuture.failedFuture(new GameCreateException("Game already created"));
        }
        final CompletableFuture<Void> future = new CompletableFuture<>();
        final var teams = game.teams();
        final var mapName = game.mapName();
        final int teamCount = teams.size();
        final int teamSize = Collections.max(teams.stream().map(List::size).toList());
        final var filtered = bedWars.getArenaUtil().getArenas().stream()
                .filter(a -> a.getDisplayName().equals(mapName)
                        && a.getStatus() == GameState.waiting
                        && a.getPlayers().isEmpty()
                        && a.getMaxPlayers() >= teamCount * teamSize
                        && a.getTeams().size() >= teamCount
                        && a.getMaxInTeam() >= teamSize)
                .toList();

        if (filtered.isEmpty()) {
            future.completeExceptionally(new GameCreateException("No available arenas found for map " + mapName));
            return future;
        }

        final var arena = filtered.get(new Random().nextInt(filtered.size()));
        arenaToGame.put(arena.getArenaName(), game);

        CompletableFuture.supplyAsync(() -> {
            try {
                Bukkit.getScheduler().callSyncMethod(plugin, (Callable<Void>) () -> {
                    teams.forEach(team -> team.forEach(player -> arena.addPlayer(player, true)));
                    return null;
                }).get();
                return null;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }).whenComplete((result, throwable) -> {
            if (throwable != null) {
                future.completeExceptionally(new GameCreateException("Failed to add players to arena", throwable));
            } else {
                arena.setTeamAssigner(iArena -> {
                    for (int i = 0; i < teamCount; i++) {
                        final var team = iArena.getTeams().get(i);
                        team.addPlayers(teams.get(i).toArray(new Player[0]));
                    }
                });
                arenaStartFutures.put(arena.getArenaName(), future);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (arena.getStatus() != GameState.waiting) return;
                    future.completeExceptionally(new GameCreateException("Game not getting started"));
                    cancelArenaStart(arena);
                }, 30);
            }
        });

        return future;
    }

    private void cancelArenaStart(final IArena arena) {
        this.arenaStartFutures.remove(arena.getArenaName());
        this.arenaToGame.remove(arena.getArenaName());
        new ArrayList<>(arena.getPlayers()).forEach(player -> arena.removePlayer(player, false));
    }

    @EventHandler
    public void onArenaLeave(final PlayerLeaveArenaEvent event) {
        final var arena = event.getArena();
        final var arenaName = arena.getArenaName();
        if (arena.getStatus() == GameState.playing) return;
        final var future = this.arenaStartFutures.get(arenaName);
        if (future == null) return;
        cancelArenaStart(arena);
        future.completeExceptionally(new GameCreateException(
                String.format("Player `%s` left the game", event.getPlayer().getName())
        ));
    }

    @EventHandler
    public void onGameStart(final GameStateChangeEvent event) {
        if (event.getNewState() != GameState.playing) return;
        final var arena = event.getArena();
        final var future = this.arenaStartFutures.get(arena.getArenaName());
        if (future == null) return;
        this.arenaStartFutures.remove(arena.getArenaName());
        future.complete(null);
    }

    @EventHandler
    public void onGameEnd(final GameEndEvent event) {
        final var arena = event.getArena();
        final var statsHolder = arena.getStatsHolder();
        if (statsHolder == null) return;
        final String arenaName = arena.getArenaName();
        if (!arenaToGame.containsKey(arenaName)) return;
        final var rankedGame = arenaToGame.get(arenaName);
        arenaToGame.remove(arenaName);
        final JsonArray teamsData = new JsonArray();
        final UUID winningTeamId = event.getTeamWinner().getIdentity();
        arena.getTeams().forEach(team -> {
            final JsonArray playersData = new JsonArray();
            team.getMembers().forEach(member -> {
                final var stats = statsHolder.get(member).orElse(null);
                final JsonObject playerData = new JsonObject();
                playerData.addProperty("name", member.getName());
                if (stats != null) {
                    stats.getStatistic(DefaultStatistics.KILLS).ifPresent(stat ->
                            playerData.addProperty("kills", (int) stat.getValue())
                    );
                    stats.getStatistic(DefaultStatistics.BEDS_DESTROYED).ifPresent(stat ->
                            playerData.addProperty("bedsBroken", (int) stat.getValue())
                    );
                } else {
                    playerData.addProperty("kills", 0);
                    playerData.addProperty("bedsBroken", 0);
                }
                playersData.add(playerData);
            });
            final JsonObject teamData = new JsonObject();
            teamData.addProperty("win", team.getIdentity().equals(winningTeamId));
            teamData.add("players", playersData);
            teamsData.add(teamData);
        });
        final JsonObject data = new JsonObject();
        data.add("teams", teamsData);
        final HttpRequest httpRequest = HttpRequest
                .newBuilder()
                .uri(URI.create(String.format("https://rbw.giqnt.dev/project/%s/score/%s", plugin.configHolder.rbwName(), rankedGame.id())))
                .header("Authorization", "Bearer " + plugin.configHolder.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(plugin.gson.toJson(data)))
                .build();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.api.request(httpRequest);
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("Failed to send game data to rbw bot", e);
            }
        });
    }

    @EventHandler
    public void onArenaJoin(final PlayerJoinArenaEvent event) {
        final var arena = event.getArena();
        if (!arena.getGroup().startsWith(plugin.configHolder.groupPrefix())) return;
        final var rankedGame = arenaToGame.get(arena.getArenaName());
        if (rankedGame == null || !rankedGame.players().contains(event.getPlayer())) {
            event.setCancelled(true);
        }
    }
}
