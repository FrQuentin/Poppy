package fr.quentin.poppy.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.NonNull;

/**
 * Marker holder for one page of the /shop main category menu — carries
 * which page it's showing so {@code ShopListener} can compute the
 * previous/next page to open on a navigation click.
 */
public class ShopMainHolder implements InventoryHolder {

    private final int page;
    private Inventory inventory;

    public ShopMainHolder(int page) {
        this.page = page;
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