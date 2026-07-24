package fr.quentin.poppy.gui;

import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

public final class TrashGUI {

    private final Messages messages;
    private final PoppyConfig config;

    public TrashGUI(Messages messages, PoppyConfig config) {
        this.messages = messages;
        this.config = config;
    }

    public void open(Player player) {
        TrashHolder holder = new TrashHolder();
        Inventory inventory = Bukkit.createInventory(holder, config.trashSize(), messages.get("trash.title"));
        holder.setInventory(inventory);
        player.openInventory(inventory);
    }
}