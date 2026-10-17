package dev.giqnt.rbw.hook.websocket;

import org.jspecify.annotations.NonNull;

public abstract class MessageHandler {
    public abstract void execute(@NonNull final MessageHandlerContext context);

    /** Bukkit and BedWars state is not thread-safe, so handlers touching it must run on the main thread. */
    public boolean runsOnMainThread() {
        return true;
    }
}
