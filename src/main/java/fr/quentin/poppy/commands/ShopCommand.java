package fr.quentin.poppy.commands;

import fr.quentin.poppy.gui.ShopGUI;
import fr.quentin.poppy.manager.ShopManager;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

public class ShopCommand extends SafeCommand {

    private final ShopManager shopManager;
    private final ShopGUI shopGUI;

    public ShopCommand(JavaPlugin plugin, ShopManager shopManager, ShopGUI shopGUI, Messages messages) {
        super(plugin, messages);
        this.shopManager = shopManager;
        this.shopGUI = shopGUI;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        shopGUI.openMain(player, shopManager.getCategories());
        return true;
    }
}