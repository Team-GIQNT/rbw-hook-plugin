package dev.giqnt.rbw.hook.adapter;

import dev.giqnt.rbw.hook.game.RankedGame;
import org.bukkit.entity.Player;

import java.util.Collection;

public interface Adapter {
    Collection<MapInfo> getMaps();

    boolean isReady(final Player player);

    void createGame(final RankedGame game);
}
