package dev.giqnt.rbw.hook.game;

import dev.giqnt.rbw.hook.HookPlugin;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class GameCreationManager {
    private final HookPlugin plugin;
    private final ConcurrentLinkedQueue<RankedGame> gameQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentMap<String, CompletableFuture<String>> promises = new ConcurrentHashMap<>();
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final ScheduledExecutorService executorService;

    public GameCreationManager(final HookPlugin plugin) {
        this.plugin = plugin;
        this.executorService = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "GameQueue-Worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    public CompletableFuture<String> queue(final String id, @Nullable final List<String> mapNames, final List<List<Player>> teams) {
        final CompletableFuture<String> promise = new CompletableFuture<>();
        final CompletableFuture<String> existing = promises.putIfAbsent(id, promise);
        if (existing != null) {
            return existing;
        }
        promise.whenComplete((mapName, ex) -> {
            if (ex != null) promises.remove(id, promise);
        });

        final Set<Player> allPlayers = teams.stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toUnmodifiableSet());

        final RankedGame game = new RankedGame(id, mapNames, allPlayers, teams, promise);
        gameQueue.offer(game);

        triggerQueueProcessing();

        return promise;
    }

    // A repeated game.create for the same id must get the same result as the first one.
    public @Nullable CompletableFuture<String> find(final String id) {
        return promises.get(id);
    }

    public void forget(final String id) {
        promises.remove(id);
    }

    private void triggerQueueProcessing() {
        if (processing.compareAndSet(false, true)) {
            executorService.execute(this::processQueue);
        }
    }

    private void processQueue() {
        try {
            RankedGame game;
            while ((game = gameQueue.poll()) != null) {
                try {
                    this.plugin.getLogger().info("Processing game #" + game.id());
                    final String mapName = this.plugin.getBedWars().createGame(game);
                    this.plugin.getLogger().info("Successfully created game #" + game.id() + " on map " + mapName);
                    game.promise().complete(mapName);
                } catch (Exception ex) {
                    this.plugin.getLogger().log(Level.SEVERE, "Failed to create game #" + game.id(), ex);
                    game.promise().completeExceptionally(ex);
                }
            }
        } catch (Exception e) {
            this.plugin.getLogger().log(Level.SEVERE, "Fatal error in queue processing", e);
        } finally {
            processing.set(false);

            // A game queued between the last poll and set(false) would otherwise never be processed.
            if (!gameQueue.isEmpty()) {
                triggerQueueProcessing();
            }
        }
    }

    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
                this.plugin.getLogger().warning("Force shutdown of GameQueueManager executor service");
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
            this.plugin.getLogger().warning("Interrupted while shutting down GameQueueManager");
        }
    }
}
