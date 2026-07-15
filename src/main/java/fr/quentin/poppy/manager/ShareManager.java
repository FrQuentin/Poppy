package fr.quentin.poppy.manager;

import fr.quentin.poppy.model.Home;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ShareManager {

    private final JavaPlugin plugin;
    private final Map<String, Home> shares = new HashMap<>();
    private final int expirySeconds;

    public ShareManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.expirySeconds = Math.max(30, plugin.getConfig().getInt("sharehome-expiry-seconds", 300));
    }

    public String share(Home home) {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        shares.put(token, home);

        Bukkit.getScheduler().runTaskLater(plugin, () -> shares.remove(token), expirySeconds * 20L);

        return token;
    }

    public Home get(String token) {
        return shares.get(token);
    }
}