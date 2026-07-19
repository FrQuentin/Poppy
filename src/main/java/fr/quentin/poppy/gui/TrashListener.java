package fr.quentin.poppy.gui;

import fr.quentin.poppy.util.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.logging.Level;

/**
 * Empties the /trash inventory whenever it's closed — anything the player
 * left inside is deleted, matching the "trash can" semantics described in
 * plugin.yml's command usage.
 */
public class TrashListener implements Listener {

    private final JavaPlugin plugin;
    private final Messages messages;

    public TrashListener(JavaPlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    @EventHandler
    public void onClose(@NonNull InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof TrashHolder)) {
            return;
        }

        try {
            event.getInventory().clear();

            if (event.getPlayer() instanceof Player player) {
                player.sendMessage(messages.get("trash.emptied"));
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error emptying the trash for " + event.getPlayer().getName(), e);
        }
    }
}