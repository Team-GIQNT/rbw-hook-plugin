package dev.giqnt.rbw.hook.websocket;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.giqnt.rbw.hook.HookPlugin;
import dev.giqnt.rbw.hook.websocket.handler.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class WebSocketManager implements WebSocket.Listener {
    private final HookPlugin plugin;
    private final URI uri;
    private final HttpClient client;
    private WebSocket webSocket;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService senderExecutor;
    private final BlockingQueue<String> messageQueue;
    private final AtomicBoolean manuallyClosed = new AtomicBoolean(false);
    private final AtomicInteger retryCount = new AtomicInteger(0);
    private final Duration initialDelay = Duration.ofSeconds(1);
    private final Duration maxDelay = Duration.ofSeconds(60);
    private final Map<String, MessageHandler> handlerMap = Map.of(
            "request_player_status", new RequestPlayerStatusMessageHandler(),
            "send_link_code", new SendLinkCodeMessageHandler(),
            "create_game", new CreateGameMessageHandler()
    );

    public WebSocketManager(final HookPlugin plugin) {
        this.plugin = plugin;
        this.uri = URI.create(String.format("wss://rbw.giqnt.dev/project/%s/ws", plugin.configHolder.rbwName()));
        this.client = HttpClient.newHttpClient();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.senderExecutor = Executors.newSingleThreadExecutor();
        this.messageQueue = new LinkedBlockingQueue<>();
        startMessageDispatcher();
    }

    public CompletionStage<Void> connect() {
        manuallyClosed.set(false);
        return doConnect();
    }

    private CompletionStage<Void> doConnect() {
        return client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + plugin.configHolder.token())
                .buildAsync(uri, this)
                .handle((ws, throwable) -> {
                    if (throwable != null) {
                        plugin.getLogger().log(Level.SEVERE, "[ws] Failed to connect to " + uri, throwable);
                        scheduleReconnect();
                    } else {
                        this.webSocket = ws;
                        retryCount.set(0);
                    }
                    return null;
                });
    }

    public void sendText(String message) {
        messageQueue.offer(message);
    }

    private void startMessageDispatcher() {
        senderExecutor.submit(() -> {
            while (true) {
                try {
                    String message = messageQueue.take();
                    if (webSocket == null) return;
                    webSocket.sendText(message, true).join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "[ws] Failed to send message", e);
                }
            }
        });
    }

    public void close() {
        manuallyClosed.set(true);
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Normal closure");
        }
        scheduler.shutdown();
        senderExecutor.shutdownNow();
    }

    private void scheduleReconnect() {
        if (manuallyClosed.get()) {
            return;
        }
        long delay = Math.min(
                initialDelay.multipliedBy((long) Math.pow(2, retryCount.get())).toMillis(),
                maxDelay.toMillis()
        );
        scheduler.schedule(this::doConnect, delay, TimeUnit.MILLISECONDS);
        retryCount.incrementAndGet();
    }

    private CompletionStage<Void> handleMessage(final String message) {
        return CompletableFuture.runAsync(() -> {
            final JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            final String type = json.get("type").getAsString();
            final JsonObject data = json.get("data").getAsJsonObject();
            final MessageHandler handler = handlerMap.get(type);
            if (handler == null) {
                plugin.getLogger().log(Level.WARNING, String.format("[ws] No handler found for type '%s'", type));
                return;
            }
            handler.execute(new MessageHandlerContext(
                    plugin,
                    plugin.getLogger(),
                    data,
                    (msgType, msgData) -> {
                        final JsonObject newMsg = new JsonObject();
                        newMsg.addProperty("type", msgType);
                        newMsg.add("data", msgData);
                        sendText(newMsg.toString());
                    }
            ));
        });
    }

    // WebSocket.Listener callbacks
    @Override
    public void onOpen(WebSocket webSocket) {
        plugin.getLogger().log(Level.INFO, "[ws] Connected to " + uri);
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        handleMessage(data.toString()).whenComplete(((r, throwable) -> {
            if (throwable != null) {
                plugin.getLogger().log(Level.SEVERE, "[ws] Failed to handle received message", throwable);
            }
        }));
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        plugin.getLogger().log(Level.INFO, String.format("[ws] Connection closed with status code: %d, reason: %s", statusCode, reason));
        scheduleReconnect();
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        plugin.getLogger().log(Level.SEVERE, "[ws] An error occurred", error);
    }
}
