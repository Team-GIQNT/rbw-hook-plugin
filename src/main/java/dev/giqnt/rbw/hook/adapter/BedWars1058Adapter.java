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
import dev.giqnt.rbw.hook.game.HostedGame;
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

@RequiredArgsConstructor
public class BedWars1058Adapter implements Adapter, Listener {
    // 0, 2, 4, ..., 32s, then every 60s: about 10 minutes, to outlast a slow api deploy.
    private static final int REPORT_ATTEMPTS = 15;
    private static final long REPORT_MAX_DELAY_TICKS = 20 * 60;
    private final HookPlugin plugin;
    /** Arena name to its ranked game. */
    private final Map<String, ArenaSession> sessions = new HashMap<>();
    /** Game id to map name, for ended games whose result is still being reported. */
    private final ConcurrentMap<String, String> pendingResults = new ConcurrentHashMap<>();
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
    public @NonNull String createGame(@NonNull final RankedGame game) {
        final IArena arena = pickArena(game);
        final var session = new ArenaSession(game, arena.getDisplayName());
        sessions.put(arena.getArenaName(), session);
        try {
            arena.setTeamAssigner(teamAssigner);
            for (final var player : game.players()) {
                // isReady lets spectators through: an eliminated player watches their last game until it restarts.
                final IArena watching = Arena.getArenaByPlayer(player);
                if (watching != null && watching.isSpectator(player)) watching.removeSpectator(player, false);
                if (!arena.addPlayer(player, true)) {
                    throw new GameCreateException(
                            String.format("Player `%s` could not join the game", player.getName()), session.mapName());
                }
            }
            // addPlayer starts the countdown itself once the arena has enough players.
            if (arena.getStatus() == GameState.waiting) {
                throw new GameCreateException("Game not getting started", session.mapName());
            }
        } catch (Throwable ex) {
            discard(arena, session);
            if (ex instanceof GameCreateException || ex instanceof Error) throw ex;
            throw new GameCreateException("Failed to add players to arena", ex);
        }
        return session.mapName();
    }

    private IArena pickArena(final RankedGame game) {
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
        return arenas.get(ThreadLocalRandom.current().nextInt(arenas.size()));
    }

    private boolean discard(final IArena arena, final ArenaSession session) {
        if (!sessions.remove(arena.getArenaName(), session)) return false;
        new ArrayList<>(arena.getPlayers()).forEach(player -> arena.removePlayer(player, false));
        return true;
    }

    /** For a game createGame already returned, so only this report tells the api it failed. */
    private void abort(final IArena arena, final ArenaSession session, final String reason) {
        if (!discard(arena, session)) return;
        final JsonObject data = new JsonObject();
        data.addProperty("message", reason);
        data.addProperty("mapName", arena.getDisplayName());
        postUntilAccepted("/server-games/" + session.game().id() + "/start-failure", plugin.getGson().toJson(data), () -> {}, 0);
    }

    // The api ignores a repeated report, so retrying is safe; this covers api restarts during a deploy.
    private void postUntilAccepted(final String endpoint, final String body, final Runnable done, final int attempt) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            try {
                final int code = plugin.getApi().request(endpoint, "POST", body, Response::code);
                if (code < 500) {
                    if (code >= 300) plugin.getLogger().warning("POST " + endpoint + " rejected: " + code);
                    done.run();
                    return;
                }
                plugin.getLogger().warning("POST " + endpoint + " failed: " + code);
            } catch (IOException e) {
                plugin.getLogger().warning("POST " + endpoint + " failed: " + e.getMessage());
            }
            if (attempt + 1 < REPORT_ATTEMPTS) {
                postUntilAccepted(endpoint, body, done, attempt + 1);
            } else {
                plugin.getLogger().severe("Giving up POST " + endpoint);
                done.run();
            }
        }, attempt == 0 ? 0 : Math.min(20L << attempt, REPORT_MAX_DELAY_TICKS));
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
        if (arena == null) {
            return;
        }
        final String arenaName = arena.getArenaName();
        final ArenaSession session = sessions.remove(arenaName);
        if (session == null) {
            return;
        }
        final RankedGame rankedGame = session.game();
        if (arena.getStatsHolder() == null) {
            return;
        }
        JsonArray teamsData = new JsonArray();
        final UUID winningTeamId = event.getTeamWinner().getIdentity();
        arena.getTeams().forEach(team -> {
            final JsonArray playersData = new JsonArray();
            //noinspection deprecation
            team.getMembersCache().forEach(member -> {
                JsonObject playerData = new JsonObject();
                playerData.addProperty("name", member.getName());
                playerData.addProperty("uuid", member.getUniqueId().toString());
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

        pendingResults.put(rankedGame.id(), session.mapName());
        postUntilAccepted("/server-games/" + rankedGame.id() + "/result", plugin.getGson().toJson(data),
                () -> pendingResults.remove(rankedGame.id()), 0);
    }

    @Override
    public Collection<HostedGame> getGames() {
        final List<HostedGame> games = new ArrayList<>();
        // An arena can restart without GameEndEvent, e.g. a draw when the game timer runs out.
        sessions.entrySet().removeIf(entry -> {
            final IArena arena = Arena.getArenaByName(entry.getKey());
            final GameState status = arena == null ? null : arena.getStatus();
            final ArenaSession session = entry.getValue();
            if (status == GameState.starting || status == GameState.playing) {
                games.add(new HostedGame(session.game().id(), HostedGame.State.STARTED, session.mapName()));
                return false;
            }
            plugin.getLogger().warning("Dropping game #" + session.game().id() + ": arena " + entry.getKey()
                    + " is " + status + " without a result");
            return true;
        });
        pendingResults.forEach((id, mapName) -> games.add(new HostedGame(id, HostedGame.State.ENDED, mapName)));
        return games;
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

    private record ArenaSession(RankedGame game, String mapName) {
    }
}
