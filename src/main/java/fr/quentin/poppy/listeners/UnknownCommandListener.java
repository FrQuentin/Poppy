package fr.quentin.poppy.listeners;

import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.command.UnknownCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.logging.Level;

public class UnknownCommandListener implements Listener {

    private final JavaPlugin plugin;
    private final Messages messages;
    private final PoppyConfig config;

    public UnknownCommandListener(JavaPlugin plugin, Messages messages, PoppyConfig config) {
        this.plugin = plugin;
        this.messages = messages;
        this.config = config;
    }

    @EventHandler
    public void onUnknownCommand(@NonNull UnknownCommandEvent event) {
        if (!config.customUnknownCommandMessage()) {
            return;
        }

        try {
            event.message(messages.get("general.unknown-command"));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error setting unknown command message for " + event.getSender().getName(), e);
        }
    }
}