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
    private final Messages messages;

    public HomesGUIListener(HomeManager homeManager, HomesGUI homesGUI, Messages messages) {
        this.homeManager = homeManager;
        this.homesGUI = homesGUI;
        this.messages = messages;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof PoppyHomesHolder)) {
            return;
        }

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getItemMeta() == null) {
            return;
        }

        ItemMeta meta = clicked.getItemMeta();
        String homeName = meta.getPersistentDataContainer().get(homesGUI.getHomeNameKey(), PersistentDataType.STRING);
        if (homeName == null) {
            return;
        }

        Home home = homeManager.getHome(player.getUniqueId(), homeName);
        if (home == null) {
            return;
        }

        if (event.isLeftClick()) {
            teleport(player, home);
        } else if (event.isRightClick()) {
            homeManager.removeHome(player.getUniqueId(), home.getName());
            player.sendMessage(messages.get("delhome.success", "home", home.getName()));
            homesGUI.open(player, homeManager);
        }
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
