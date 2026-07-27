package fr.quentin.poppy.gui;

import fr.quentin.poppy.manager.HomeManager;
import fr.quentin.poppy.manager.TeleportManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyLogger;
import fr.quentin.poppy.util.PoppyStats;
import org.bukkit.Bukkit;
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
 *
 * <p><b>Never open or close an inventory synchronously from inside this
 * event handler.</b> Bukkit/Paper haven't finished reconciling the
 * client's view with the server's at the point a click handler runs — the
 * result packet for the click itself hasn't been sent yet. Opening a new
 * inventory (or closing the current one) mid-click is the historical
 * number-one source of GUI item duplication in plugins: the cursor item
 * ({@link InventoryClickEvent#getCursor()}) can end up dropped
 * server-side while the client still shows it held, or the reverse.
 * Cancelling the event (already done here) reduces but does not eliminate
 * this — the cursor item itself is untouched by cancellation. Every
 * open/close in this class therefore goes through {@link #openLater} (and
 * {@link #closeLater} for {@link #teleport}), which defers to the next
 * tick, by which point the click's own packet exchange has completed; and
 * {@link #clearCursor} explicitly empties the cursor — via
 * {@link org.bukkit.entity.HumanEntity#setItemOnCursor(ItemStack)} rather
 * than the deprecated {@code InventoryClickEvent#setCursor(ItemStack)} —
 * before any deferred open, so there's nothing left in transit for the
 * timing window to act on.
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
                closeLater(player);
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
            clearCursor(event);
            openLater(player, () -> confirmDeleteGUI.open(player, home));
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

        clearCursor(event);

        if (action.equals(ConfirmDeleteGUI.ACTION_CONFIRM)) {
            String homeName = holder.getHomeName();
            Home home = homeManager.getHome(player.getUniqueId(), homeName);
            if (home != null) {
                homeManager.removeHome(player.getUniqueId(), homeName);
                stats.incrementHomesDeleted();
                logger.log(PoppyLogger.Category.HOME, player, "deleted home '" + home.name() + "' (via GUI)");
                player.sendMessage(messages.get("delhome.success", "home", home.name()));
            }
            openLater(player, () -> homesGUI.open(player, homeManager));
        } else if (action.equals(ConfirmDeleteGUI.ACTION_CANCEL)) {
            openLater(player, () -> homesGUI.open(player, homeManager));
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

        clearCursor(event);

        if (action.equals(ConfirmOverwriteGUI.ACTION_CONFIRM)) {
            Home pending = holder.getPendingHome();
            homeManager.addHome(player.getUniqueId(), pending);
            logger.log(PoppyLogger.Category.HOME, player, "overwrote home '" + pending.name() + "' at "
                    + pending.worldName() + ": " + (int) pending.x() + ", " + (int) pending.y() + ", " + (int) pending.z());
            player.sendMessage(messages.get("sethome.success", "home", pending.name()));
        }

        closeLater(player);
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
        closeLater(player);
        teleportManager.requestTeleport(player, home);
    }

    /**
     * Empties the cursor before any deferred open/close — see the
     * class-level doc. {@link InventoryClickEvent#setCancelled(boolean)}
     * already blocks the click's item movement, but leaves whatever was
     * already on the cursor (e.g. picked up from the player's own
     * inventory in a separate, earlier click) untouched; without this,
     * that item is exactly what's at risk of ending up in an inconsistent
     * client/server state across the deferred GUI switch.
     */
    private void clearCursor(InventoryClickEvent event) {
        event.getWhoClicked().setItemOnCursor(null);
    }

    /**
     * Defers opening a GUI to the next tick — see the class-level doc for
     * why opening synchronously from inside an InventoryClickEvent handler
     * is unsafe.
     */
    private void openLater(Player player, Runnable opener) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                opener.run();
            }
        });
    }

    /**
     * Defers closing the player's inventory to the next tick — same
     * reasoning as {@link #openLater}.
     */
    private void closeLater(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.closeInventory();
            }
        });
    }
}