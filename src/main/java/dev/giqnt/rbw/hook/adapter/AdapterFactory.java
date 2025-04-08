package dev.giqnt.rbw.hook.adapter;

import dev.giqnt.rbw.hook.HookPlugin;

public class AdapterFactory {
    public static Adapter getAdapter(final HookPlugin plugin) {
        if (isClassLoaded("com.andrei1058.bedwars.BedWars")) {
            return new BedWars1058Adapter(plugin);
        } else {
            throw new IllegalStateException("No BedWars plugin detected");
        }
    }

    private static boolean isClassLoaded(final String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
