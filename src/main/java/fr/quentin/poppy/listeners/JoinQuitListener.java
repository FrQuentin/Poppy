package fr.quentin.poppy.listeners;

import fr.quentin.poppy.util.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public class JoinQuitListener implements Listener {

    private final JavaPlugin plugin;
    private final Messages messages;
    private final boolean customJoin;
    private final boolean customQuit;

    public JoinQuitListener(JavaPlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
        this.customJoin = plugin.getConfig().getBoolean("custom-join-message", true);
        this.customQuit = plugin.getConfig().getBoolean("custom-quit-message", true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!customJoin) {
            return;
        }

        try {
            Player player = event.getPlayer();
            event.joinMessage(messages.get("join-quit.join", "player", player.getName()));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error setting join message for " + event.getPlayer().getName(), e);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!customQuit) {
            return;
        }

        try {
            Player player = event.getPlayer();
            event.quitMessage(messages.get("join-quit.quit", "player", player.getName()));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error setting quit message for " + event.getPlayer().getName(), e);
        }
    }
}