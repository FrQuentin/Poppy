package fr.quentin.poppy.gui;

import fr.quentin.poppy.manager.HomeManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class HomesGUIListener implements Listener {

    private final HomeManager homeManager;
    private final HomesGUI homesGUI;
    private final ConfirmDeleteGUI confirmDeleteGUI;
    private final Messages messages;

    public HomesGUIListener(HomeManager homeManager, HomesGUI homesGUI, ConfirmDeleteGUI confirmDeleteGUI, Messages messages) {
        this.homeManager = homeManager;
        this.homesGUI = homesGUI;
        this.confirmDeleteGUI = confirmDeleteGUI;
        this.messages = messages;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof PoppyHomesHolder) {
            handleHomesClick(event);
        } else if (event.getInventory().getHolder() instanceof PoppyConfirmDeleteHolder holder) {
            handleConfirmClick(event, holder);
        }
    }

    private void handleHomesClick(InventoryClickEvent event) {
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Home home = homeFromItem(player, event.getCurrentItem());
        if (home == null) {
            return;
        }

        if (event.isLeftClick()) {
            teleport(player, home);
        } else if (event.isRightClick()) {
            confirmDeleteGUI.open(player, home);
        }
    }

    private void handleConfirmClick(InventoryClickEvent event, PoppyConfirmDeleteHolder holder) {
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getItemMeta() == null) {
            return;
        }

        String action = clicked.getItemMeta().getPersistentDataContainer()
                .get(confirmDeleteGUI.getActionKey(), PersistentDataType.STRING);
        if (action == null) {
            return;
        }

        if (action.equals(ConfirmDeleteGUI.ACTION_CONFIRM)) {
            String homeName = holder.getHomeName();
            Home home = homeManager.getHome(player.getUniqueId(), homeName);
            if (home != null) {
                homeManager.removeHome(player.getUniqueId(), homeName);
                player.sendMessage(messages.get("delhome.success", "home", home.getName()));
            }
            homesGUI.open(player, homeManager);
        } else if (action.equals(ConfirmDeleteGUI.ACTION_CANCEL)) {
            homesGUI.open(player, homeManager);
        }
    }

    private Home homeFromItem(Player player, ItemStack item) {
        if (item == null || item.getItemMeta() == null) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        String homeName = meta.getPersistentDataContainer().get(homesGUI.getHomeNameKey(), PersistentDataType.STRING);
        if (homeName == null) {
            return null;
        }
        return homeManager.getHome(player.getUniqueId(), homeName);
    }

    private void teleport(Player player, Home home) {
        var location = home.toLocation();
        if (location == null) {
            player.sendMessage(messages.get("general.world-not-loaded"));
            return;
        }

        player.closeInventory();
        player.teleport(location);
        player.sendMessage(messages.get("home.success", "home", home.getName()));
    }
}