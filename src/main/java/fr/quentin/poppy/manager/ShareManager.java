package fr.quentin.poppy.manager;

import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.PoppyConfig;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ShareManager {

    private final JavaPlugin plugin;
    private final PoppyConfig config;
    private final Map<String, Home> shares = new HashMap<>();

    public ShareManager(JavaPlugin plugin, PoppyConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public String share(Home home) {
        String token = UUID.randomUUID().toString().replace("-", "");
        shares.put(token, home);

        Bukkit.getScheduler().runTaskLater(plugin, () -> shares.remove(token), config.sharehomeExpirySeconds() * 20L);

        return token;
    }

    public Home get(String token) {
        return shares.get(token);
    }
}