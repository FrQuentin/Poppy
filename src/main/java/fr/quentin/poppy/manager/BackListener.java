package fr.quentin.poppy.manager;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class BackListener implements Listener {

    private final BackManager backManager;
    private final boolean recordOnDeath;

    public BackListener(JavaPlugin plugin, BackManager backManager) {
        this.backManager = backManager;
        this.recordOnDeath = plugin.getConfig().getBoolean("back-on-death", true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!recordOnDeath) {
            return;
        }

        Player player = event.getEntity();
        backManager.recordLocation(player);
    }
}