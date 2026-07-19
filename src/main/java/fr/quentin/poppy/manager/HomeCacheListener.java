package fr.quentin.poppy.manager;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.logging.Level;

/**
 * Flushes and evicts a player's homes from {@link HomeManager}'s in-memory
 * cache on quit — without this, the cache grows forever since nothing else
 * removes an entry once a player has logged in at least once.
 */
public class HomeCacheListener implements Listener {

    private final JavaPlugin plugin;
    private final HomeManager homeManager;

    public HomeCacheListener(JavaPlugin plugin, HomeManager homeManager) {
        this.plugin = plugin;
        this.homeManager = homeManager;
    }

    @EventHandler
    public void onQuit(@NonNull PlayerQuitEvent event) {
        try {
            homeManager.unload(event.getPlayer().getUniqueId());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error unloading homes cache for " + event.getPlayer().getName(), e);
        }
    }
}