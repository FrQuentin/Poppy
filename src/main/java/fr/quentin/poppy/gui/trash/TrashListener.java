package fr.quentin.poppy.gui.trash;

import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyLogger;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.logging.Level;

/**
 * Empties the /trash inventory whenever it's closed — anything the player
 * left inside is deleted, matching the "trash can" semantics described in
 * plugin.yml's command usage.
 *
 * <p>Since this permanently destroys items with no way to recover them,
 * every non-empty trash emptying is logged under
 * {@link PoppyLogger.Category#ADMIN} — being able to answer "what happened
 * to it?" after an accidental deletion is exactly what an activity log is
 * for.
 */
public class TrashListener implements Listener {

    private final JavaPlugin plugin;
    private final Messages messages;
    private final PoppyLogger logger;

    public TrashListener(JavaPlugin plugin, Messages messages, PoppyLogger logger) {
        this.plugin = plugin;
        this.messages = messages;
        this.logger = logger;
    }

    @EventHandler
    public void onClose(@NonNull InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof TrashHolder)) {
            return;
        }

        try {
            Inventory inventory = event.getInventory();
            String summary = summarizeContents(inventory);

            inventory.clear();

            if (event.getPlayer() instanceof Player player) {
                if (!summary.isEmpty()) {
                    logger.log(PoppyLogger.Category.ADMIN, player, "emptied /trash, destroyed: " + summary);
                }
                player.sendMessage(messages.get("trash.emptied"));
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error emptying the trash for " + event.getPlayer().getName(), e);
        }
    }

    private String summarizeContents(Inventory inventory) {
        StringBuilder summary = new StringBuilder();

        for (ItemStack item : inventory.getContents()) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (!summary.isEmpty()) {
                summary.append(", ");
            }
            summary.append(item.getAmount()).append("x ").append(item.getType());
        }

        return summary.toString();
    }
}