package dev.giqnt.rbw.hook.adapter;

import dev.giqnt.rbw.hook.HookPlugin;
import org.bukkit.Bukkit;

public class AdapterFactory {
    public static Adapter getAdapter(final HookPlugin plugin) {
        if (Bukkit.getPluginManager().isPluginEnabled("BedWars1058")) {
            return new MultiArenaAdapter(plugin);
        } else {
            throw new IllegalStateException("No BedWars plugin detected");
        }
    }
}
