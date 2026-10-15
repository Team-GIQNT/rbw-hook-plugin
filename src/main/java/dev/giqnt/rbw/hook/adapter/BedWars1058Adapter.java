package dev.giqnt.rbw.hook.adapter;

import com.andrei1058.bedwars.api.arena.GameState;
import com.andrei1058.bedwars.api.arena.IArena;
import com.andrei1058.bedwars.api.arena.stats.DefaultStatistics;
import com.andrei1058.bedwars.api.arena.team.ITeamAssigner;
import com.andrei1058.bedwars.api.configuration.ConfigPath;
import com.andrei1058.bedwars.api.events.gameplay.GameEndEvent;
import com.andrei1058.bedwars.api.events.player.PlayerJoinArenaEvent;
import com.andrei1058.bedwars.api.events.player.PlayerLeaveArenaEvent;
import com.andrei1058.bedwars.arena.Arena;
import com.andrei1058.bedwars.arena.team.TeamAssigner;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.giqnt.rbw.hook.HookPlugin;
import dev.giqnt.rbw.hook.game.GameCreateException;
import dev.giqnt.rbw.hook.game.RankedGame;
import lombok.RequiredArgsConstructor;
import okhttp3.Response;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

@RequiredArgsConstructor
public class BedWars1058Adapter implements Adapter, Listener {
    private static final long[] REPORT_DELAY_TICKS = {0, 40, 80, 160, 320, 640};
    private final HookPlugin plugin;
    private final ConcurrentMap<String, ArenaSession> sessions = new ConcurrentHashMap<>();
    private final ITeamAssigner teamAssigner = arena -> {
        final ArenaSession session = sessions.get(arena.getArenaName());
        if (session == null) {
            new TeamAssigner().assignTeams(arena);
            return;
        }
        final List<List<Player>> teams = session.game().teams();
        for (int i = 0; i < teams.size(); i++) {
            arena.getTeams().get(i).addPlayers(teams.get(i).toArray(new Player[0]));
        }
    };

    @Override
    public Collection<MapInfo> getMaps() {
        final Set<MapInfo> maps = new HashSet<>();
        final String groupPrefix = plugin.getConfigHolder().groupPrefix();
        for (final var arena : Arena.getArenas()) {
            final String group = arena.getGroup();
            if (!group.startsWith(groupPrefix)) continue;
            final String mapName = arena.getDisplayName();
            final int maxBuildY = arena.getConfig().getInt(ConfigPath.ARENA_CONFIGURATION_MAX_BUILD_Y);
            maps.add(new MapInfo(group, mapName, maxBuildY));
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

    @Override
    public @NonNull String createGame(@NonNull final RankedGame game) throws GameCreateException {
        if (sessions.values().stream().anyMatch(s -> s.game().id().equals(game.id()))) {
            throw new GameCreateException("Game already created");
        }
        final List<List<Player>> teams = game.teams();
        final List<String> mapNames = game.mapNames();
        final Set<String> mapNameSet = mapNames == null ? null : Set.copyOf(mapNames);
        final int teamCount = teams.size();
        final int teamSize = teams.stream().mapToInt(List::size).max().orElse(0);

        final Map<String, List<IArena>> mapToArenas = new HashMap<>();
        for (final IArena arena : Arena.getArenas()) {
            if (!arena.getGroup().startsWith(plugin.getConfigHolder().groupPrefix())
                    || arena.getStatus() != GameState.waiting
                    || !arena.getPlayers().isEmpty()
                    || arena.getMaxPlayers() < teamCount * teamSize
                    || arena.getTeams().size() < teamCount
                    || arena.getMaxInTeam() < teamSize) {
                continue;
            }
            if (mapNameSet == null || mapNameSet.contains(arena.getDisplayName())) {
                mapToArenas.computeIfAbsent(arena.getDisplayName(), k -> new ArrayList<>()).add(arena);
            }
        }
        if (mapToArenas.isEmpty()) {
            throw new GameCreateException("No available arena found");
        }
        final List<List<IArena>> arenaGroups = List.copyOf(mapToArenas.values());
        final List<IArena> arenas = arenaGroups.get(ThreadLocalRandom.current().nextInt(arenaGroups.size()));
        final IArena selectedArena = arenas.get(ThreadLocalRandom.current().nextInt(arenas.size()));
        final var session = new ArenaSession(game, new CompletableFuture<>());
        sessions.put(selectedArena.getArenaName(), session);
        try {
            Bukkit.getScheduler().callSyncMethod(plugin, (Callable<Void>) () -> {
                selectedArena.setTeamAssigner(teamAssigner);
                for (final var player : game.players()) {
                    // isReady lets spectators through: an eliminated player watches their last game until it restarts.
                    final IArena watching = Arena.getArenaByPlayer(player);
                    if (watching != null && watching.isSpectator(player)) watching.removeSpectator(player, false);
                    if (!selectedArena.addPlayer(player, true)) {
                        abort(selectedArena, session, String.format("Player `%s` could not join the game", player.getName()));
                        return null;
                    }
                }
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (sessions.get(selectedArena.getArenaName()) != session) return;
                    if (selectedArena.getStatus() == GameState.waiting) {
                        abort(selectedArena, session, "Game not getting started");
                    } else {
                        session.started().complete(null);
                    }
                }, 30L);
                return null;
            }).get();
        } catch (InterruptedException | ExecutionException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            plugin.getLogger().log(Level.SEVERE, "Failed to add players to arena", ex);
            // Interrupted means the plugin is shutting down, and runTask throws on a disabled plugin.
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> abort(selectedArena, session, "Failed to add players to arena"));
            }
            throw new GameCreateException("Failed to add players to arena", ex);
        }
        try {
            session.started().join();
        } catch (CompletionException ex) {
            if (ex.getCause() instanceof GameCreateException exception) {
                throw new GameCreateException(exception.getMessage(), selectedArena.getDisplayName());
            }
            throw new RuntimeException("Failed to create game", ex);
        }
        return selectedArena.getDisplayName();
    }

    /** Main thread only. */
    private void abort(final IArena arena, final ArenaSession session, final String reason) {
        if (!sessions.remove(arena.getArenaName(), session)) return;
        new ArrayList<>(arena.getPlayers()).forEach(player -> arena.removePlayer(player, false));
        if (session.started().completeExceptionally(new GameCreateException(reason))) return;
        // game.create already reported success (or is about to), so only this report tells the api.
        plugin.getGameCreationManager().forget(session.game().id());
        final JsonObject data = new JsonObject();
        data.addProperty("message", reason);
        data.addProperty("mapName", arena.getDisplayName());
        postUntilAccepted("/server-games/" + session.game().id() + "/start-failure", plugin.getGson().toJson(data), 0);
    }

    // The api ignores a repeated report, so retrying is safe; this covers api restarts during a deploy.
    private void postUntilAccepted(final String endpoint, final String body, final int attempt) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            try {
                final int code = plugin.getApi().request(endpoint, "POST", body, Response::code);
                if (code < 500) {
                    if (code >= 300) plugin.getLogger().warning("POST " + endpoint + " rejected: " + code);
                    return;
                }
                plugin.getLogger().warning("POST " + endpoint + " failed: " + code);
            } catch (IOException e) {
                plugin.getLogger().warning("POST " + endpoint + " failed: " + e.getMessage());
            }
            if (attempt + 1 < REPORT_DELAY_TICKS.length) {
                postUntilAccepted(endpoint, body, attempt + 1);
            } else {
                plugin.getLogger().severe("Giving up POST " + endpoint);
            }
        }, REPORT_DELAY_TICKS[attempt]);
    }

    @EventHandler
    public void onArenaLeave(final PlayerLeaveArenaEvent event) {
        if (event.isSpectator()) return;
        final var arena = event.getArena();
        if (arena == null || (arena.getStatus() != GameState.waiting && arena.getStatus() != GameState.starting)) {
            return;
        }
        final ArenaSession session = sessions.get(arena.getArenaName());
        if (session == null) return;
        abort(arena, session, String.format("Player `%s` left the game", event.getPlayer().getName()));
    }

    @EventHandler
    public void onGameEnd(final GameEndEvent event) {
        final var arena = event.getArena();
        if (arena == null || arena.getStatsHolder() == null) {
            return;
        }
        final String arenaName = arena.getArenaName();
        final ArenaSession session = sessions.remove(arenaName);
        if (session == null) {
            return;
        }
        final RankedGame rankedGame = session.game();
        plugin.getGameCreationManager().forget(rankedGame.id());
        JsonArray teamsData = new JsonArray();
        final UUID winningTeamId = event.getTeamWinner().getIdentity();
        arena.getTeams().forEach(team -> {
            final JsonArray playersData = new JsonArray();
            //noinspection deprecation
            team.getMembersCache().forEach(member -> {
                JsonObject playerData = new JsonObject();
                playerData.addProperty("name", member.getName());
                arena.getStatsHolder().get(member.getUniqueId()).ifPresentOrElse(stats -> {
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
            final JsonObject teamData = new JsonObject();
            teamData.addProperty("win", team.getIdentity().equals(winningTeamId));
            teamData.add("players", playersData);
            teamsData.add(teamData);
        });
        final JsonObject data = new JsonObject();
        data.add("teams", teamsData);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getApi().request("/server-games/" + rankedGame.id() + "/result", "POST", plugin.getGson().toJson(data));
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to send game data to rbw bot: " + e.getMessage());
                throw new RuntimeException("Failed to send game data to rbw bot", e);
            }
        });
    }

    @EventHandler
    public void onArenaJoin(final PlayerJoinArenaEvent event) {
        if (event.isSpectator()) return;
        final var arena = event.getArena();
        if (arena == null || !arena.getGroup().startsWith(plugin.getConfigHolder().groupPrefix())) {
            return;
        }
        final ArenaSession session = sessions.get(arena.getArenaName());
        if (session == null || !session.game().players().contains(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    private record ArenaSession(RankedGame game, CompletableFuture<Void> started) {
    }
}
