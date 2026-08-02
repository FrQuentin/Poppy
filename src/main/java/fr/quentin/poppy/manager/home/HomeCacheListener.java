package fr.quentin.poppy.manager.home;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.logging.Level;

/**
 * Flushes and evicts a player's homes from {@link HomeManager}'s in-memory
 * cache on quit — without this, the cache grows forever since nothing else
 * removes an entry once a player has logged in at least once.
 *
 * <p>Also warms the cache asynchronously on join (see
 * {@link HomeManager#preloadAsync}), so the player's own first {@code /home}
 * or {@code /sethome} of the session doesn't trigger a synchronous file
 * read on the main thread.
 */
public class HomeCacheListener implements Listener {

    private final JavaPlugin plugin;
    private final HomeManager homeManager;

    public HomeCacheListener(JavaPlugin plugin, HomeManager homeManager) {
        this.plugin = plugin;
        this.homeManager = homeManager;
    }

    @EventHandler
    public void onJoin(@NonNull PlayerJoinEvent event) {
        homeManager.preloadAsync(event.getPlayer().getUniqueId());
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