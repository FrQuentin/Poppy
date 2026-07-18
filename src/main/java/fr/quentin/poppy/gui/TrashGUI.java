package fr.quentin.poppy.gui;

import fr.quentin.poppy.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

public final class TrashGUI {

    private final Messages messages;
    private final int size;

    public TrashGUI(JavaPlugin plugin, Messages messages) {
        this.messages = messages;

        int configured = plugin.getConfig().getInt("trash-size", 27);
        int normalized = (configured / 9) * 9; // round down to a multiple of 9
        if (normalized < 9) {
            normalized = 27; // sane fallback if misconfigured
        }
        this.size = Math.min(54, normalized);
    }

    public void open(Player player) {
        TrashHolder holder = new TrashHolder();
        Inventory inventory = Bukkit.createInventory(holder, size, messages.get("trash.title"));
        holder.setInventory(inventory);
        player.openInventory(inventory);
    }
}