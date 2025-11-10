package dev.giqnt.rbw.hook.adapter;

import dev.giqnt.rbw.hook.game.GameCreateException;
import dev.giqnt.rbw.hook.game.RankedGame;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.Collection;
import java.util.List;

public class EmptyAdapter implements Adapter {
    @Override
    public Collection<MapInfo> getMaps() {
        return List.of();
    }

    @Override
    public boolean isReady(Player player) {
        return true;
    }

    @Override
    public @NonNull String createGame(RankedGame game) {
        throw new GameCreateException("BedWars adapter not defined.");
    }
}
