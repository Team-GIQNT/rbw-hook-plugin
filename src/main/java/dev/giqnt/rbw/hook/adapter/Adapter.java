package dev.giqnt.rbw.hook.adapter;

import dev.giqnt.rbw.hook.game.HostedGame;
import dev.giqnt.rbw.hook.game.RankedGame;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.Collection;

/** Called on the main thread only. */
public interface Adapter {
    Collection<MapInfo> getMaps();

    boolean isReady(final Player player);

    /** Returns the map name once the arena is counting down, or throws GameCreateException. */
    @NonNull
    String createGame(final RankedGame game);

    /** False on servers such as a lobby, which answer game.create as unsupported. */
    default boolean canHostGames() {
        return true;
    }

    /** Games past game.create, including ended ones whose result is still being reported. */
    Collection<HostedGame> getGames();
}
