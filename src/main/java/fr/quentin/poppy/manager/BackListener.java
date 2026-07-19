package fr.quentin.poppy.manager;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.logging.Level;

/**
 * Records the player's death location into {@link BackManager} so /back can
 * return them there, toggleable via {@code back-on-death} in config.yml.
 * Also evicts the player's entry from {@link BackManager} on quit, so that
 * map doesn't retain a Location forever for players who log off.
 */
public class BackListener implements Listener {

    private final JavaPlugin plugin;
    private final BackManager backManager;
    private final boolean recordOnDeath;

    public BackListener(JavaPlugin plugin, BackManager backManager) {
        this.plugin = plugin;
        this.backManager = backManager;
        this.recordOnDeath = plugin.getConfig().getBoolean("back-on-death", true);
    }

    @EventHandler
    public void onDeath(@NonNull PlayerDeathEvent event) {
        try {
            if (!recordOnDeath) {
                return;
            }

            Player player = event.getEntity();
            backManager.recordLocation(player);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in BackListener#onDeath for " + event.getEntity().getName(), e);
        }
    }

    @EventHandler
    public void onQuit(@NonNull PlayerQuitEvent event) {
        backManager.remove(event.getPlayer().getUniqueId());
    }
}