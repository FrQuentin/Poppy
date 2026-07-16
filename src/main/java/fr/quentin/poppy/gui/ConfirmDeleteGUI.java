package fr.quentin.poppy.gui;

import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;

public final class ConfirmDeleteGUI {

    public static final String ACTION_CONFIRM = "confirm";
    public static final String ACTION_CANCEL = "cancel";

    private final Messages messages;
    private final NamespacedKey actionKey;

    public ConfirmDeleteGUI(Plugin plugin, Messages messages) {
        this.messages = messages;
        this.actionKey = new NamespacedKey(plugin, "confirm_action");
    }

    public NamespacedKey getActionKey() {
        return actionKey;
    }

    public void open(Player player, Home home) {
        PoppyConfirmDeleteHolder holder = new PoppyConfirmDeleteHolder(home.name());
        Inventory inventory = Bukkit.createInventory(holder, 9, messages.get("confirm.title"));
        holder.setInventory(inventory);

        ItemStack filler = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.displayName(Component.empty());
        filler.setItemMeta(fillerMeta);
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(messages.get("confirm.question", "home", home.name()));
        infoMeta.lore(List.of(messages.get("confirm.warning")));
        info.setItemMeta(infoMeta);
        inventory.setItem(4, info);

        inventory.setItem(2, buildActionItem(Material.LIME_STAINED_GLASS_PANE, "confirm.yes", ACTION_CONFIRM));
        inventory.setItem(6, buildActionItem(Material.RED_STAINED_GLASS_PANE, "confirm.no", ACTION_CANCEL));

        player.openInventory(inventory);
    }

    private ItemStack buildActionItem(Material material, String messagePath, String action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.get(messagePath));
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }
}