package fr.quentin.poppy.gui;

import fr.quentin.poppy.manager.ShopManager;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.MoneyFormat;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the two /shop GUIs: the main category menu, and each category's
 * 54-slot item listing (price and buy/sell hints shown in the lore —
 * left-click buys 1, shift+left buys 16, right-click sells 1,
 * shift+right sells 16, handled by {@code ShopListener}).
 */
public final class ShopGUI {

    private final Messages messages;
    private final NamespacedKey categoryKey;
    private final NamespacedKey itemIndexKey;
    private final NamespacedKey backButtonKey;

    public ShopGUI(Plugin plugin, Messages messages) {
        this.messages = messages;
        this.categoryKey = new NamespacedKey(plugin, "shop_category");
        this.itemIndexKey = new NamespacedKey(plugin, "shop_item_index");
        this.backButtonKey = new NamespacedKey(plugin, "shop_back_button");
    }

    public NamespacedKey getCategoryKey() {
        return categoryKey;
    }

    public NamespacedKey getItemIndexKey() {
        return itemIndexKey;
    }

    public NamespacedKey getBackButtonKey() {
        return backButtonKey;
    }

    public void openMain(Player player, List<ShopManager.ShopCategory> categories) {
        ShopMainHolder holder = new ShopMainHolder();
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.get("shop.main-title"));
        holder.setInventory(inventory);

        ItemStack filler = buildFiller();
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        int slot = 10;
        for (ShopManager.ShopCategory category : categories) {
            if (slot >= inventory.getSize() - 1) {
                break;
            }

            ItemStack icon = new ItemStack(category.icon());
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(messages.get("shop.category-name", "category", category.displayName()));
            meta.getPersistentDataContainer().set(categoryKey, PersistentDataType.STRING, category.id());
            icon.setItemMeta(meta);
            inventory.setItem(slot, icon);

            slot++;
            if (slot % 9 == 8) {
                slot += 2;
            }
        }

        player.openInventory(inventory);
    }

    public void openCategory(Player player, ShopManager.ShopCategory category) {
        ShopCategoryHolder holder = new ShopCategoryHolder(category.id());
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.get("shop.category-title", "category", category.displayName()));
        holder.setInventory(inventory);

        ItemStack filler = buildFiller();
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        List<ShopManager.ShopItem> items = category.items();
        // Reserve the last slot for the back button — items only fill up to
        // size - 1, so a category with exactly 54 items would lose its last
        // one to the button rather than silently overflowing past the
        // inventory bounds.
        int maxItemSlots = inventory.getSize() - 1;
        for (int i = 0; i < items.size() && i < maxItemSlots; i++) {
            inventory.setItem(i, buildItemIcon(items.get(i), i));
        }

        inventory.setItem(inventory.getSize() - 1, buildBackButton());

        player.openInventory(inventory);
    }

    private ItemStack buildBackButton() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.get("shop.back-button"));
        meta.getPersistentDataContainer().set(backButtonKey, PersistentDataType.INTEGER, 1);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildItemIcon(ShopManager.ShopItem item, int index) {
        ItemStack icon = new ItemStack(item.material());
        ItemMeta meta = icon.getItemMeta();
        meta.displayName(messages.get("shop.item-name", "item", item.displayName()));

        List<Component> lore = new ArrayList<>();
        if (item.purchasable()) {
            lore.add(messages.get("shop.item-lore-buy", "price", MoneyFormat.format(item.buyPrice())));
        }
        if (item.sellable()) {
            lore.add(messages.get("shop.item-lore-sell", "price", MoneyFormat.format(item.sellPrice())));
        }
        lore.add(Component.empty());
        if (item.purchasable()) {
            lore.add(messages.get("shop.item-lore-buy-hint"));
        }
        if (item.sellable()) {
            lore.add(messages.get("shop.item-lore-sell-hint"));
        }
        meta.lore(lore);

        meta.getPersistentDataContainer().set(itemIndexKey, PersistentDataType.INTEGER, index);
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack buildFiller() {
        ItemStack filler = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        meta.displayName(Component.empty());
        filler.setItemMeta(meta);
        return filler;
    }
}