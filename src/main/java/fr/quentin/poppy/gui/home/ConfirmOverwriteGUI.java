package fr.quentin.poppy.gui.home;

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

/**
 * Builds the small 9-slot "are you sure?" GUI shown by /sethome when the
 * sender already has a home with the given name. The pending {@link Home}
 * (built from the sender's current location, not yet saved) travels inside
 * {@link PoppyConfirmOverwriteHolder} until the player confirms — see
 * {@link HomesGUIListener#handleConfirmOverwriteClick}.
 */
public final class ConfirmOverwriteGUI {

    public static final String ACTION_CONFIRM = "confirm";
    public static final String ACTION_CANCEL = "cancel";

    private final Messages messages;
    private final NamespacedKey actionKey;

    public ConfirmOverwriteGUI(Plugin plugin, Messages messages) {
        this.messages = messages;
        this.actionKey = new NamespacedKey(plugin, "overwrite_action");
    }

    public NamespacedKey getActionKey() {
        return actionKey;
    }

    public void open(Player player, Home pendingHome) {
        PoppyConfirmOverwriteHolder holder = new PoppyConfirmOverwriteHolder(pendingHome);
        Inventory inventory = Bukkit.createInventory(holder, 9, messages.get("confirm-overwrite.title"));
        holder.setInventory(inventory);

        ItemStack filler = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.displayName(Component.empty());
        filler.setItemMeta(fillerMeta);
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        ItemStack info = new ItemStack(org.bukkit.Material.PAPER);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(messages.get("confirm-overwrite.question", "home", pendingHome.name()));
        infoMeta.lore(List.of(messages.get("confirm-overwrite.warning")));
        // Same reasoning as ConfirmDeleteGUI: tagged so onClose's safety net
        // recognizes it as ours instead of refunding it to the player.
        infoMeta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "info");
        info.setItemMeta(infoMeta);
        inventory.setItem(4, info);

        inventory.setItem(2, buildActionItem(Material.LIME_STAINED_GLASS_PANE, "confirm-overwrite.yes", ACTION_CONFIRM));
        inventory.setItem(6, buildActionItem(Material.RED_STAINED_GLASS_PANE, "confirm-overwrite.no", ACTION_CANCEL));

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