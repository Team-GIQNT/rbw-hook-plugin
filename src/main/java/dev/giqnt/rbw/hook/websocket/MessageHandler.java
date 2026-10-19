package dev.giqnt.rbw.hook.websocket;

import org.jspecify.annotations.NonNull;

/** Runs on the main thread, since Bukkit and BedWars state is not thread-safe. */
public abstract class MessageHandler {
    public abstract void execute(@NonNull final MessageHandlerContext context);
}
