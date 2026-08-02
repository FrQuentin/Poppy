package fr.quentin.poppy.gui.home;

import fr.quentin.poppy.model.Home;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.NonNull;

/**
 * Marker holder identifying a home-overwrite confirmation inventory,
 * carrying the not-yet-saved {@link Home} built from the sender's current
 * location so {@link HomesGUIListener} can persist it on confirmation
 * without re-reading the player's position later.
 *
 * <p>Two-step construction: {@link #setInventory(Inventory)} must be called
 * immediately after the constructor, before this holder is attached to any
 * inventory that could be read from — {@link ConfirmOverwriteGUI#open} does
 * this correctly. {@link #getInventory()} is {@code @NonNull} on the trust
 * that this ordering is respected.
 */
public class PoppyConfirmOverwriteHolder implements InventoryHolder {

    private final Home pendingHome;
    private Inventory inventory;

    public PoppyConfirmOverwriteHolder(Home pendingHome) {
        this.pendingHome = pendingHome;
    }

    public Home getPendingHome() {
        return pendingHome;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NonNull Inventory getInventory() {
        return inventory;
    }
}