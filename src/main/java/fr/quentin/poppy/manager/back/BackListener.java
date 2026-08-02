package fr.quentin.poppy.manager.back;

import fr.quentin.poppy.util.PoppyConfig;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.logging.Level;

public class BackListener implements Listener {

    private final JavaPlugin plugin;
    private final BackManager backManager;
    private final PoppyConfig config;

    public BackListener(JavaPlugin plugin, BackManager backManager, PoppyConfig config) {
        this.plugin = plugin;
        this.backManager = backManager;
        this.config = config;
    }

    @EventHandler
    public void onDeath(@NonNull PlayerDeathEvent event) {
        try {
            if (!config.backOnDeath()) {
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