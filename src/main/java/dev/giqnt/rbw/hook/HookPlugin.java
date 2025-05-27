package dev.giqnt.rbw.hook;

import com.google.gson.Gson;
import dev.giqnt.rbw.hook.adapter.Adapter;
import dev.giqnt.rbw.hook.adapter.AdapterFactory;
import dev.giqnt.rbw.hook.game.GameCreationManager;
import dev.giqnt.rbw.hook.leaderboard.LeaderboardManager;
import dev.giqnt.rbw.hook.placeholder.RBWPlaceholderExpansion;
import dev.giqnt.rbw.hook.profile.PlayerProfileManager;
import dev.giqnt.rbw.hook.utils.APIUtils;
import dev.giqnt.rbw.hook.websocket.WebSocketManager;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.logging.Level;

public class HookPlugin extends JavaPlugin {
    @Getter
    private final Gson gson = new Gson();
    @Getter
    private ConfigHolder configHolder;
    private WebSocketManager webSocketManager;
    @Getter
    private APIUtils api;
    @Getter
    private LeaderboardManager leaderboardManager;
    @Getter
    private PlayerProfileManager playerProfileManager;
    @Getter
    private Adapter bedWars;
    @Getter
    private GameCreationManager gameCreationManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.configHolder = ConfigHolder.load(getConfig());
        this.api = new APIUtils(this.configHolder.rbwName(), this.configHolder.token());
        this.leaderboardManager = new LeaderboardManager(this);
        this.playerProfileManager = new PlayerProfileManager(this);
        this.bedWars = AdapterFactory.getAdapter(this);
        this.gameCreationManager = new GameCreationManager(this);
        this.webSocketManager = new WebSocketManager(this);

        webSocketManager.connect();
        this.leaderboardManager.init();
        this.playerProfileManager.init();
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new RBWPlaceholderExpansion(this).register();
        }
        if (this.bedWars instanceof Listener adapter) {
            Bukkit.getPluginManager().registerEvents(adapter, this);
        }
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
                this.api.request("/maps", "PUT", gson.toJson(maps));
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Failed to update maps data", e);
            }
        });
    }
}
