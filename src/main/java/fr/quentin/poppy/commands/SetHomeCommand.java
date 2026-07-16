package fr.quentin.poppy.commands;

import fr.quentin.poppy.gui.ConfirmOverwriteGUI;
import fr.quentin.poppy.manager.HomeManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

public class SetHomeCommand implements CommandExecutor {

    private final HomeManager homeManager;
    private final ConfirmOverwriteGUI confirmOverwriteGUI;
    private final Messages messages;

    public SetHomeCommand(HomeManager homeManager, ConfirmOverwriteGUI confirmOverwriteGUI, Messages messages) {
        this.homeManager = homeManager;
        this.confirmOverwriteGUI = confirmOverwriteGUI;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get("general.only-player"));
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(messages.get("sethome.usage"));
            return true;
        }

        String name = args[0];

        if (homeManager.hasHome(player.getUniqueId(), name)) {
            Home pending = Home.fromLocation(name, player.getLocation());
            confirmOverwriteGUI.open(player, pending);
            return true;
        }

        if (homeManager.isFull(player.getUniqueId())) {
            player.sendMessage(messages.get("sethome.full", "max", String.valueOf(HomeManager.MAX_HOMES)));
            return true;
        }

        Home home = Home.fromLocation(name, player.getLocation());
        homeManager.addHome(player.getUniqueId(), home);

        player.sendMessage(messages.get("sethome.success", "home", name));
        return true;
    }
}