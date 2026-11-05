package dev.giqnt.rbw.hook.adapter;

import dev.giqnt.rbw.hook.api.GameCallbacks;
import dev.giqnt.rbw.hook.api.RankedGame;

import java.util.concurrent.CompletableFuture;

/**
 * An adapter that may need time before it knows the map of a game, such as for a map vote. The future completes on the
 * main thread with what {@link dev.giqnt.rbw.hook.api.BedWarsAdapter#startGame} would have returned, or fails with the
 * exception it would have thrown. Internal to rbw-hook: the public adapter API is unchanged.
 */
public interface DeferredStartAdapter {
    CompletableFuture<String> startGameAsync(RankedGame game, GameCallbacks callbacks);
}
