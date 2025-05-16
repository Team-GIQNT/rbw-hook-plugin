package dev.giqnt.rbw.hook;

import com.google.gson.Gson;
import dev.giqnt.rbw.hook.adapter.Adapter;
import dev.giqnt.rbw.hook.adapter.AdapterFactory;
import dev.giqnt.rbw.hook.game.GameCreationManager;
import dev.giqnt.rbw.hook.utils.APIUtils;
import dev.giqnt.rbw.hook.websocket.WebSocketManager;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;

public class HookPlugin extends JavaPlugin {
    @Getter
    private final Gson gson = new Gson();
    @Getter
    private ConfigHolder configHolder;
    private WebSocketManager webSocketManager;
    @Getter
    private APIUtils api;
    @Getter
    private Adapter bedWars;
    @Getter
    private GameCreationManager gameCreationManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.configHolder = ConfigHolder.load(getConfig());
        this.api = new APIUtils(this.configHolder.rbwName(), this.configHolder.token());
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
            try {
                final var response = this.api.request("/maps", "PUT", gson.toJson(maps));
                if (response.statusCode() != 200) {
                    throw new RuntimeException(String.format("Failed to send maps to rbw bot: (%d) %s", response.statusCode(), response.body()));
                }
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("Failed to push maps data", e);
            }
        });
    }
}
