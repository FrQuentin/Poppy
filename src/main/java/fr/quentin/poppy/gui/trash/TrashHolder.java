package fr.quentin.poppy.gui.trash;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.NonNull;

/**
 * Marker holder so {@link TrashListener} can recognize a /trash inventory
 * on close, without relying on the title.
 *
 * <p>Two-step construction: {@link #setInventory(Inventory)} must be called
 * immediately after the constructor, before this holder is attached to any
 * inventory that could be read from — {@link TrashGUI#open} does this
 * correctly. {@link #getInventory()} is {@code @NonNull} on the trust that
 * this ordering is respected.
 */
public class TrashHolder implements InventoryHolder {

    private Inventory inventory;

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NonNull Inventory getInventory() {
        return inventory;
    }
}