package dev.giqnt.rbw.hook;

import com.google.gson.Gson;
import dev.giqnt.rbw.hook.adapter.Adapter;
import dev.giqnt.rbw.hook.adapter.AdapterFactory;
import dev.giqnt.rbw.hook.game.GameCreationManager;
import dev.giqnt.rbw.hook.websocket.WebSocketManager;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;

public class HookPlugin extends JavaPlugin {
    public ConfigHolder configHolder;
    public final Gson gson = new Gson();
    private WebSocketManager webSocketManager;
    public APIUtils api;
    public Adapter bedWars;
    public GameCreationManager gameCreationManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.configHolder = ConfigHolder.load(getConfig());
        this.api = new APIUtils();
        this.bedWars = AdapterFactory.getAdapter(this);
        this.gameCreationManager = new GameCreationManager(this);
        this.webSocketManager = new WebSocketManager(this);
        if (this.bedWars instanceof Listener adapter) {
            Bukkit.getPluginManager().registerEvents(adapter, this);
        }
        webSocketManager.connect().toCompletableFuture().join();
        Bukkit.getScheduler().runTaskTimer(this, this::updateMaps, 20 * 30, 20 * 30);
    }

    @Override
    public void onDisable() {
        if (this.webSocketManager != null) {
            this.webSocketManager.close();
            this.webSocketManager = null;
        }
        if (this.gameCreationManager != null) {
            this.gameCreationManager.shutdown();
            this.gameCreationManager = null;
        }
    }

    public void updateMaps() {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            final var maps = this.bedWars.getMaps();
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(String.format("https://rbw.giqnt.dev/project/%s/maps", configHolder.rbwName())))
                    .header("Authorization", "Bearer " + configHolder.token())
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(gson.toJson(maps)))
                    .build();
            try {
                final var response = this.api.request(request);
                if (response.statusCode() != 200) {
                    throw new RuntimeException(String.format("Failed to send maps to rbw bot: (%d) %s", response.statusCode(), response.body()));
                }
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("Failed to push maps data", e);
            }
        });
    }
}
