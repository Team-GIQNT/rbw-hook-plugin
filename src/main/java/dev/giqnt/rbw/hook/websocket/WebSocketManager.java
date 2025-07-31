package dev.giqnt.rbw.hook.websocket;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.giqnt.rbw.hook.HookPlugin;
import dev.giqnt.rbw.hook.websocket.handler.*;
import okhttp3.*;
import okhttp3.dnsoverhttps.DnsOverHttps;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.*;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class WebSocketManager {
    private final HookPlugin plugin;
    private final URI uri;
    private final OkHttpClient client;
    private final ScheduledExecutorService scheduler; // reconnection
    private final ExecutorService senderExecutor;
    private final BlockingQueue<String> messageQueue;
    private final AtomicBoolean manuallyClosed = new AtomicBoolean(false);
    private final Duration reconnectDelay = Duration.ofSeconds(5);
    private final Map<String, MessageHandler> handlerMap = Map.of(
            "request_player_status", new RequestPlayerStatusMessageHandler(),
            "send_link_code", new SendLinkCodeMessageHandler(),
            "create_game", new CreateGameMessageHandler()
    );
    private volatile WebSocket webSocket;

    public WebSocketManager(final HookPlugin plugin) {
        this.plugin = plugin;

        this.uri = URI.create(
                String.format("wss://rbw.giqnt.dev/project/%s/ws", plugin.getConfigHolder().rbwName())
        );
        OkHttpClient.Builder builder = new OkHttpClient.Builder().pingInterval(Duration.ofSeconds(30));
        if (plugin.getConfig().getBoolean("proxy.enabled", false)) {
            FileConfiguration config = plugin.getConfig();
            int port = config.getInt("proxy.port", 8585);
            String ip = config.getString("proxy.ip", "127.0.0.1");
            String username = config.getString("proxy.username", "");
            String password = config.getString("proxy.password", "");
            builder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(ip, port))).proxyAuthenticator(
                    (route, response) -> {
                        String credential = Credentials.basic(username, password);
                        return response
                                .request()
                                .newBuilder()
                                .header("Proxy-Authorization", credential)
                                .build();
                    });
        }
        this.client = builder.build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.senderExecutor = Executors.newSingleThreadExecutor();
        this.messageQueue = new LinkedBlockingQueue<>();
        startMessageDispatcher();
    }

    public void connect() {
        manuallyClosed.set(false);
        doConnect();
    }

    private void doConnect() {
        final Request request = new Request.Builder()
                .url(uri.toString())
                .addHeader("Authorization", "Bearer " + plugin.getConfigHolder().token())
                .build();
        this.webSocket = client.newWebSocket(request, new InternalWebSocketListener());
        plugin.getLogger().log(Level.INFO, "[ws] Connecting...");
    }

    private void scheduleReconnect() {
        if (manuallyClosed.get()) {
            plugin.getLogger().log(Level.INFO, "[ws] Manual close, skipping reconnect");
            return;
        }
        plugin.getLogger().log(Level.INFO, String.format("[ws] Reconnecting in %d ms...", reconnectDelay.toMillis()));
        scheduler.schedule(this::doConnect, reconnectDelay.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void sendText(String message) {
        messageQueue.offer(message);
    }

    private void startMessageDispatcher() {
        senderExecutor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String msg = messageQueue.take();
                    final WebSocket ws = this.webSocket;
                    if (ws != null) {
                        boolean sent = ws.send(msg);
                        if (!sent) {
                            plugin.getLogger().log(Level.WARNING, "[ws] Failed to send: " + msg);
                        }
                    } else {
                        plugin.getLogger().log(Level.WARNING, "[ws] Not connected, dropping: " + msg);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE,
                            "[ws] Error dispatching message", e
                    );
                }
            }
        });
    }

    public void close() {
        manuallyClosed.set(true);
        final WebSocket ws = this.webSocket;
        if (ws != null) {
            ws.close(1000, "Normal closure");
        }
        scheduler.shutdown();
        senderExecutor.shutdownNow();
        client.dispatcher().executorService().shutdown();
    }

    private CompletionStage<Void> handleMessage(final String message) {
        return CompletableFuture.runAsync(() -> {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            String type = json.get("type").getAsString();
            JsonObject data = json.get("data").getAsJsonObject();
            MessageHandler handler = handlerMap.get(type);
            if (handler == null) {
                plugin.getLogger().log(Level.WARNING, String.format("[ws] No handler for type '%s'", type));
                return;
            }
            handler.execute(new MessageHandlerContext(
                    plugin,
                    plugin.getLogger(),
                    data,
                    (msgType, msgData) -> {
                        JsonObject out = new JsonObject();
                        out.addProperty("type", msgType);
                        out.add("data", msgData);
                        sendText(out.toString());
                    }
            ));
        });
    }

    private class InternalWebSocketListener extends WebSocketListener {
        @Override
        public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
            plugin.getLogger().log(Level.INFO, "[ws] Connected");
        }

        @Override
        public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
            plugin.getLogger().log(Level.FINE, "[ws] Received: " + text);
            handleMessage(text).whenComplete((v, t) -> {
                if (t != null) {
                    plugin.getLogger().log(Level.SEVERE, "[ws] Handler error", t);
                }
            });
        }

        @Override
        public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            plugin.getLogger().log(Level.INFO, String.format("[ws] Closing: %d, %s", code, reason));
            webSocket.close(code, reason);
        }

        @Override
        public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            plugin.getLogger().log(Level.INFO, String.format("[ws] Closed: %d, %s", code, reason));
            scheduleReconnect();
        }

        @Override
        public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
            plugin.getLogger().log(Level.SEVERE, "[ws] Connection failure", t);
            scheduleReconnect();
        }
    }
}
