package fr.quentin.poppy.listeners;

import fr.quentin.poppy.util.Messages;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.command.UnknownCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.logging.Level;

/**
 * Replaces vanilla's "Unknown command" message with Poppy's own, toggleable
 * via {@code custom-unknown-command-message} in config.yml.
 */
public class UnknownCommandListener implements Listener {

    private final JavaPlugin plugin;
    private final Messages messages;
    private final boolean enabled;

    public UnknownCommandListener(JavaPlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
        this.enabled = plugin.getConfig().getBoolean("custom-unknown-command-message", true);
    }

    @EventHandler
    public void onUnknownCommand(@NonNull UnknownCommandEvent event) {
        if (!enabled) {
            return;
        }

        try {
            event.message(messages.get("general.unknown-command"));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error setting unknown command message for " + event.getSender().getName(), e);
        }
    }
}