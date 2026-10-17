package dev.giqnt.rbw.hook.websocket.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.giqnt.rbw.hook.game.GameCreateException;
import dev.giqnt.rbw.hook.websocket.MessageHandler;
import dev.giqnt.rbw.hook.websocket.MessageHandlerContext;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class GameCreateRequestHandler extends MessageHandler {
    @Override
    public void execute(@NonNull final MessageHandlerContext context) {
        final var plugin = context.plugin();
        if (!plugin.getBedWars().canHostGames()) {
            final JsonObject responseData = new JsonObject();
            responseData.addProperty("success", false);
            responseData.addProperty("unsupported", true);
            responseData.addProperty("message", "This server can't host games.");
            context.messageReplier().accept(responseData);
            return;
        }
        try {
            final var data = plugin.getGson().fromJson(context.data(), GameCreateRequestData.class);
            final var existing = plugin.getGameCreationManager().find(data.id());
            if (existing != null) {
                plugin.getLogger().info("Game " + data.id() + " already requested, replying with its result");
                replyWhenCreated(context, data.id(), existing);
                return;
            }

            final List<GameCreateRequestTeamMemberData> missingPlayers = new ArrayList<>();
            final List<List<Player>> teamPlayers = data.teams().stream()
                    .map(team -> team.members()
                            .stream().map(member -> {
                                final Player player = plugin.getServer().getPlayer(member.uuid());
                                if (player == null || !player.isOnline() || !plugin.getBedWars().isReady(player)) {
                                    missingPlayers.add(member);
                                    return null;
                                }
                                return player;
                            })
                            .toList())
                    .toList();
            if (!missingPlayers.isEmpty()) {
                sendGameCreateFailure(context, missingPlayers);
                return;
            }

            replyWhenCreated(context, data.id(), plugin.getGameCreationManager().queue(data.id(), data.mapNames(), teamPlayers));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error processing create_game request", e);
            sendGameCreateFailure(context, "Internal server error");
        }
    }

    private void replyWhenCreated(final MessageHandlerContext context, final String id, final CompletableFuture<String> promise) {
        final var plugin = context.plugin();
        promise.handle((mapName, ex) -> {
            if (ex != null) {
                plugin.getLogger().log(Level.SEVERE, "Failed to create game " + id, ex);
                if (ex instanceof GameCreateException gameCreateException) {
                    sendGameCreateFailure(context, gameCreateException.getMessage(), gameCreateException.getMapName());
                } else {
                    sendGameCreateFailure(context, "Internal server error");
                }
            } else {
                plugin.getLogger().info("Successfully created game " + id);
                sendGameCreateSuccess(context, mapName);
            }
            return null;
        });
    }

    private @Nullable List<String> resolveMapNames(final JsonElement element) {
        if (element == null || !element.isJsonArray()) return null;
        final List<String> mapIds = new ArrayList<>();
        for (final JsonElement el : element.getAsJsonArray()) {
            if (el.isJsonPrimitive()) mapIds.add(el.getAsString());
        }
        return List.copyOf(mapIds);
    }

    private void sendGameCreateFailure(final MessageHandlerContext context, final String message) {
        sendGameCreateFailure(context, message, null);
    }

    private void sendGameCreateFailure(final MessageHandlerContext context, final String message, @Nullable final String mapName) {
        final JsonObject responseData = new JsonObject();
        responseData.addProperty("success", false);
        responseData.addProperty("message", message);
        if (mapName != null) responseData.addProperty("mapName", mapName);
        context.messageReplier().accept(responseData);
    }

    private void sendGameCreateFailure(final MessageHandlerContext context, final List<GameCreateRequestTeamMemberData> offlinePlayers) {
        final JsonObject responseData = new JsonObject();
        responseData.addProperty("success", false);
        responseData.addProperty("message", "Some players are offline or not ready");
        final JsonArray missingPlayers = new JsonArray();
        offlinePlayers.forEach(data ->
                missingPlayers.add(context.plugin().getGson().toJsonTree(data)));
        responseData.add("missingPlayers", missingPlayers);
        context.messageReplier().accept(responseData);
    }

    private void sendGameCreateSuccess(final MessageHandlerContext context, final String mapName) {
        final JsonObject responseData = new JsonObject();
        responseData.addProperty("success", true);
        responseData.addProperty("mapName", mapName);
        context.messageReplier().accept(responseData);
    }

    private record GameCreateRequestData(String id, List<String> mapNames, List<GameCreateRequestTeamData> teams) {
    }

    private record GameCreateRequestTeamData(String id, List<GameCreateRequestTeamMemberData> members) {
    }

    private record GameCreateRequestTeamMemberData(String userId, UUID uuid, String name) {
    }
}
