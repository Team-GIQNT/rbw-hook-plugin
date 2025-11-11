package dev.giqnt.rbw.hook.websocket;

import com.google.gson.JsonObject;
import dev.giqnt.rbw.hook.HookPlugin;

import java.util.function.Consumer;
import java.util.logging.Logger;

public record MessageHandlerContext(HookPlugin plugin, Logger logger, JsonObject data,
                                    Consumer<JsonObject> messageReplier) {}
