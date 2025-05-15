package dev.giqnt.rbw.hook.websocket.handler;

import org.jspecify.annotations.NonNull;

public abstract class MessageHandler {
    public abstract void execute(@NonNull final MessageHandlerContext context);
}
