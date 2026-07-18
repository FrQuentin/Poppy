package fr.quentin.poppy.manager;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

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
    public void onDeath(PlayerDeathEvent event) {
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
}