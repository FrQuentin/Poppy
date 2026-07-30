package fr.quentin.poppy.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.NonNull;

/**
 * Marker holder for the /shop main category menu. Two-step construction
 * (the {@link Inventory} is set after this holder already exists,
 * since Bukkit requires the holder to build the inventory).
 */
public class ShopMainHolder implements InventoryHolder {

    private Inventory inventory;

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NonNull Inventory getInventory() {
        return inventory;
    }
}