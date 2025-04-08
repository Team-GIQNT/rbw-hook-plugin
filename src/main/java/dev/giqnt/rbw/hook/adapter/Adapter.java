package dev.giqnt.rbw.hook.adapter;

import dev.giqnt.rbw.hook.game.RankedGame;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

public interface Adapter {
    Collection<MapInfo> getMaps();

    boolean isReady(final Player player);

    CompletableFuture<Void> createGame(final RankedGame game);
}
