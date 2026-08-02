package fr.quentin.poppy.listeners.misc;

import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.logging.Level;

public class JoinQuitListener implements Listener {

    private final JavaPlugin plugin;
    private final Messages messages;
    private final PoppyConfig config;

    public JoinQuitListener(JavaPlugin plugin, Messages messages, PoppyConfig config) {
        this.plugin = plugin;
        this.messages = messages;
        this.config = config;
    }

    @EventHandler
    public void onJoin(@NonNull PlayerJoinEvent event) {
        if (!config.customJoinMessage()) {
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
    public void onQuit(@NonNull PlayerQuitEvent event) {
        if (!config.customQuitMessage()) {
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