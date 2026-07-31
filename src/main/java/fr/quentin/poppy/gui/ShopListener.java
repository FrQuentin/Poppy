package fr.quentin.poppy.gui;

import fr.quentin.poppy.manager.ShopManager;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.MoneyFormat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * Handles clicks in the /shop GUIs, including pagination (previous/next
 * page, back to categories) and the actual buy/sell transactions. Every
 * click is cancelled outright, and opening a different page/category is
 * deferred a tick — same reasoning as {@code HomesGUIListener}: opening
 * an inventory synchronously from inside an {@link InventoryClickEvent}
 * handler risks a client/server desync.
 */
public class ShopListener implements Listener {

    private final JavaPlugin plugin;
    private final ShopManager shopManager;
    private final ShopGUI shopGUI;
    private final Messages messages;

    public ShopListener(JavaPlugin plugin, ShopManager shopManager, ShopGUI shopGUI, Messages messages) {
        this.plugin = plugin;
        this.shopManager = shopManager;
        this.shopGUI = shopGUI;
        this.messages = messages;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        boolean isShopGui = holder instanceof ShopMainHolder || holder instanceof ShopCategoryHolder;
        if (!isShopGui) {
            return;
        }

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        try {
            if (holder instanceof ShopMainHolder mainHolder) {
                handleMainClick(event, player, mainHolder);
            } else if (holder instanceof ShopCategoryHolder categoryHolder) {
                handleCategoryClick(event, player, categoryHolder);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error handling a click in the shop GUI for " + player.getName(), e);
            player.sendMessage(messages.get("general.error"));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof ShopMainHolder || holder instanceof ShopCategoryHolder) {
            event.setCancelled(true);
        }
    }

    private void handleMainClick(InventoryClickEvent event, Player player, ShopMainHolder holder) {
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getItemMeta() == null) {
            return;
        }
        ItemMeta meta = clicked.getItemMeta();

        if (meta.getPersistentDataContainer().has(shopGUI.getPrevPageKey(), PersistentDataType.INTEGER)) {
            openMainPage(player, holder.getPage() - 1);
            return;
        }
        if (meta.getPersistentDataContainer().has(shopGUI.getNextPageKey(), PersistentDataType.INTEGER)) {
            openMainPage(player, holder.getPage() + 1);
            return;
        }

        String categoryId = meta.getPersistentDataContainer().get(shopGUI.getCategoryKey(), PersistentDataType.STRING);
        if (categoryId == null) {
            return;
        }

        ShopManager.ShopCategory category = shopManager.findCategory(categoryId);
        if (category == null) {
            return;
        }

        openCategoryPage(player, category, 0);
    }

    private void handleCategoryClick(InventoryClickEvent event, Player player, ShopCategoryHolder holder) {
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getItemMeta() == null) {
            return;
        }
        ItemMeta meta = clicked.getItemMeta();

        ShopManager.ShopCategory category = shopManager.findCategory(holder.getCategoryId());

        if (meta.getPersistentDataContainer().has(shopGUI.getPrevPageKey(), PersistentDataType.INTEGER)) {
            if (holder.getPage() == 0) {
                // On the first page, this same button doubles as "back to the
                // category menu" — the only way out of a category view otherwise.
                openMainPage(player, 0);
            } else if (category != null) {
                openCategoryPage(player, category, holder.getPage() - 1);
            }
            return;
        }
        if (meta.getPersistentDataContainer().has(shopGUI.getNextPageKey(), PersistentDataType.INTEGER)) {
            if (category != null) {
                openCategoryPage(player, category, holder.getPage() + 1);
            }
            return;
        }

        Integer index = meta.getPersistentDataContainer().get(shopGUI.getItemIndexKey(), PersistentDataType.INTEGER);
        if (index == null || category == null || index < 0 || index >= category.items().size()) {
            return;
        }

        ShopManager.ShopItem item = category.items().get(index);
        int amount = event.isShiftClick() ? 16 : 1;

        if (event.isRightClick()) {
            handleSell(player, item, amount);
        } else if (event.isLeftClick()) {
            handleBuy(player, item, amount);
        }
    }

    private void openMainPage(Player player, int page) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                shopGUI.openMain(player, shopManager.getCategories(), page);
            }
        });
    }

    private void openCategoryPage(Player player, ShopManager.ShopCategory category, int page) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                shopGUI.openCategory(player, category, page);
            }
        });
    }

    private void handleBuy(Player player, ShopManager.ShopItem item, int amount) {
        ShopManager.TransactionResult result = shopManager.buy(player, item, amount);
        switch (result) {
            case OK -> player.sendMessage(messages.get("shop.buy-success",
                    "amount", String.valueOf(amount), "item", item.displayName(),
                    "price", MoneyFormat.format(item.buyPrice() * amount)));
            case INSUFFICIENT_FUNDS -> player.sendMessage(messages.get("shop.insufficient-funds"));
            case INVENTORY_FULL -> player.sendMessage(messages.get("shop.inventory-full"));
            case NOT_PURCHASABLE -> player.sendMessage(messages.get("shop.not-purchasable"));
            default -> {
            }
        }
    }

    private void handleSell(Player player, ShopManager.ShopItem item, int amount) {
        ShopManager.TransactionResult result = shopManager.sell(player, item, amount);
        switch (result) {
            case OK -> player.sendMessage(messages.get("shop.sell-success",
                    "amount", String.valueOf(amount), "item", item.displayName(),
                    "price", MoneyFormat.format(item.sellPrice() * amount)));
            case NOT_ENOUGH_ITEMS -> player.sendMessage(messages.get("shop.not-enough-items"));
            case NOT_SELLABLE -> player.sendMessage(messages.get("shop.not-sellable"));
            case BALANCE_FULL -> player.sendMessage(messages.get("shop.balance-full"));
            default -> {
            }
        }
    }
}