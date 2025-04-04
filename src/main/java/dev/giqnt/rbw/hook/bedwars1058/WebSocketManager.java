package dev.giqnt.rbw.hook.bedwars1058;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class WebSocketManager {
    public final HookPlugin plugin;
    private WebSocket ws;
    private final ConcurrentLinkedQueue<String> messageQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean sending = new AtomicBoolean(false);

    public WebSocketManager(HookPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                () -> {
                    if (this.ws == null || this.ws.isInputClosed() || this.ws.isOutputClosed()) {
                        start();
                    } else {
                        this.send("ping", null);
                    }
                },
                20 * 20,
                20 * 20
        );
    }

    public void start() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final var listener = new WebSocketListener(this);
            try (final var client = HttpClient.newHttpClient()) {
                this.ws = client
                        .newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + plugin.configHolder.token())
                        .buildAsync(URI.create(String.format("wss://rbw.giqnt.dev/project/%s/ws", plugin.configHolder.rbwName())), listener)
                        .join();
            }
        });
    }

    public void stop() {
        if (this.ws == null) return;
        this.ws.abort();
    }

    public void send(final JsonObject message) {
        final String text = plugin.gson.toJson(message);
        messageQueue.offer(text);
        processQueue();
    }

    public void send(final String type, @Nullable final JsonObject data) {
        final JsonObject json = new JsonObject();
        json.addProperty("type", type);
        if (data != null) {
            json.add("data", data);
        }
        this.send(json);
    }

    private void processQueue() {
        if (!sending.compareAndSet(false, true)) {
            return;
        }
        sendNext();
    }

    private void sendNext() {
        String message = messageQueue.poll();
        if (message == null) {
            sending.set(false);
            return;
        }
        ws.sendText(message, true)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        // 전송 실패 처리: 예외 로깅 또는 재시도 로직 추가 가능
                        System.err.println("Send failed: " + ex.getMessage());
                    }
                    // 다음 메시지 전송 시도
                    sendNext();
                });
    }

    public void onMessage(final JsonObject message) {
        final String type = message.get("type").getAsString();
        if (type.equals("pong")) return;
        final JsonObject data = message.get("data").getAsJsonObject();
        switch (type) {
            case "send_link_code" -> {
                final String playerName = data.get("name").getAsString();
                final String code = data.get("code").getAsString();
                final Player player = Bukkit.getPlayerExact(playerName);
                if (player == null || !player.isOnline()) {
                    plugin.getLogger().info("failed to send code to " + playerName + " because player is not online");
                    return;
                }
                player.sendMessage(ChatColor.translateAlternateColorCodes(
                        '&',
                        "&eYour code is: &f" + code
                ));
                plugin.getLogger().info("sent code to " + playerName);
            }
            case "request_player_status" -> {
                final String playerName = data.get("name").getAsString();
                final Player player = Bukkit.getPlayerExact(playerName);
                if (player == null) {
                    sendPlayerStatus(playerName, false);
                    return;
                }
                sendPlayerStatus(player.getName(), plugin.bedWars.isReady(player));
            }
            case "create_game" -> {
                final int id = data.get("id").getAsInt();
                final String mapName = data.get("mapId").getAsString();
                final var typeToken = new TypeToken<ArrayList<ArrayList<String>>>() {};
                final List<List<String>> teamsInfo = plugin.gson.fromJson(data.get("teams").getAsJsonArray(), typeToken.getType());
                final var offlinePlayers = new ArrayList<String>();
                final List<List<Player>> teamPlayers = teamsInfo.stream().map(team -> team.stream().map((name) -> {
                    final Player player = Bukkit.getPlayerExact(name);
                    if (player == null || !this.plugin.bedWars.isReady(player)) {
                        offlinePlayers.add(name);
                        return null;
                    }
                    return player;
                }).toList()).toList();
                if (!offlinePlayers.isEmpty()) {
                    sendGameCreateFailure(id, offlinePlayers);
                    return;
                }
                this.plugin.gameQueue.queue(id, mapName, teamPlayers).handle((a, ex) -> {
                    if (ex != null) {
                        plugin.getLogger().log(Level.SEVERE, "Failed to create game", ex);
                        if (ex instanceof GameCreateException) {
                            sendGameCreateFailure(id, ex.getMessage());
                        } else if (ex instanceof RuntimeException) {
                            sendGameCreateFailure(id, "Unknown error occurred");
                        }
                    } else {
                        sendGameCreateSuccess(id);
                    }
                    return null;
                });
            }
        }
    }

    private void sendPlayerStatus(final String name, final boolean isReady) {
        final JsonObject responseData = new JsonObject();
        responseData.addProperty("name", name);
        responseData.addProperty("isReady", isReady);
        send("player_status", responseData);
    }

    private void sendGameCreateFailure(final int id, final String message) {
        final JsonObject responseData = new JsonObject();
        responseData.addProperty("id", id);
        responseData.addProperty("message", message);
        send("game_create_failure", responseData);
    }

    private void sendGameCreateFailure(final int id, final List<String> offlinePlayers) {
        final JsonObject responseData = new JsonObject();
        responseData.addProperty("id", id);
        responseData.addProperty("message", "Some players are offline.");
        final JsonArray missingPlayers = new JsonArray();
        offlinePlayers.forEach(missingPlayers::add);
        responseData.add("missingPlayers", missingPlayers);
        send("game_create_failure", responseData);
    }

    private void sendGameCreateSuccess(final int id) {
        final JsonObject responseData = new JsonObject();
        responseData.addProperty("id", id);
        responseData.addProperty("serverGameId", String.valueOf(id));
        send("game_create_success", responseData);
    }
}
