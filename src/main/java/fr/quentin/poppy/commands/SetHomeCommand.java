package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.HomeManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class SetHomeCommand implements CommandExecutor {

    private final HomeManager homeManager;
    private final Messages messages;

    public SetHomeCommand(HomeManager homeManager, Messages messages) {
        this.homeManager = homeManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
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
            player.sendMessage(messages.get("sethome.already-exists", "home", name));
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
