package fr.quentin.poppy.gui;

import fr.quentin.poppy.model.Home;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

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
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}