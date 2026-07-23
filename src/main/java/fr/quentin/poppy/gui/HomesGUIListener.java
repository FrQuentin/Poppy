package fr.quentin.poppy.gui;

import fr.quentin.poppy.manager.HomeManager;
import fr.quentin.poppy.manager.TeleportManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyLogger;
import fr.quentin.poppy.util.PoppyStats;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * Handles clicks in all three Poppy inventory GUIs (the /homes list and its
 * two confirmation screens), routed by the clicked inventory's
 * {@link org.bukkit.inventory.InventoryHolder} marker type rather than by
 * title, since titles can be re-themed in messages.yml without breaking
 * this listener.
 *
 * <p>{@link #handleConfirmDeleteClick} and {@link #handleConfirmOverwriteClick}
 * are {@code protected} rather than {@code private} to stay overridable/testable
 * from a subclass; every other handler here has no reason to be exposed and
 * stays {@code private}.
 */
public class HomesGUIListener implements Listener {

    private final JavaPlugin plugin;
    private final HomeManager homeManager;
    private final HomesGUI homesGUI;
    private final ConfirmDeleteGUI confirmDeleteGUI;
    private final ConfirmOverwriteGUI confirmOverwriteGUI;
    private final TeleportManager teleportManager;
    private final Messages messages;
    private final PoppyStats stats;
    private final PoppyLogger logger;

    public HomesGUIListener(JavaPlugin plugin, HomeManager homeManager, HomesGUI homesGUI, ConfirmDeleteGUI confirmDeleteGUI,
                            ConfirmOverwriteGUI confirmOverwriteGUI, TeleportManager teleportManager, Messages messages,
                            PoppyStats stats, PoppyLogger logger) {
        this.plugin = plugin;
        this.homeManager = homeManager;
        this.homesGUI = homesGUI;
        this.confirmDeleteGUI = confirmDeleteGUI;
        this.confirmOverwriteGUI = confirmOverwriteGUI;
        this.teleportManager = teleportManager;
        this.messages = messages;
        this.stats = stats;
        this.logger = logger;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        boolean isPoppyGui = event.getInventory().getHolder() instanceof PoppyHomesHolder
                || event.getInventory().getHolder() instanceof PoppyConfirmDeleteHolder
                || event.getInventory().getHolder() instanceof PoppyConfirmOverwriteHolder;

        if (!isPoppyGui) {
            return;
        }

        event.setCancelled(true);

        try {
            if (event.getInventory().getHolder() instanceof PoppyHomesHolder) {
                handleHomesClick(event);
            } else if (event.getInventory().getHolder() instanceof PoppyConfirmDeleteHolder holder) {
                handleConfirmDeleteClick(event, holder);
            } else if (event.getInventory().getHolder() instanceof PoppyConfirmOverwriteHolder holder) {
                handleConfirmOverwriteClick(event, holder);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error handling a click in a Poppy GUI for " + event.getWhoClicked().getName(), e);
            if (event.getWhoClicked() instanceof Player player) {
                player.sendMessage(messages.get("general.error"));
                player.closeInventory();
            }
        }
    }

    private void handleHomesClick(InventoryClickEvent event) {
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
            if (!player.hasPermission("poppy.delhome")) {
                player.sendMessage(messages.get("general.no-permission"));
                return;
            }
            confirmDeleteGUI.open(player, home);
        }
    }

    protected void handleConfirmDeleteClick(InventoryClickEvent event, PoppyConfirmDeleteHolder holder) {
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
                stats.incrementHomesDeleted();
                logger.log(PoppyLogger.Category.HOME, player, "deleted home '" + home.name() + "' (via GUI)");
                player.sendMessage(messages.get("delhome.success", "home", home.name()));
            }
            homesGUI.open(player, homeManager);
        } else if (action.equals(ConfirmDeleteGUI.ACTION_CANCEL)) {
            homesGUI.open(player, homeManager);
        }
    }

    protected void handleConfirmOverwriteClick(InventoryClickEvent event, PoppyConfirmOverwriteHolder holder) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getItemMeta() == null) {
            return;
        }

        String action = clicked.getItemMeta().getPersistentDataContainer()
                .get(confirmOverwriteGUI.getActionKey(), PersistentDataType.STRING);
        if (action == null) {
            return;
        }

        if (action.equals(ConfirmOverwriteGUI.ACTION_CONFIRM)) {
            Home pending = holder.getPendingHome();
            homeManager.addHome(player.getUniqueId(), pending);
            logger.log(PoppyLogger.Category.HOME, player, "overwrote home '" + pending.name() + "' at "
                    + pending.worldName() + ": " + (int) pending.x() + ", " + (int) pending.y() + ", " + (int) pending.z());
            player.sendMessage(messages.get("sethome.success", "home", pending.name()));
        }

        player.closeInventory();
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
        player.closeInventory();
        teleportManager.requestTeleport(player, home);
    }
}