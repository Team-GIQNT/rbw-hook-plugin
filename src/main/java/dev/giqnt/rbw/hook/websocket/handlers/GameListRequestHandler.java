package dev.giqnt.rbw.hook.websocket.handlers;

import com.google.gson.JsonObject;
import dev.giqnt.rbw.hook.game.HostedGame;
import dev.giqnt.rbw.hook.websocket.MessageHandler;
import dev.giqnt.rbw.hook.websocket.MessageHandlerContext;
import org.jspecify.annotations.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;

/** The api voids a hosted game that stops showing up here, so every game it may still score must be listed. */
public class GameListRequestHandler extends MessageHandler {
    @Override
    public void execute(@NonNull final MessageHandlerContext context) {
        final var plugin = context.plugin();
        final Map<String, HostedGame> games = new LinkedHashMap<>();
        for (final String id : plugin.getGameCreationManager().creatingIds()) {
            games.put(id, new HostedGame(id, HostedGame.State.CREATING, null));
        }
        for (final HostedGame game : plugin.getBedWars().getGames()) {
            games.put(game.id(), game);
        }
        final JsonObject responseData = new JsonObject();
        responseData.add("games", plugin.getGson().toJsonTree(games.values()));
        context.messageReplier().accept(responseData);
    }

    // Reads only concurrent maps, and the api expects an answer even while the main thread is busy.
    @Override
    public boolean runsOnMainThread() {
        return false;
    }
}
