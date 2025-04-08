package dev.giqnt.rbw.hook.websocket.handler;

import com.google.gson.JsonObject;
import dev.giqnt.rbw.hook.HookPlugin;

import java.util.function.BiConsumer;
import java.util.logging.Logger;

public record MessageHandlerContext(HookPlugin plugin, Logger logger, JsonObject data,
                                    BiConsumer<String, JsonObject> messageSender) {}
