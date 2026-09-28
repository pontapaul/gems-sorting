package com.github.wssorting;

import org.bukkit.plugin.java.JavaPlugin;

public final class WsSortingPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        int radius = Math.max(1, getConfig().getInt("radius", 128));
        SortingService service = new SortingService(this, radius);
        getServer().getPluginManager().registerEvents(new SortingListener(service), this);
        getLogger().info("Sorting radius: " + radius + " blocks");
    }
}
