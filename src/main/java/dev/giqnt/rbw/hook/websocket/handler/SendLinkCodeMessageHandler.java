package dev.giqnt.rbw.hook.websocket.handler;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

public class SendLinkCodeMessageHandler extends MessageHandler {
    @Override
    public void execute(@NonNull final MessageHandlerContext context) {
        final var data = context.data();
        final String playerName = data.get("name").getAsString();
        final String code = data.get("code").getAsString();
        final Player player = Bukkit.getPlayerExact(playerName);
        if (player != null) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&eYour code is: &f" + code));
            context.logger().info("Sent verification code to " + playerName);
        } else {
            context.logger().info("Failed to send code to " + playerName + " (player offline)");
        }
    }
}
