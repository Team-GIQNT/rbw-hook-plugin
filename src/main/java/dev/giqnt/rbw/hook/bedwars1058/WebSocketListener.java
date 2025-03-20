package dev.giqnt.rbw.hook.bedwars1058;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;

public class WebSocketListener implements WebSocket.Listener {
    private final WebSocketManager webSocketManager;

    public WebSocketListener(WebSocketManager webSocketManager) {
        this.webSocketManager = webSocketManager;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        WebSocket.Listener.super.onOpen(webSocket);
        webSocketManager.plugin.getLogger().info("[ws] client connected");
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        return CompletableFuture.supplyAsync(() -> {
            webSocketManager.plugin.getLogger().info(String.format("[ws] connection closed with statusCode: %d, reason: %s", statusCode, reason));
            return null;
        });
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        webSocketManager.plugin.getLogger().log(Level.SEVERE, "[ws] error", error);
    }

    @Override
    public CompletionStage<Void> onText(WebSocket webSocket, CharSequence data, boolean last) {
        webSocket.request(1);
        final String msg = data.toString();

        return CompletableFuture.runAsync(() -> {
            JsonObject message = JsonParser.parseString(msg).getAsJsonObject();
            webSocketManager.onMessage(message);
        }).exceptionally(ex -> {
            webSocketManager.plugin.getLogger().log(Level.SEVERE, "Failed to handle message: " + msg, ex);
            return null;
        });
    }
}
