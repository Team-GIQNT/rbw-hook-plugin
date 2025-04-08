package dev.giqnt.rbw.hook.bedwars1058.game;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public record RankedGame(
        int id,
        String mapName,
        Set<Player> players,
        List<List<Player>> teams,
        CompletableFuture<Void> promise
) {
}
