package fr.quentin.poppy.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.NonNull;

/**
 * Marker holder for one page of one /shop category's item listing —
 * carries both which category and which page, so {@code ShopListener}
 * can resolve a click back to the right absolute item index and compute
 * the previous/next page.
 */
public class ShopCategoryHolder implements InventoryHolder {

    private final String categoryId;
    private final int page;
    private Inventory inventory;

    public ShopCategoryHolder(String categoryId, int page) {
        this.categoryId = categoryId;
        this.page = page;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public int getPage() {
        return page;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NonNull Inventory getInventory() {
        return inventory;
    }
}