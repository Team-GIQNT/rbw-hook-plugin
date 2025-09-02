package dev.giqnt.rbw.hook.websocket.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import dev.giqnt.rbw.hook.HookPlugin;
import dev.giqnt.rbw.hook.game.GameCreateException;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class CreateGameMessageHandler extends MessageHandler {
    @Override
    public void execute(@NonNull final MessageHandlerContext context) {
        final JsonObject data = context.data();
        final HookPlugin plugin = context.plugin();
        try {
            final String id = data.getAsJsonPrimitive("id").getAsString();
            final List<String> mapNames = resolveMapNames(data);
            final var typeToken = new TypeToken<ArrayList<ArrayList<String>>>() {};
            final List<List<String>> teamsInfo = plugin.getGson().fromJson(
                    data.get("teams").getAsJsonArray(),
                    typeToken.getType()
            );

            // Check for offline or unready players
            final List<String> offlinePlayers = new ArrayList<>();
            final List<List<Player>> teamPlayers = teamsInfo.stream()
                    .map(team -> team.stream()
                            .map(name -> {
                                final Player player = Bukkit.getPlayerExact(name);
                                if (player == null || !plugin.getBedWars().isReady(player)) {
                                    offlinePlayers.add(name);
                                    return null;
                                }
                                return player;
                            })
                            .toList())
                    .toList();
            if (!offlinePlayers.isEmpty()) {
                sendGameCreateFailure(context, id, offlinePlayers);
                return;
            }

            // Create the game
            plugin.getGameCreationManager()
                    .queue(id, mapNames, teamPlayers)
                    .handle((result, ex) -> {
                        if (ex != null) {
                            plugin.getLogger().log(Level.SEVERE, "Failed to create game " + id, ex);
                            if (ex instanceof GameCreateException) {
                                sendGameCreateFailure(context, id, ex.getMessage());
                            } else {
                                sendGameCreateFailure(context, id, "Internal server error");
                            }
                        } else {
                            plugin.getLogger().info("Successfully created game " + id);
                            sendGameCreateSuccess(context, id);
                        }
                        return null;
                    });
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error processing create_game request", e);
            try {
                final String id = data.get("id").getAsString();
                sendGameCreateFailure(context, id, "Internal server error");
            } catch (Exception ignored) {
                // If we can't even get the game ID, just log the error
                plugin.getLogger().severe("Could not send failure response for malformed game creation request");
            }
        }
    }

    private @Nullable List<String> resolveMapNames(final JsonObject data) {
        final JsonElement rawMapId = data.get("mapId");
        if (rawMapId != null && rawMapId.isJsonPrimitive()) {
            return List.of(rawMapId.getAsString());
        }
        final JsonElement rawMapIds = data.get("mapIds");
        if (rawMapIds != null && rawMapIds.isJsonArray()) {
            final List<String> mapIds = new ArrayList<>();
            for (final JsonElement el : rawMapIds.getAsJsonArray()) {
                if (el.isJsonPrimitive()) mapIds.add(el.getAsString());
            }
            return List.copyOf(mapIds);
        }
        return null;
    }

    /**
     * Sends game creation failure with an error message.
     */
    private void sendGameCreateFailure(final MessageHandlerContext context, final String id, final String message) {
        final JsonObject responseData = new JsonObject();
        responseData.addProperty("id", id);
        responseData.addProperty("message", message);
        context.messageSender().accept("game_create_failure", responseData);
    }

    /**
     * Sends game creation failure with a list of offline players.
     */
    private void sendGameCreateFailure(final MessageHandlerContext context, final String id, final List<String> offlinePlayers) {
        final JsonObject responseData = new JsonObject();
        responseData.addProperty("id", id);
        responseData.addProperty("message", "Some players are offline or not ready");

        final JsonArray missingPlayers = new JsonArray();
        offlinePlayers.forEach(missingPlayers::add);
        responseData.add("missingPlayers", missingPlayers);

        context.messageSender().accept("game_create_failure", responseData);
    }

    /**
     * Sends game creation success response.
     */
    private void sendGameCreateSuccess(final MessageHandlerContext context, final String id) {
        final JsonObject responseData = new JsonObject();
        responseData.addProperty("id", id);
        responseData.addProperty("serverGameId", id);
        context.messageSender().accept("game_create_success", responseData);
    }
}
