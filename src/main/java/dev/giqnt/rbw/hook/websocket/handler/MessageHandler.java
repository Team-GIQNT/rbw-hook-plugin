package dev.giqnt.rbw.hook.websocket.handler;

import javax.annotation.Nonnull;

public abstract class MessageHandler {
    public abstract void execute(@Nonnull final MessageHandlerContext context);
}
