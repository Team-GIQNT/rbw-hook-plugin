package dev.giqnt.rbw.hook.websocket.handlers;

import com.google.gson.JsonObject;
import dev.giqnt.rbw.hook.websocket.MessageHandler;
import dev.giqnt.rbw.hook.websocket.MessageHandlerContext;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

public class PlayerMessageSendRequestHandler extends MessageHandler {
    @Override
    public void execute(@NonNull final MessageHandlerContext context) {
        final var plugin = context.plugin();
        final var data = plugin.getGson().fromJson(context.data(), PlayerMessageSendRequestData.class);
        final Player player = data.uuid != null
                ? plugin.getServer().getPlayer(data.uuid)
                : Bukkit.getPlayerExact(data.name);
        final JsonObject responseData = new JsonObject();
        if (player != null) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', data.message));
            responseData.addProperty("delivered", true);
            responseData.addProperty("uuid", player.getUniqueId().toString());
            responseData.addProperty("name", player.getName());
        } else {
            responseData.addProperty("delivered", false);
        }
        context.messageReplier().accept(responseData);
    }

    private record PlayerMessageSendRequestData(UUID uuid, String name, String message) {
    }
}
