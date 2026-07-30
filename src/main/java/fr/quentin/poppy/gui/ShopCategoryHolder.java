package fr.quentin.poppy.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.NonNull;

/**
 * Marker holder for one /shop category's item listing, carrying which
 * category it belongs to so {@code ShopListener} can resolve clicks back
 * to the right item list.
 */
public class ShopCategoryHolder implements InventoryHolder {

    private final String categoryId;
    private Inventory inventory;

    public ShopCategoryHolder(String categoryId) {
        this.categoryId = categoryId;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NonNull Inventory getInventory() {
        return inventory;
    }
}