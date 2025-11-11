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
import java.util.logging.Level;

public class GameCreateRequestHandler extends MessageHandler {
    @Override
    public void execute(@NonNull final MessageHandlerContext context) {
        final var plugin = context.plugin();
        try {
            final var data = plugin.getGson().fromJson(context.data(), GameCreateRequestData.class);

            // Check for offline or unready players
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

            // Create the game
            plugin.getGameCreationManager()
                    .queue(data.id(), data.mapNames(), teamPlayers)
                    .handle((mapName, ex) -> {
                        if (ex != null) {
                            plugin.getLogger().log(Level.SEVERE, "Failed to create game " + data.id(), ex);
                            if (ex instanceof GameCreateException) {
                                sendGameCreateFailure(context, ex.getMessage());
                            } else {
                                sendGameCreateFailure(context, "Internal server error");
                            }
                        } else {
                            plugin.getLogger().info("Successfully created game " + data.id());
                            sendGameCreateSuccess(context, mapName);
                        }
                        return null;
                    });
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error processing create_game request", e);
            sendGameCreateFailure(context, "Internal server error");
        }
    }

    private @Nullable List<String> resolveMapNames(final JsonElement element) {
        if (element == null || !element.isJsonArray()) return null;
        final List<String> mapIds = new ArrayList<>();
        for (final JsonElement el : element.getAsJsonArray()) {
            if (el.isJsonPrimitive()) mapIds.add(el.getAsString());
        }
        return List.copyOf(mapIds);
    }

    /**
     * Sends game creation failure with an error message.
     */
    private void sendGameCreateFailure(final MessageHandlerContext context, final String message) {
        final JsonObject responseData = new JsonObject();
        responseData.addProperty("success", false);
        responseData.addProperty("message", message);
        context.messageReplier().accept(responseData);
    }

    /**
     * Sends game creation failure with a list of offline players.
     */
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

    /**
     * Sends game creation success response.
     */
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
