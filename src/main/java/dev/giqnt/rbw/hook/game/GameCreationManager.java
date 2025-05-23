package dev.giqnt.rbw.hook.game;

import dev.giqnt.rbw.hook.HookPlugin;
import org.bukkit.entity.Player;

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

    /**
     * Queues a new ranked game for processing
     *
     * @param id      The unique game identifier
     * @param mapName The map on which the game will be played
     * @param teams   List of teams containing players
     * @return A CompletableFuture that completes when the game is created
     */
    public CompletableFuture<Void> queue(final int id, final String mapName, final List<List<Player>> teams) {
        CompletableFuture<Void> promise = new CompletableFuture<>();

        // Create a set of all players from all teams
        final Set<Player> allPlayers = teams.stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toUnmodifiableSet());

        RankedGame game = new RankedGame(id, mapName, allPlayers, teams, promise);
        gameQueue.offer(game);

        this.plugin.getLogger().info("Game #" + id + " queued with " + allPlayers.size() + " players on map " + mapName);

        // Trigger queue processing
        triggerQueueProcessing();

        return promise;
    }

    /**
     * Triggers the queue processing if it's not already running
     */
    private void triggerQueueProcessing() {
        if (processing.compareAndSet(false, true)) {
            executorService.execute(this::processQueue);
        }
    }

    /**
     * Processes all games in the queue sequentially
     */
    private void processQueue() {
        try {
            RankedGame game;
            while ((game = gameQueue.poll()) != null) {
                try {
                    this.plugin.getLogger().info("Processing game #" + game.id() + " on map " + game.mapName());
                    this.plugin.getBedWars().createGame(game);
                    this.plugin.getLogger().info("Successfully created game #" + game.id());
                    game.promise().complete(null);
                } catch (Exception ex) {
                    this.plugin.getLogger().log(Level.SEVERE, "Failed to create game #" + game.id(), ex);
                    game.promise().completeExceptionally(ex);
                }
            }
        } catch (Exception e) {
            this.plugin.getLogger().log(Level.SEVERE, "Fatal error in queue processing", e);
        } finally {
            processing.set(false);

            // Check if new games were added while we were processing
            if (!gameQueue.isEmpty()) {
                triggerQueueProcessing();
            }
        }
    }

    /**
     * Safely shuts down the executor service
     */
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
