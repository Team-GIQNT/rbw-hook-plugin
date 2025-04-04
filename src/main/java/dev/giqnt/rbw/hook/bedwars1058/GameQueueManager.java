package dev.giqnt.rbw.hook.bedwars1058;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class GameQueueManager {
    private final HookPlugin plugin;
    private final ConcurrentLinkedQueue<RankedGame> gameQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean queuing = new AtomicBoolean(false);

    public GameQueueManager(final HookPlugin plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<Void> queue(final int id, final String mapName, final List<List<Player>> teams) {
        CompletableFuture<Void> promise = new CompletableFuture<>();
        gameQueue.offer(new RankedGame(id, mapName, teams.stream().flatMap(Collection::stream).collect(Collectors.toUnmodifiableSet()), teams, promise));
        processNext();
        return promise;
    }

    private void processNext() {
        if (queuing.compareAndSet(false, true)) {
            final var nextGame = gameQueue.poll();
            if (nextGame != null) {
                this.plugin.bedWars.createGame(nextGame)
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
}
