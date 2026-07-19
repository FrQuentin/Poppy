package fr.quentin.poppy.manager;

import fr.quentin.poppy.model.Home;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Holds short-lived tokens created by /sharehome, each mapped to the shared
 * {@link Home}. A token is the only access control for the click-to-teleport
 * link — see {@link fr.quentin.poppy.commands.PoppyGotoCommand} — so it's
 * scoped to {@code sharehome-expiry-seconds} (config.yml, 300s by default,
 * minimum 30s) and self-removes via a scheduled task rather than lingering
 * in memory.
 */
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