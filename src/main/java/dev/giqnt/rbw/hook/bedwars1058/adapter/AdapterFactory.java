package dev.giqnt.rbw.hook.bedwars1058.adapter;

import dev.giqnt.rbw.hook.bedwars1058.HookPlugin;
import org.bukkit.Bukkit;

public class AdapterFactory {
    public static Adapter getAdapter(final HookPlugin plugin) {
        if (Bukkit.getPluginManager().isPluginEnabled("BedWars1058")) {
            return new MultiArenaAdapter(plugin);
        } else if (Bukkit.getPluginManager().isPluginEnabled("BedWarsProxy")) {
            return new ProxyAdapter(plugin);
        } else {
            throw new IllegalStateException("BedWars1058 plugin is not enabled");
        }
    }
}
