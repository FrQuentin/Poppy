package fr.quentin.poppy.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.NonNull;

/**
 * Marker holder identifying a home-deletion confirmation inventory, carrying
 * the name of the home pending deletion so {@link HomesGUIListener} can act
 * on it without depending on inventory titles or slot positions.
 *
 * <p>Two-step construction: {@link #setInventory(Inventory)} must be called
 * immediately after the constructor, before this holder is attached to any
 * inventory that could be read from — {@link ConfirmDeleteGUI#open} does
 * this correctly. {@link #getInventory()} is {@code @NonNull} on the trust
 * that this ordering is respected.
 */
public class PoppyConfirmDeleteHolder implements InventoryHolder {

    private final String homeName;
    private Inventory inventory;

    public PoppyConfirmDeleteHolder(String homeName) {
        this.homeName = homeName;
    }

    public String getHomeName() {
        return homeName;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NonNull Inventory getInventory() {
        return inventory;
    }
}