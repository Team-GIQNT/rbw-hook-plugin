package dev.giqnt.rbw.hook.websocket.handler;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

public class RequestPlayerStatusMessageHandler extends MessageHandler {
    @Override
    public void execute(@NonNull MessageHandlerContext context) {
        final String playerName = context.data().get("name").getAsString();
        final Player player = Bukkit.getPlayerExact(playerName);

        boolean isReady = player != null && context.plugin().getBedWars().isReady(player);
        final JsonObject responseData = new JsonObject();
        responseData.addProperty("name", playerName);
        responseData.addProperty("isReady", isReady);
        context.messageSender().accept("player_status", responseData);
    }
}
