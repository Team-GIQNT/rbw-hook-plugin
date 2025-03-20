package dev.giqnt.rbw.hook.bedwars1058;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.stream.Collectors;

public class HookPlugin extends JavaPlugin {
    public ConfigHolder configHolder;
    public final Gson gson = new Gson();
    private WebSocketManager webSocketManager;
    public APIUtils api;
    public BedWarsUtils bedWars;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.configHolder = ConfigHolder.load(getConfig());
        this.api = new APIUtils(this);
        this.bedWars = new BedWarsUtils(this);
        this.webSocketManager = new WebSocketManager(this);
        Bukkit.getPluginManager().registerEvents(bedWars, this);
        Bukkit.getScheduler().runTask(this, () -> webSocketManager.start());
        Bukkit.getScheduler().runTaskTimer(this, this::updateMaps, 20 * 30, 20 * 30);
    }

    @Override
    public void onDisable() {
        this.webSocketManager.stop();
    }

    public void updateMaps() {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            final var maps = this.bedWars.getMapsInfo().values().stream()
                    .flatMap(innerMap -> innerMap.values().stream())
                    .collect(Collectors.toList());
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
