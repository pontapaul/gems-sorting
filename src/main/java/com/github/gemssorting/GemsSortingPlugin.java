package com.github.gemssorting;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

public final class GemsSortingPlugin extends JavaPlugin {

    private WebServer web;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Add keys missing from an older config.yml, then read it back: getString(path, def)
        // returns def, not the jar's default, for keys that are not in the file.
        getConfig().options().copyDefaults(true);
        saveConfig();
        reloadConfig();

        Path data = getDataFolder().toPath();
        GroupStore groups = new GroupStore(data.resolve("groups.json"), getLogger());
        boolean groupsLoaded = true;
        try {
            groups.load();
        } catch (IOException | RuntimeException e) {
            groupsLoaded = false;
            getLogger().log(Level.SEVERE, "Could not read groups.json: .group tags won't work and the web editor"
                    + " stays off until the file is fixed", e);
        }

        int radius = Math.max(1, getConfig().getInt("radius", 128));
        SortingService service = new SortingService(this, radius, groups);
        getServer().getPluginManager().registerEvents(new SortingListener(service), this);
        getLogger().info("Sorting radius: " + radius + " blocks");

        Auth auth = null;
        String publicUrl = getConfig().getString("web.public-url", "").replaceAll("/+$", "");
        if (getConfig().getBoolean("web.enabled", true) && groupsLoaded) {
            String bind = getConfig().getString("web.bind", "0.0.0.0");
            int port = getConfig().getInt("web.port", 8101);
            if (publicUrl.isEmpty()) {
                String ip = getServer().getIp();
                publicUrl = "http://" + (ip.isBlank() || ip.equals("0.0.0.0") ? "localhost" : ip) + ":" + port;
                getLogger().warning("web.public-url is not set: /gems web links point to " + publicUrl
                        + ", which only works from this machine or network");
            }
            Duration sessions = Duration.ofDays(Math.max(1, getConfig().getInt("web.session-days", 30)));
            Assets assets = new Assets(data.resolve("cache"), getServer().getMinecraftVersion(), getLogger());
            getServer().getScheduler().runTaskAsynchronously(this, assets::prepare);
            auth = new Auth(this, data.resolve("sessions.json"), sessions);
            try {
                web = new WebServer(groups, assets, auth, bind, port, publicUrl.startsWith("https://"), getLogger());
                web.start();
                getLogger().info("Web interface on " + bind + ":" + port + " (" + publicUrl + ")");
            } catch (IOException e) {
                auth = null;
                getLogger().log(Level.SEVERE, "Could not start the web interface on port " + port, e);
            }
        }
        getCommand("gems").setExecutor(new GemsCommand(auth, publicUrl));
    }

    @Override
    public void onDisable() {
        if (web != null) {
            web.stop();
        }
    }
}
